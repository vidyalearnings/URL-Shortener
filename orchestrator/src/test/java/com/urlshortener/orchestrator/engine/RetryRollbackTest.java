package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.approvals.ApprovalGate;
import com.urlshortener.orchestrator.approvals.ApprovalMode;
import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLogger;
import com.urlshortener.orchestrator.policy.PolicyEngine;
import com.urlshortener.orchestrator.stages.StageExecutor;
import com.urlshortener.orchestrator.stages.StageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryRollbackTest {

    /** Fails the first {@code failuresBeforeSuccess} attempts, then succeeds. */
    static class FlakyStage implements StageExecutor {
        final int failuresBeforeSuccess;
        final AtomicInteger attempts = new AtomicInteger(0);

        FlakyStage(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public StageResult execute(RunContext context, StageDef def) {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                return StageResult.failure("simulated failure on attempt " + attempt, Map.of());
            }
            return StageResult.success("succeeded on attempt " + attempt, Map.of());
        }
    }

    /** Always fails; records whether rollback() was invoked. */
    static class AlwaysFailsStage implements StageExecutor {
        final AtomicInteger attempts = new AtomicInteger(0);
        final AtomicBoolean rollbackCalled = new AtomicBoolean(false);

        @Override
        public StageResult execute(RunContext context, StageDef def) {
            attempts.incrementAndGet();
            return StageResult.failure("always fails", Map.of());
        }

        @Override
        public void rollback(RunContext context, StageDef def) {
            rollbackCalled.set(true);
        }
    }

    static class SucceedsAfterDelay implements StageExecutor {
        final long delayMs;

        SucceedsAfterDelay(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public StageResult execute(RunContext context, StageDef def) throws Exception {
            Thread.sleep(delayMs);
            return StageResult.success("ok", Map.of());
        }
    }

    private RunContext newContext(Path tempDir) {
        return new RunContext(UUID.randomUUID().toString(), "retry-rollback-test", tempDir, Map.of());
    }

    @Test
    void retriesWithBackoffThenSucceeds(@TempDir Path tempDir) {
        FlakyStage flaky = new FlakyStage(2); // fails twice, succeeds on 3rd attempt
        StageDef node = new StageDef("flaky", List.of(), "flaky",
                new RetryPolicyDef(4, 0.01, 1.0), false, null, null, null);
        Graph graph = new Graph(List.of(node));
        RunContext context = newContext(tempDir);
        Path auditFile = tempDir.resolve("audit.jsonl");
        AuditLogger auditLogger = new AuditLogger(auditFile);

        Orchestrator orchestrator = new Orchestrator(graph, context, auditLogger, PolicyEngine.empty(),
                new ApprovalGate(ApprovalMode.REPLAY), Map.of("flaky", flaky));
        RunContext finished = orchestrator.run();
        auditLogger.close();

        assertEquals(StageState.SUCCEEDED, finished.getState("flaky"));
        assertEquals(3, flaky.attempts.get());

        List<AuditEvent> events = AuditLogger.readAll(auditFile);
        long retryEvents = events.stream().filter(e -> e.eventType() == AuditEventType.RETRY).count();
        assertEquals(2, retryEvents, "expected exactly 2 RETRY audit events (before attempts 2 and 3)");
    }

    @Test
    void exhaustsRetriesTransitionsToFailedTriggersRollbackAndCascades(@TempDir Path tempDir) {
        AlwaysFailsStage failing = new AlwaysFailsStage();
        SucceedsAfterDelay sibling = new SucceedsAfterDelay(250);

        StageDef root = new StageDef("root", List.of(), "root",
                new RetryPolicyDef(2, 0.01, 1.0), false, null, null, null);
        StageDef downstream = new StageDef("downstream", List.of("root"), "downstream", null, false, null, null, null);
        StageDef siblingNode = new StageDef("sibling", List.of(), "sibling", null, false, null, null, null);
        StageDef gatedOnSibling = new StageDef("gated-later", List.of("sibling"), "gated-later", null, false, null, null, null);

        Graph graph = new Graph(List.of(root, downstream, siblingNode, gatedOnSibling));
        RunContext context = newContext(tempDir);
        Path auditFile = tempDir.resolve("audit.jsonl");
        AuditLogger auditLogger = new AuditLogger(auditFile);

        Map<String, StageExecutor> executors = Map.of(
                "root", failing,
                "downstream", failing, // never invoked - node is cascaded before it can run
                "sibling", sibling,
                "gated-later", failing // never invoked - safe-stop should prevent scheduling
        );

        Orchestrator orchestrator = new Orchestrator(graph, context, auditLogger, PolicyEngine.empty(),
                new ApprovalGate(ApprovalMode.REPLAY), executors);
        RunContext finished = orchestrator.run();
        auditLogger.close();

        assertEquals(StageState.FAILED, finished.getState("root"));
        assertEquals(2, failing.attempts.get(), "should have attempted root exactly maxAttempts times");
        assertTrue(failing.rollbackCalled.get(), "rollback() should have been invoked after retries exhausted");
        assertEquals(StageState.ROLLED_BACK, finished.getState("downstream"), "downstream dependent should cascade to ROLLED_BACK");

        // The independent sibling was already in-flight when root failed - safe-stop lets it finish.
        assertEquals(StageState.SUCCEEDED, finished.getState("sibling"), "in-flight sibling should be allowed to finish");

        // But safe-stop must prevent scheduling NEW work, even once sibling's dependent becomes eligible.
        assertFalse(finished.getState("gated-later") == StageState.SUCCEEDED,
                "safe-stop should have prevented gated-later from ever being scheduled");
    }
}

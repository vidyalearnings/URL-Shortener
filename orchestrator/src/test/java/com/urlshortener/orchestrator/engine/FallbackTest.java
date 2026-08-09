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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the fallback mechanism is real and distinct from retry/rollback: when a node's primary
 * executor exhausts its retry budget, a configured fallback executor is attempted once, and if
 * IT succeeds the node ends SUCCEEDED (not FAILED) with the outcome recorded in the audit log
 * and node outputs - only if the fallback also fails does the node fall through to the
 * existing FAILED/rollback/safe-stop path.
 */
class FallbackTest {

    static class AlwaysFailsStage implements StageExecutor {
        final AtomicInteger attempts = new AtomicInteger(0);

        @Override
        public StageResult execute(RunContext context, StageDef def) {
            attempts.incrementAndGet();
            return StageResult.failure("primary always fails", Map.of());
        }
    }

    static class AlwaysSucceedsStage implements StageExecutor {
        final AtomicInteger attempts = new AtomicInteger(0);

        @Override
        public StageResult execute(RunContext context, StageDef def) {
            attempts.incrementAndGet();
            return StageResult.success("degraded fallback path succeeded", Map.of("degraded", true));
        }
    }

    private RunContext newContext(Path tempDir) {
        return new RunContext(UUID.randomUUID().toString(), "fallback-test", tempDir, Map.of());
    }

    @Test
    void primaryExhaustsRetriesThenFallbackSucceedsNodeEndsSucceeded(@TempDir Path tempDir) {
        AlwaysFailsStage primary = new AlwaysFailsStage();
        AlwaysSucceedsStage fallback = new AlwaysSucceedsStage();

        StageDef node = new StageDef("doc", List.of(), "primary-doc",
                new RetryPolicyDef(2, 0.01, 1.0), false, null, null, null, "fallback-doc");
        Graph graph = new Graph(List.of(node));
        RunContext context = newContext(tempDir);
        Path auditFile = tempDir.resolve("audit.jsonl");
        AuditLogger auditLogger = new AuditLogger(auditFile);

        Orchestrator orchestrator = new Orchestrator(graph, context, auditLogger, PolicyEngine.empty(),
                new ApprovalGate(ApprovalMode.REPLAY), Map.of("primary-doc", primary, "fallback-doc", fallback));
        RunContext finished = orchestrator.run();
        auditLogger.close();

        assertEquals(StageState.SUCCEEDED, finished.getState("doc"),
                "node should end SUCCEEDED via the fallback path, not FAILED");
        assertEquals(2, primary.attempts.get(), "primary should have been attempted exactly maxAttempts times");
        assertEquals(1, fallback.attempts.get(), "fallback should be attempted exactly once (no retries of its own)");

        Map<String, Object> outputs = finished.getOutputs().get("doc");
        assertEquals(true, outputs.get("viaFallback"));
        assertTrue(String.valueOf(outputs.get("primaryFailureReason")).contains("primary always fails"));

        List<AuditEvent> events = AuditLogger.readAll(auditFile);
        long fallbackEvents = events.stream().filter(e -> e.eventType() == AuditEventType.FALLBACK).count();
        assertEquals(1, fallbackEvents, "expected exactly one FALLBACK audit event");
        assertTrue(events.stream().anyMatch(e -> e.eventType() == AuditEventType.FALLBACK
                && Boolean.TRUE.equals(e.data().get("fallbackSucceeded"))));
    }

    @Test
    void primaryAndFallbackBothFailNodeEndsFailedAndRollsBack(@TempDir Path tempDir) {
        AlwaysFailsStage primary = new AlwaysFailsStage();
        AlwaysFailsStage fallback = new AlwaysFailsStage();

        StageDef node = new StageDef("doc", List.of(), "primary-doc",
                new RetryPolicyDef(1, 0.01, 1.0), false, null, null, null, "fallback-doc");
        Graph graph = new Graph(List.of(node));
        RunContext context = newContext(tempDir);
        Path auditFile = tempDir.resolve("audit.jsonl");
        AuditLogger auditLogger = new AuditLogger(auditFile);

        Orchestrator orchestrator = new Orchestrator(graph, context, auditLogger, PolicyEngine.empty(),
                new ApprovalGate(ApprovalMode.REPLAY), Map.of("primary-doc", primary, "fallback-doc", fallback));
        RunContext finished = orchestrator.run();
        auditLogger.close();

        assertEquals(StageState.FAILED, finished.getState("doc"));
        assertEquals(1, fallback.attempts.get(), "fallback should still have been attempted once");

        List<AuditEvent> events = AuditLogger.readAll(auditFile);
        assertTrue(events.stream().anyMatch(e -> e.eventType() == AuditEventType.FALLBACK
                && Boolean.FALSE.equals(e.data().get("fallbackSucceeded"))));
    }
}

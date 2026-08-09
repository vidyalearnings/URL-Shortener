package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.approvals.ApprovalGate;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a small synthetic graph through the real Orchestrator and asserts (a) correct dependency
 * sequencing and (b) that two independent parallel branches genuinely overlap in wall-clock time
 * rather than running one-after-another.
 */
class StateMachineTest {

    /** A trivial in-test StageExecutor that sleeps a fixed duration and records start/end times. */
    static class SleepyStage implements StageExecutor {
        final long sleepMs;
        final Map<String, long[]> timings; // node -> [startMs, endMs]
        final String name;

        SleepyStage(String name, long sleepMs, Map<String, long[]> timings) {
            this.name = name;
            this.sleepMs = sleepMs;
            this.timings = timings;
        }

        @Override
        public StageResult execute(RunContext context, StageDef def) throws Exception {
            long start = System.currentTimeMillis();
            Thread.sleep(sleepMs);
            long end = System.currentTimeMillis();
            timings.put(name, new long[]{start, end});
            return StageResult.success(name + " done", Map.of());
        }
    }

    @Test
    void sequencesDependenciesAndRunsIndependentBranchesInParallel(@TempDir Path tempDir) {
        Map<String, long[]> timings = new ConcurrentHashMap<>();
        long branchSleepMs = 300;

        StageDef start = new StageDef("start", List.of(), "start", null, false, null, null, null);
        StageDef branchA = new StageDef("branch-a", List.of("start"), "branch-a", null, false, null, null, null);
        StageDef branchB = new StageDef("branch-b", List.of("start"), "branch-b", null, false, null, null, null);
        StageDef join = new StageDef("join", List.of("branch-a", "branch-b"), "join", null, false, null, null, null);

        Graph graph = new Graph(List.of(start, branchA, branchB, join));
        RunContext context = new RunContext(UUID.randomUUID().toString(), "state-machine-test", tempDir, Map.of());
        AuditLogger auditLogger = new AuditLogger(tempDir.resolve("audit.jsonl"));

        Map<String, StageExecutor> executors = Map.of(
                "start", new SleepyStage("start", 50, timings),
                "branch-a", new SleepyStage("branch-a", branchSleepMs, timings),
                "branch-b", new SleepyStage("branch-b", branchSleepMs, timings),
                "join", new SleepyStage("join", 50, timings)
        );

        ApprovalGate approvalGate = new ApprovalGate(com.urlshortener.orchestrator.approvals.ApprovalMode.REPLAY);
        Orchestrator orchestrator = new Orchestrator(graph, context, auditLogger, PolicyEngine.empty(), approvalGate, executors);
        RunContext finished = orchestrator.run();

        // Correct sequencing: every node succeeded, and start finished before both branches started.
        for (String name : List.of("start", "branch-a", "branch-b", "join")) {
            assertEquals(StageState.SUCCEEDED, finished.getState(name), name + " should have succeeded");
        }
        assertTrue(timings.get("start")[1] <= timings.get("branch-a")[0]);
        assertTrue(timings.get("start")[1] <= timings.get("branch-b")[0]);
        assertTrue(timings.get("branch-a")[1] <= timings.get("join")[0]);
        assertTrue(timings.get("branch-b")[1] <= timings.get("join")[0]);

        // Genuine parallelism: branch-a and branch-b overlap in wall-clock time.
        long aStart = timings.get("branch-a")[0];
        long aEnd = timings.get("branch-a")[1];
        long bStart = timings.get("branch-b")[0];
        long bEnd = timings.get("branch-b")[1];
        boolean overlap = aStart < bEnd && bStart < aEnd;
        assertTrue(overlap, "expected branch-a and branch-b to overlap in wall-clock time");

        auditLogger.close();
    }
}

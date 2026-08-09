package com.urlshortener.orchestrator.metrics;

import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.engine.StageState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsCalculatorTest {

    private AuditEvent transition(String runId, Instant t, String node, StageState from, StageState to, Long durationMs) {
        return new AuditEvent(t, runId, AuditEventType.STAGE_TRANSITION, node, from, to, "system", "x", Map.of(), durationMs);
    }

    private AuditEvent retry(String runId, Instant t, String node) {
        return new AuditEvent(t, runId, AuditEventType.RETRY, node, StageState.RUNNING, StageState.RUNNING, "system", "retry", Map.of(), null);
    }

    private AuditEvent rollback(String runId, Instant t, String node) {
        return new AuditEvent(t, runId, AuditEventType.ROLLBACK, node, StageState.FAILED, StageState.FAILED, "system", "rollback", Map.of(), null);
    }

    @Test
    void computesKnownMetricsFromSyntheticFixture() {
        String runId = "run-1";
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        List<AuditEvent> events = new ArrayList<>();

        // Node "a": succeeds cleanly on the first attempt, duration 100ms.
        events.add(transition(runId, t0, "a", StageState.PENDING, StageState.RUNNING, null));
        events.add(transition(runId, t0.plusMillis(100), "a", StageState.RUNNING, StageState.SUCCEEDED, 100L));

        // Node "b": one retry, then succeeds, duration 300ms.
        events.add(transition(runId, t0.plusMillis(200), "b", StageState.PENDING, StageState.RUNNING, null));
        events.add(retry(runId, t0.plusMillis(250), "b"));
        events.add(transition(runId, t0.plusMillis(500), "b", StageState.RUNNING, StageState.SUCCEEDED, 300L));

        // Node "c": fails, rolls back, then (in a re-run) succeeds 1000ms after the failure -> contributes to MTTR.
        events.add(transition(runId, t0.plusMillis(600), "c", StageState.PENDING, StageState.RUNNING, null));
        events.add(transition(runId, t0.plusMillis(700), "c", StageState.RUNNING, StageState.FAILED, 100L));
        events.add(rollback(runId, t0.plusMillis(710), "c"));
        events.add(transition(runId, t0.plusMillis(1700), "c", StageState.PENDING, StageState.SUCCEEDED, 200L));

        // Node "d": never recovers (stays failed) - should not contribute to MTTR, drags down success rate.
        events.add(transition(runId, t0.plusMillis(800), "d", StageState.PENDING, StageState.RUNNING, null));
        events.add(transition(runId, t0.plusMillis(900), "d", StageState.RUNNING, StageState.FAILED, 100L));

        Instant lastTimestamp = t0.plusMillis(1700);

        Metrics metrics = MetricsCalculator.calculate(events);

        assertEquals(4, metrics.totalNodes());
        assertEquals(3, metrics.succeededNodes()); // a, b, c (d never succeeded)
        assertEquals(3.0 / 4.0, metrics.successRate(), 1e-9);
        assertEquals(1, metrics.retryEvents());
        assertEquals(1.0 / 4.0, metrics.retryFrequency(), 1e-9);
        assertEquals(1, metrics.rollbackEvents());
        assertEquals(1.0 / 4.0, metrics.rollbackFrequency(), 1e-9);
        // Only "c" failed-then-recovered: FAILED at t0+700, SUCCEEDED at t0+1700 -> 1000ms.
        assertEquals(1000.0, metrics.mttrMs(), 1e-6);
        assertEquals(java.time.Duration.between(t0, lastTimestamp).toMillis(), metrics.endToEndLatencyMs());
        assertEquals(100L, metrics.perStageDurationMs().get("a"));
        assertEquals(300L, metrics.perStageDurationMs().get("b"));
    }

    @Test
    void emptyEventsProduceZeroedMetrics() {
        Metrics metrics = MetricsCalculator.calculate(List.of());
        assertEquals(0, metrics.totalNodes());
        assertEquals(0.0, metrics.successRate());
        assertEquals(0.0, metrics.mttrMs());
        assertEquals(0, metrics.endToEndLatencyMs());
    }
}

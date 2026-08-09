package com.urlshortener.orchestrator.metrics;

import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLogger;
import com.urlshortener.orchestrator.engine.StageState;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Computes reliability metrics purely from a run's {@code audit.jsonl} -- the single source of
 * truth. Never touches live run state, so it works equally on a completed run's file or on a
 * synthetic fixture (see MetricsCalculatorTest).
 */
public class MetricsCalculator {

    public static Metrics calculate(Path auditFile) {
        return calculate(AuditLogger.readAll(auditFile));
    }

    public static Metrics calculate(List<AuditEvent> events) {
        if (events.isEmpty()) {
            return new Metrics(0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0.0, 0, Map.of());
        }

        TreeSet<String> allNodes = new TreeSet<>();
        Map<String, StageState> lastStateByNode = new LinkedHashMap<>();
        Map<String, Long> perStageDuration = new LinkedHashMap<>();
        long retryEvents = 0;
        long rollbackEvents = 0;
        long fallbackEvents = 0;

        // For MTTR: per node, timestamp of the most recent unmatched FAILED transition.
        Map<String, Instant> pendingFailure = new LinkedHashMap<>();
        List<Long> recoveryDurationsMs = new ArrayList<>();

        Instant first = null;
        Instant last = null;

        List<AuditEvent> sorted = events.stream()
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();

        for (AuditEvent event : sorted) {
            if (first == null || event.timestamp().isBefore(first)) {
                first = event.timestamp();
            }
            if (last == null || event.timestamp().isAfter(last)) {
                last = event.timestamp();
            }

            if (event.eventType() == AuditEventType.RETRY) {
                retryEvents++;
            } else if (event.eventType() == AuditEventType.ROLLBACK) {
                rollbackEvents++;
            } else if (event.eventType() == AuditEventType.FALLBACK) {
                fallbackEvents++;
            }

            if (event.eventType() == AuditEventType.STAGE_TRANSITION && event.node() != null) {
                allNodes.add(event.node());
                lastStateByNode.put(event.node(), event.toState());
                if (event.durationMs() != null) {
                    perStageDuration.put(event.node(), event.durationMs());
                }
                if (event.toState() == StageState.FAILED) {
                    pendingFailure.put(event.node(), event.timestamp());
                } else if (event.toState() == StageState.SUCCEEDED) {
                    Instant failedAt = pendingFailure.remove(event.node());
                    if (failedAt != null) {
                        recoveryDurationsMs.add(Duration.between(failedAt, event.timestamp()).toMillis());
                    }
                }
            }
        }

        int totalNodes = allNodes.size();
        long succeededNodes = lastStateByNode.values().stream().filter(s -> s == StageState.SUCCEEDED).count();

        double successRate = totalNodes == 0 ? 0.0 : (double) succeededNodes / totalNodes;
        double retryFrequency = totalNodes == 0 ? 0.0 : (double) retryEvents / totalNodes;
        double rollbackFrequency = totalNodes == 0 ? 0.0 : (double) rollbackEvents / totalNodes;
        double fallbackFrequency = totalNodes == 0 ? 0.0 : (double) fallbackEvents / totalNodes;
        double mttrMs = recoveryDurationsMs.isEmpty() ? 0.0
                : recoveryDurationsMs.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long endToEndLatencyMs = (first == null || last == null) ? 0 : Duration.between(first, last).toMillis();

        return new Metrics(totalNodes, (int) succeededNodes, successRate, retryEvents, retryFrequency,
                rollbackEvents, rollbackFrequency, fallbackEvents, fallbackFrequency, mttrMs, endToEndLatencyMs, perStageDuration);
    }
}

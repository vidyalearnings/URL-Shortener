package com.urlshortener.orchestrator.metrics;

import java.util.Map;

/** Reliability metrics computed from a run's audit.jsonl. */
public record Metrics(
        int totalNodes,
        int succeededNodes,
        double successRate,
        long retryEvents,
        double retryFrequency,
        long rollbackEvents,
        double rollbackFrequency,
        double mttrMs,
        long endToEndLatencyMs,
        Map<String, Long> perStageDurationMs
) {

    public String toTable() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-28s %s%n", "Total nodes:", totalNodes));
        sb.append(String.format("%-28s %d%n", "Succeeded nodes:", succeededNodes));
        sb.append(String.format("%-28s %.2f%%%n", "Success rate:", successRate * 100));
        sb.append(String.format("%-28s %d%n", "Retry events:", retryEvents));
        sb.append(String.format("%-28s %.2f%n", "Retry frequency (per node):", retryFrequency));
        sb.append(String.format("%-28s %d%n", "Rollback events:", rollbackEvents));
        sb.append(String.format("%-28s %.2f%n", "Rollback frequency (per node):", rollbackFrequency));
        sb.append(String.format("%-28s %.0f ms%n", "MTTR:", mttrMs));
        sb.append(String.format("%-28s %d ms%n", "End-to-end latency:", endToEndLatencyMs));
        sb.append("Per-stage duration (ms):\n");
        for (Map.Entry<String, Long> e : perStageDurationMs.entrySet()) {
            sb.append(String.format("  %-26s %d ms%n", e.getKey() + ":", e.getValue()));
        }
        return sb.toString();
    }
}

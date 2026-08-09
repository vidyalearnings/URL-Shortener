package com.urlshortener.orchestrator.audit;

import com.urlshortener.orchestrator.engine.StageState;

import java.time.Instant;
import java.util.Map;

/**
 * A single immutable audit-log entry. One JSON object per line in {@code audit.jsonl}, the
 * single source of truth from which both metrics and reports are derived. Never mutated once
 * written.
 */
public record AuditEvent(
        Instant timestamp,
        String runId,
        AuditEventType eventType,
        String node,
        StageState fromState,
        StageState toState,
        String actor,
        String reason,
        Map<String, Object> data,
        Long durationMs
) {
}

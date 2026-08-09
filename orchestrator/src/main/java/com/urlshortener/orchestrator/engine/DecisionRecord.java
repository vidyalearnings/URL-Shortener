package com.urlshortener.orchestrator.engine;

import java.time.Instant;
import java.util.Map;

/**
 * A recorded decision made during the run (e.g. "requirements flagged ambiguous, inserting
 * clarify-requirements node"). Kept in {@link RunContext} for cross-stage lineage and mirrored
 * into the audit log as a {@code DECISION} event.
 */
public record DecisionRecord(Instant timestamp, String node, String summary, String rationale, Map<String, Object> data) {
}

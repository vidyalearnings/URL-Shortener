package com.urlshortener.orchestrator.reporting;

import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLogger;
import com.urlshortener.orchestrator.metrics.Metrics;
import com.urlshortener.orchestrator.metrics.MetricsCalculator;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads audit.jsonl and renders a human-readable Markdown run report: stage-transition timeline,
 * decisions with rationale, approvals, retries/rollbacks, and the final metrics summary.
 */
public class ReportGenerator {

    public static String generate(Path auditFile, String runId) {
        List<AuditEvent> events = AuditLogger.readAll(auditFile);
        StringBuilder md = new StringBuilder();

        md.append("# Orchestration Run Report\n\n");
        md.append("**Run ID:** ").append(runId).append("\n\n");

        md.append("## Timeline\n\n");
        md.append("| Timestamp | Node | Event | From -> To | Actor | Reason |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (AuditEvent e : events) {
            if (e.eventType() == AuditEventType.STAGE_TRANSITION) {
                md.append("| ").append(e.timestamp())
                        .append(" | ").append(nullToDash(e.node()))
                        .append(" | ").append(e.eventType())
                        .append(" | ").append(nullToDash(e.fromState())).append(" -> ").append(nullToDash(e.toState()))
                        .append(" | ").append(nullToDash(e.actor()))
                        .append(" | ").append(nullToDash(e.reason()))
                        .append(" |\n");
            }
        }

        md.append("\n## Decisions\n\n");
        boolean anyDecision = false;
        for (AuditEvent e : events) {
            if (e.eventType() == AuditEventType.DECISION || e.eventType() == AuditEventType.REPLAN
                    || e.eventType() == AuditEventType.NODE_INSERTED || e.eventType() == AuditEventType.NODE_STALE) {
                anyDecision = true;
                md.append("- **[").append(e.eventType()).append("]** ").append(nullToDash(e.node()))
                        .append(" @ ").append(e.timestamp()).append(": ").append(nullToDash(e.reason())).append("\n");
            }
        }
        if (!anyDecision) {
            md.append("_None recorded._\n");
        }

        md.append("\n## Approvals\n\n");
        boolean anyApproval = false;
        for (AuditEvent e : events) {
            if (e.eventType() == AuditEventType.APPROVAL_REQUESTED || e.eventType() == AuditEventType.APPROVAL_GRANTED
                    || e.eventType() == AuditEventType.APPROVAL_DENIED) {
                anyApproval = true;
                md.append("- **[").append(e.eventType()).append("]** ").append(nullToDash(e.node()))
                        .append(" by ").append(nullToDash(e.actor()))
                        .append(" @ ").append(e.timestamp())
                        .append(": ").append(nullToDash(e.reason())).append("\n");
            }
        }
        if (!anyApproval) {
            md.append("_None recorded._\n");
        }

        md.append("\n## Retries / Rollbacks\n\n");
        boolean anyRetryRollback = false;
        for (AuditEvent e : events) {
            if (e.eventType() == AuditEventType.RETRY || e.eventType() == AuditEventType.ROLLBACK
                    || e.eventType() == AuditEventType.POLICY_VIOLATION) {
                anyRetryRollback = true;
                md.append("- **[").append(e.eventType()).append("]** ").append(nullToDash(e.node()))
                        .append(" @ ").append(e.timestamp()).append(": ").append(nullToDash(e.reason())).append("\n");
            }
        }
        if (!anyRetryRollback) {
            md.append("_None recorded._\n");
        }

        md.append("\n## Metrics Summary\n\n```\n");
        Metrics metrics = MetricsCalculator.calculate(events);
        md.append(metrics.toTable());
        md.append("```\n");

        return md.toString();
    }

    private static String nullToDash(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }
}

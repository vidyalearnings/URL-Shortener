package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLogger;
import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import com.urlshortener.orchestrator.engine.StageState;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates release readiness: testing SUCCEEDED, documentation SUCCEEDED, no unresolved policy
 * violation for this node (already enforced by the Orchestrator's policy pre-check + the
 * {@code release-approval} entry-approval gate before this executor even runs). On success, logs
 * a final "released" DECISION-type audit event with a synthetic version tag.
 *
 * IMPORTANT: this deliberately does NOT run any real `git tag` / `git push` / deploy command.
 * Automating an actual release/deploy from a prototype orchestrator is out of scope for this
 * assignment and unsafe to run unattended against a real remote -- so the "release" here is
 * log-only, a clearly-marked boundary between orchestration and real-world side effects.
 */
public class ReleaseReadinessStage implements StageExecutor {

    private static final DateTimeFormatter VERSION_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Override
    public StageResult execute(RunContext context, StageDef def) {
        StageState testingState = context.getState("testing");
        StageState documentationState = context.getState("documentation");

        boolean testingOk = testingState == StageState.SUCCEEDED;
        boolean documentationOk = documentationState == StageState.SUCCEEDED;
        boolean approvalOk = context.isApproved(def.getApprovalDecisionId());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("testingState", String.valueOf(testingState));
        outputs.put("documentationState", String.valueOf(documentationState));
        outputs.put("releaseApproved", approvalOk);

        if (!testingOk || !documentationOk || !approvalOk) {
            String reason = "release readiness failed: testingOk=" + testingOk + " documentationOk=" + documentationOk
                    + " approvalOk=" + approvalOk;
            return StageResult.failure(reason, outputs);
        }

        String version = "v" + Instant.now().atZone(java.time.ZoneOffset.UTC).format(VERSION_TS)
                + "-" + context.getRunId().substring(0, Math.min(8, context.getRunId().length()));
        outputs.put("versionTag", version);

        AuditLogger logger = context.getAuditLogger();
        if (logger != null) {
            // Log-only "released" marker - see class Javadoc for why no real git/deploy action happens here.
            logger.log(new AuditEvent(Instant.now(), context.getRunId(), AuditEventType.DECISION, def.getName(),
                    null, null, "system", "released " + version + " (log-only, no real deploy/tag/push executed)",
                    Map.of("versionTag", version), null));
        }

        return StageResult.success("Release readiness satisfied; tagged " + version + " (log-only).", outputs);
    }
}

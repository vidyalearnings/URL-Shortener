package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

import java.util.Map;

/**
 * Rule 2: blocks the final "released" transition unless an {@code APPROVAL_GRANTED} decision was
 * actually recorded (in the audit log, mirrored live in {@link RunContext#getApprovals()}) for
 * the release approval gate.
 */
public class RequireHumanApprovalRule implements PolicyRule {

    public static final String ID = "require-human-approval";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PolicyResult evaluate(RunContext context, StageDef node, Map<String, Object> params) {
        String decisionId = params == null ? null : (String) params.get("decisionId");
        if (decisionId == null) {
            decisionId = "release-approval";
        }
        if (context.isApproved(decisionId)) {
            return PolicyResult.allow();
        }
        return PolicyResult.block("No recorded APPROVAL_GRANTED decision for '" + decisionId + "'");
    }
}

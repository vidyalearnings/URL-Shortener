package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import com.urlshortener.orchestrator.engine.StageState;

import java.util.Map;

/**
 * Rule 1: blocks a node (by default {@code release-readiness}) from proceeding unless a
 * required upstream stage (by default {@code testing}) is SUCCEEDED.
 */
public class RequireStageSucceededRule implements PolicyRule {

    public static final String ID = "require-stage-succeeded";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PolicyResult evaluate(RunContext context, StageDef node, Map<String, Object> params) {
        String requiredStage = params == null ? null : (String) params.get("requiredStage");
        if (requiredStage == null) {
            requiredStage = "testing";
        }
        StageState state = context.getState(requiredStage);
        if (state == StageState.SUCCEEDED) {
            return PolicyResult.allow();
        }
        return PolicyResult.block("Required stage '" + requiredStage + "' has not SUCCEEDED (state=" + state + ")");
    }
}

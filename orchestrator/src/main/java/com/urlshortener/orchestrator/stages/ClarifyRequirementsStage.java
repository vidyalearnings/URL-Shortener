package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

import java.util.Map;

/**
 * Executor for the synthetic {@code clarify-requirements} node inserted at runtime by
 * {@link RequirementsStage}. By the time this executes, its entry approval gate has already
 * required a human to sign off on the flagged ambiguities (see graph wiring in RequirementsStage)
 * -- so this stage's only job is to record that clarification happened and hand the flagged
 * ambiguities through as its own output for downstream lineage.
 */
public class ClarifyRequirementsStage implements StageExecutor {

    @Override
    public StageResult execute(RunContext context, StageDef def) {
        Object ambiguities = def.getParams().get("ambiguities");
        return StageResult.success(
                "Ambiguities acknowledged and clarified following human approval.",
                Map.of("clarifiedAmbiguities", ambiguities == null ? Map.of() : ambiguities)
        );
    }
}

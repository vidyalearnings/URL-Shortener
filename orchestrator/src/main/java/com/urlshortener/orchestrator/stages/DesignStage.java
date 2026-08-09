package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a structured architecture decision record (ADR). Deliberately simple/hardcoded
 * rationale per the assignment scope -- the point of this stage in the demo is to exercise the
 * exit-approval gate (architecture review), not to do generative design.
 */
public class DesignStage implements StageExecutor {

    @Override
    public StageResult execute(RunContext context, StageDef def) {
        Map<String, Object> adr = new LinkedHashMap<>();
        adr.put("title", "URL Shortener service architecture");
        adr.put("decisions", List.of(
                Map.of(
                        "decision", "Use Spring JdbcTemplate over JPA/Hibernate for persistence",
                        "rationale", "The service's data access patterns are simple, well-known CRUD/lookup "
                                + "queries around a single short-code table; JdbcTemplate keeps SQL explicit and "
                                + "avoids ORM session/lazy-loading overhead that isn't needed at this scale."
                ),
                Map.of(
                        "decision", "Implement SDLC orchestration as a plain-Java engine (this module), not Spring/Airflow/Temporal",
                        "rationale", "The orchestrator's job is to make the workflow graph, retries, approvals and "
                                + "audit trail directly inspectable by a reviewer; a heavyweight workflow platform "
                                + "would hide exactly the control flow this assignment asks to see."
                )
        ));
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("adr", adr);
        return StageResult.success("Architecture decision record produced.", outputs);
    }
}

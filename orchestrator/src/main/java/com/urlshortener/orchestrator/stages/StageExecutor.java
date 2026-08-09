package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

/** A pluggable unit of real work for one stage-graph node. */
public interface StageExecutor {

    StageResult execute(RunContext context, StageDef def) throws Exception;

    /**
     * Best-effort compensating action invoked by the orchestrator when this stage's retries are
     * exhausted. Default no-op: most stages here are read/verify style (compile, test, doc
     * checks) with nothing stateful to undo.
     */
    default void rollback(RunContext context, StageDef def) {
        // no-op by default
    }
}

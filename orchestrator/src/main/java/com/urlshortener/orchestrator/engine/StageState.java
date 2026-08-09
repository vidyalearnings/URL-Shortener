package com.urlshortener.orchestrator.engine;

/**
 * Lifecycle states of a single node in the stage graph.
 */
public enum StageState {
    PENDING,
    READY,
    RUNNING,
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    ROLLED_BACK,
    STALE,
    SKIPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == ROLLED_BACK || this == SKIPPED;
    }

    /** States that satisfy a downstream node's dependency requirement. */
    public boolean satisfiesDependency() {
        return this == SUCCEEDED || this == SKIPPED;
    }

    /** States that mean "this node will never succeed" and must cascade to dependents. */
    public boolean isFailureLike() {
        return this == FAILED || this == ROLLED_BACK;
    }
}

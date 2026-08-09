package com.urlshortener.orchestrator.approvals;

public enum ApprovalMode {
    /** Prompts on stdin via Scanner and blocks for real human input. */
    INTERACTIVE,
    /** Looks up a pre-supplied decision by decisionId, for reproducible non-interactive runs. */
    REPLAY
}

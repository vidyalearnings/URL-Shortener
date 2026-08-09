package com.urlshortener.orchestrator.policy;

/**
 * Maps each concrete {@link PolicyRule} to the governance category the assignment brief names
 * explicitly: "policy guardrails for security, compliance, and change control."
 */
public enum PolicyCategory {
    SECURITY,
    COMPLIANCE,
    CHANGE_CONTROL
}

package com.urlshortener.orchestrator.audit;

public enum AuditEventType {
    STAGE_TRANSITION,
    RETRY,
    FALLBACK,
    ROLLBACK,
    POLICY_VIOLATION,
    APPROVAL_REQUESTED,
    APPROVAL_GRANTED,
    APPROVAL_DENIED,
    DECISION,
    REPLAN,
    NODE_INSERTED,
    NODE_STALE
}

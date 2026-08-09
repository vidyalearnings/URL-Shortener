package com.urlshortener.orchestrator.approvals;

public record ApprovalDecision(String decisionId, boolean approved, String approver, String comment) {
}

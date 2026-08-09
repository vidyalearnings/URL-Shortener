package com.urlshortener.orchestrator.policy;

/** Outcome of a single policy rule evaluation. */
public record PolicyResult(boolean allowed, String reason) {

    public static PolicyResult allow() {
        return new PolicyResult(true, null);
    }

    public static PolicyResult block(String reason) {
        return new PolicyResult(false, reason);
    }
}

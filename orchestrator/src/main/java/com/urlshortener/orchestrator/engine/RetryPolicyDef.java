package com.urlshortener.orchestrator.engine;

/**
 * Retry/backoff configuration for a single stage node.
 * Backoff between attempt {@code n} and {@code n+1} is:
 * {@code backoffBaseSeconds * backoffMultiplier ^ (n-1)} seconds.
 */
public final class RetryPolicyDef {

    public static final int DEFAULT_MAX_ATTEMPTS = 1;
    public static final double DEFAULT_BACKOFF_BASE_SECONDS = 1.0;
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private final int maxAttempts;
    private final double backoffBaseSeconds;
    private final double backoffMultiplier;

    public RetryPolicyDef(int maxAttempts, double backoffBaseSeconds, double backoffMultiplier) {
        this.maxAttempts = maxAttempts < 1 ? 1 : maxAttempts;
        this.backoffBaseSeconds = backoffBaseSeconds;
        this.backoffMultiplier = backoffMultiplier;
    }

    public static RetryPolicyDef defaults() {
        return new RetryPolicyDef(DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_BASE_SECONDS, DEFAULT_BACKOFF_MULTIPLIER);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public double backoffSecondsForAttempt(int attemptNumber) {
        // attemptNumber is the attempt that just failed (1-based); delay before the next attempt.
        return backoffBaseSeconds * Math.pow(backoffMultiplier, Math.max(0, attemptNumber - 1));
    }
}

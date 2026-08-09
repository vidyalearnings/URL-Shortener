package com.urlshortener.service;

import com.urlshortener.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory per-IP token bucket rate limiter.
 *
 * <p><b>Known limitation (accepted for this prototype, not a bug):</b> state
 * is held in a local {@link ConcurrentHashMap}, so this only rate-limits
 * correctly for a single instance of the service. Running multiple
 * instances behind a load balancer would let each instance grant its own
 * independent budget per IP - a correct multi-instance implementation would
 * need a shared store (e.g. Redis) for the bucket state.
 */
@Service
public class RateLimiterService {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double refillPerSecond;

    public RateLimiterService(AppProperties appProperties) {
        this.capacity = appProperties.rateLimit().capacity();
        this.refillPerSecond = appProperties.rateLimit().refillPerSecond();
    }

    public boolean tryConsume(String key) {
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillPerSecond));
        return bucket.tryConsume();
    }

    private static final class TokenBucket {
        private final int capacity;
        private final double refillPerSecond;
        private final ReentrantLock lock = new ReentrantLock();
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        boolean tryConsume() {
            lock.lock();
            try {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefillNanos = now;
        }
    }
}

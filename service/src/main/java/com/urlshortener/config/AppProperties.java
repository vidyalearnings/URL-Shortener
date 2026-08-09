package com.urlshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        ShortCode shortCode,
        RateLimit rateLimit
) {
    public record ShortCode(int length, int maxGenerationAttempts) {
    }

    public record RateLimit(int capacity, double refillPerSecond) {
    }
}

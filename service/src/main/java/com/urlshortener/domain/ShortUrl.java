package com.urlshortener.domain;

import java.time.Instant;

/**
 * Domain model for a shortened URL record.
 */
public record ShortUrl(
        Long id,
        String shortCode,
        String originalUrl,
        boolean customAlias,
        UrlStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant lastAccessedAt
) {

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}

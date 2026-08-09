package com.urlshortener.dto;

import java.time.Instant;

/**
 * Request body for {@code PUT /api/urls/{code}}. Both fields optional - only
 * non-null fields are applied.
 */
public record UpdateUrlRequest(
        String originalUrl,
        Instant expiresAt
) {
}

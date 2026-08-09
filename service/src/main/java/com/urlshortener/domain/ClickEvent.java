package com.urlshortener.domain;

import java.time.Instant;

/**
 * Domain model for a single click/redirect event recorded against a short code.
 */
public record ClickEvent(
        Long id,
        String shortCode,
        Instant clickedAt,
        String referrer,
        String userAgent,
        String ipHash
) {
}

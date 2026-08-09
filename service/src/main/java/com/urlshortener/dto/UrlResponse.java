package com.urlshortener.dto;

import com.urlshortener.domain.ShortUrl;
import com.urlshortener.domain.UrlStatus;

import java.time.Instant;

public record UrlResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        UrlStatus status,
        boolean isCustomAlias,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant lastAccessedAt
) {
    public static UrlResponse from(ShortUrl url, String baseUrl) {
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return new UrlResponse(
                url.shortCode(),
                normalizedBase + "/" + url.shortCode(),
                url.originalUrl(),
                url.status(),
                url.customAlias(),
                url.createdAt(),
                url.updatedAt(),
                url.expiresAt(),
                url.lastAccessedAt()
        );
    }
}

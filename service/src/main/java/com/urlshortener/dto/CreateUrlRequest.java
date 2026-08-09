package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Request body for {@code POST /api/urls}.
 */
public record CreateUrlRequest(
        @NotBlank(message = "originalUrl must not be blank")
        String originalUrl,
        String customAlias,
        Instant expiresAt
) {
}

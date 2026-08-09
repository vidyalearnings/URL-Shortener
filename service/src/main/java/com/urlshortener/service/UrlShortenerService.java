package com.urlshortener.service;

import com.urlshortener.config.AppProperties;
import com.urlshortener.domain.ShortUrl;
import com.urlshortener.domain.UrlStatus;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.UpdateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.exception.AliasAlreadyExistsException;
import com.urlshortener.exception.IdempotencyConflictException;
import com.urlshortener.exception.ShortCodeGenerationException;
import com.urlshortener.exception.ShortCodeNotFoundException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.repository.IdempotencyKeyRepository;
import com.urlshortener.repository.SqliteConstraintViolations;
import com.urlshortener.repository.UrlRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class UrlShortenerService {

    /**
     * Result of a create operation. {@code created=false} means an existing
     * idempotency-key replay was returned instead of creating a new row.
     */
    public record CreateResult(UrlResponse response, boolean created) {
    }

    private final UrlRepository urlRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final UrlValidationService validationService;
    private final AppProperties appProperties;

    public UrlShortenerService(UrlRepository urlRepository,
                                IdempotencyKeyRepository idempotencyKeyRepository,
                                ShortCodeGenerator shortCodeGenerator,
                                UrlValidationService validationService,
                                AppProperties appProperties) {
        this.urlRepository = urlRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.validationService = validationService;
        this.appProperties = appProperties;
    }

    public CreateResult createUrl(CreateUrlRequest request, String idempotencyKey) {
        validationService.validateOriginalUrl(request.originalUrl());
        boolean hasCustomAlias = request.customAlias() != null && !request.customAlias().isBlank();
        if (hasCustomAlias) {
            validationService.validateCustomAlias(request.customAlias());
        }

        String requestHash = hashRequest(request);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyKeyRepository.IdempotencyRecord> existing = idempotencyKeyRepository.find(idempotencyKey);
            if (existing.isPresent()) {
                IdempotencyKeyRepository.IdempotencyRecord record = existing.get();
                if (!record.requestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(idempotencyKey);
                }
                ShortUrl url = urlRepository.findByShortCode(record.shortCode())
                        .orElseThrow(() -> new ShortCodeNotFoundException(record.shortCode()));
                return new CreateResult(UrlResponse.from(url, appProperties.baseUrl()), false);
            }
        }

        ShortUrl created = hasCustomAlias
                ? createWithCustomAlias(request)
                : createWithGeneratedCode(request);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyKeyRepository.insert(idempotencyKey, created.shortCode(), requestHash);
        }

        return new CreateResult(UrlResponse.from(created, appProperties.baseUrl()), true);
    }

    private ShortUrl createWithCustomAlias(CreateUrlRequest request) {
        Instant now = Instant.now();
        ShortUrl candidate = new ShortUrl(null, request.customAlias(), request.originalUrl(), true,
                UrlStatus.ACTIVE, now, now, request.expiresAt(), null);
        try {
            urlRepository.insert(candidate);
            return candidate;
        } catch (DataAccessException e) {
            if (SqliteConstraintViolations.isUniqueConstraintViolation(e)) {
                throw new AliasAlreadyExistsException(request.customAlias());
            }
            throw e;
        }
    }

    private ShortUrl createWithGeneratedCode(CreateUrlRequest request) {
        int length = appProperties.shortCode().length();
        int maxAttempts = appProperties.shortCode().maxGenerationAttempts();
        Instant now = Instant.now();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = shortCodeGenerator.generate(length);
            ShortUrl candidate = new ShortUrl(null, code, request.originalUrl(), false,
                    UrlStatus.ACTIVE, now, now, request.expiresAt(), null);
            try {
                urlRepository.insert(candidate);
                return candidate;
            } catch (DataAccessException e) {
                if (SqliteConstraintViolations.isUniqueConstraintViolation(e)) {
                    // Collision on the DB unique constraint - the real correctness
                    // backstop under concurrent races. Retry with a fresh code.
                    continue;
                }
                throw e;
            }
        }
        throw new ShortCodeGenerationException(
                "Failed to generate a unique short code after " + maxAttempts + " attempts");
    }

    public UrlResponse getUrl(String shortCode) {
        ShortUrl url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
        return UrlResponse.from(url, appProperties.baseUrl());
    }

    /**
     * Resolves a short code for redirection. Throws
     * {@link ShortCodeNotFoundException} if missing or soft-deleted, and
     * {@link UrlExpiredException} if active but past its expiry.
     */
    public ShortUrl resolveForRedirect(String shortCode) {
        ShortUrl url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
        if (url.status() == UrlStatus.DELETED) {
            throw new ShortCodeNotFoundException(shortCode);
        }
        if (url.isExpired()) {
            throw new UrlExpiredException(shortCode);
        }
        return url;
    }

    public UrlResponse updateUrl(String shortCode, UpdateUrlRequest request) {
        ShortUrl existing = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));

        String newUrl = request.originalUrl() != null ? request.originalUrl() : existing.originalUrl();
        if (request.originalUrl() != null) {
            validationService.validateOriginalUrl(request.originalUrl());
        }
        Instant newExpiresAt = request.expiresAt() != null ? request.expiresAt() : existing.expiresAt();

        Instant now = Instant.now();
        urlRepository.updateDestination(shortCode, newUrl, newExpiresAt, now);

        ShortUrl updated = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
        return UrlResponse.from(updated, appProperties.baseUrl());
    }

    public void deleteUrl(String shortCode) {
        urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
        urlRepository.updateStatus(shortCode, UrlStatus.DELETED, Instant.now());
    }

    private String hashRequest(CreateUrlRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.valueOf(request.originalUrl()) + '|' +
                    request.customAlias() + '|' +
                    request.expiresAt();
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

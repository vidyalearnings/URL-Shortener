package com.urlshortener.service;

import com.urlshortener.exception.InvalidUrlException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Centralized validation used explicitly by the service layer on BOTH the
 * create and update paths, so scheme/alias validation cannot be bypassed by
 * skipping a Bean Validation annotation on one DTO.
 */
@Component
public class UrlValidationService {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,20}$");

    private static final Set<String> RESERVED_WORDS = Set.of(
            "api", "admin", "health", "actuator", "static", "www"
    );

    /**
     * Validates that the given URL is well-formed and uses an allowed
     * scheme (http/https only). Throws {@link InvalidUrlException}
     * otherwise (mapped to 400).
     */
    public void validateOriginalUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new InvalidUrlException("originalUrl must not be blank");
        }
        URI uri;
        try {
            uri = new URI(originalUrl);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("originalUrl is not a valid URI: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new InvalidUrlException("originalUrl must use http or https scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("originalUrl must include a host");
        }
    }

    /**
     * Validates a custom alias format and rejects reserved words. Throws
     * {@link InvalidUrlException} otherwise (mapped to 400) - actual
     * conflicts with an existing alias are a separate concern handled at
     * insert time via {@code AliasAlreadyExistsException}.
     */
    public void validateCustomAlias(String alias) {
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new InvalidUrlException(
                    "customAlias must be 3-20 characters and contain only letters, digits, '_' or '-'");
        }
        if (RESERVED_WORDS.contains(alias.toLowerCase())) {
            throw new InvalidUrlException("customAlias '" + alias + "' is a reserved word");
        }
    }
}

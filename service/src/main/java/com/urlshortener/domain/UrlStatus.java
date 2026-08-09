package com.urlshortener.domain;

/**
 * Lifecycle status of a shortened URL.
 *
 * <p>{@code EXPIRED} is set proactively by {@link com.urlshortener.service.ExpiredLinkCleanupJob}
 * once {@code expires_at} has passed; until that sweep runs, an ACTIVE row past
 * its expiry is still caught reactively by {@link ShortUrl#isExpired()} at read
 * time, so behavior is correct either way - the sweep only makes the DB state
 * (and anything querying it directly) converge sooner.
 */
public enum UrlStatus {
    ACTIVE,
    EXPIRED,
    DELETED
}

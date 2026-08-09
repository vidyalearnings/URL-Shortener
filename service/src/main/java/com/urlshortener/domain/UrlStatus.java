package com.urlshortener.domain;

/**
 * Lifecycle status of a shortened URL.
 *
 * <p>Deliberately does NOT include an {@code EXPIRED} value yet - expiry is
 * checked lazily at read time by comparing {@code expires_at} to the current
 * time. Adding a dedicated {@code EXPIRED} status (e.g. via a background
 * sweep job) is a intentionally deferred follow-up.
 */
public enum UrlStatus {
    ACTIVE,
    DELETED
}

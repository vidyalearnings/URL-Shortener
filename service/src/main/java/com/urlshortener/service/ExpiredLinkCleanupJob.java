package com.urlshortener.service;

import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodically sweeps ACTIVE rows whose {@code expires_at} has passed and
 * flips them to EXPIRED, so the database reflects reality without waiting for
 * a read to trigger the lazy check in {@link com.urlshortener.domain.ShortUrl#isExpired()}.
 *
 * <p>This does not change correctness (reads were already expiry-safe via the
 * lazy check) - it only makes {@code status} converge sooner, which matters
 * for anything that queries status directly (e.g. analytics, admin tooling).
 */
@Component
public class ExpiredLinkCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredLinkCleanupJob.class);

    private final UrlRepository urlRepository;

    public ExpiredLinkCleanupJob(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Scheduled(fixedDelayString = "${app.cleanup.fixed-delay-ms:60000}")
    public void sweepExpiredLinks() {
        int updated = urlRepository.expireOverdueActive(Instant.now());
        if (updated > 0) {
            log.info("expired-link-cleanup: transitioned {} row(s) ACTIVE -> EXPIRED", updated);
        }
    }
}

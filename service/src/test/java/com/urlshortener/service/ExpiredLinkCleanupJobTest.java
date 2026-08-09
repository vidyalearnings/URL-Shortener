package com.urlshortener.service;

import com.urlshortener.domain.ShortUrl;
import com.urlshortener.domain.UrlStatus;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Brownfield enhancement: proves the scheduled sweep actively transitions
 * overdue ACTIVE rows to EXPIRED, rather than relying solely on the lazy
 * {@link ShortUrl#isExpired()} check at read time.
 */
@SpringBootTest
class ExpiredLinkCleanupJobTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        File dbFile = File.createTempFile("cleanup-job-test", ".db");
        Files.deleteIfExists(dbFile.toPath());
        dbFile.deleteOnExit();
        registry.add("DB_PATH", () -> dbFile.getAbsolutePath());
        // Disable the real scheduled trigger; the test invokes the sweep directly
        // so it's deterministic instead of racing a background timer.
        registry.add("app.cleanup.fixed-delay-ms", () -> "3600000");
    }

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private ExpiredLinkCleanupJob cleanupJob;

    @Test
    void sweepTransitionsOverdueActiveRowsToExpired() {
        Instant now = Instant.now();
        ShortUrl overdue = new ShortUrl(null, "old1exp", "https://example.com/overdue", false,
                UrlStatus.ACTIVE, now.minus(2, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.HOURS), null);
        ShortUrl notYetDue = new ShortUrl(null, "stillok", "https://example.com/future", false,
                UrlStatus.ACTIVE, now, now, now.plus(1, ChronoUnit.DAYS), null);
        ShortUrl noExpiry = new ShortUrl(null, "forever", "https://example.com/forever", false,
                UrlStatus.ACTIVE, now, now, null, null);

        urlRepository.insert(overdue);
        urlRepository.insert(notYetDue);
        urlRepository.insert(noExpiry);

        cleanupJob.sweepExpiredLinks();

        assertThat(urlRepository.findByShortCode("old1exp").orElseThrow().status())
                .isEqualTo(UrlStatus.EXPIRED);
        assertThat(urlRepository.findByShortCode("stillok").orElseThrow().status())
                .isEqualTo(UrlStatus.ACTIVE);
        assertThat(urlRepository.findByShortCode("forever").orElseThrow().status())
                .isEqualTo(UrlStatus.ACTIVE);
    }

    @Test
    void sweepIsIdempotentWhenNothingIsOverdue() {
        int updated = urlRepository.expireOverdueActive(Instant.now());
        assertThat(updated).isGreaterThanOrEqualTo(0);
        cleanupJob.sweepExpiredLinks();
    }
}

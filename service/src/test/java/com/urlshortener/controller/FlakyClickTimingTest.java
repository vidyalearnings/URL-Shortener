package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Intentionally flaky ~30% of the time to give the orchestration layer's
 * retry/MTTR metrics real signal to report - not a real coverage gap.
 *
 * <p>The click event triggered by a redirect is recorded asynchronously
 * (see {@code ClickTrackingService}) specifically so the redirect response
 * doesn't block on it. This test starts a "checker" thread at the same
 * instant the redirect request is dispatched, has it wait a deliberately
 * razor-thin slice of time, and then asserts the click row is already in
 * SQLite. Whether that holds is a genuine race between the checker thread's
 * tiny wait and the server picking up + flushing the async write - it will
 * pass when the scheduler favors the async task and fail when it doesn't.
 * Kept isolated in its own test class so its non-determinism can't
 * destabilize any other test's assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FlakyClickTimingTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        File dbFile = File.createTempFile("flaky-click-timing-test", ".db");
        Files.deleteIfExists(dbFile.toPath());
        dbFile.deleteOnExit();
        registry.add("DB_PATH", () -> dbFile.getAbsolutePath());
        registry.add("app.rate-limit.capacity", () -> "10000");
        registry.add("app.rate-limit.refill-per-second", () -> "1000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void disableRedirectFollowing() {
        TestRestTemplateRedirects.disable(restTemplate);
    }

    @Test
    void clickEventIsRecordedWithinAnUnrealisticallyNarrowWindow() throws InterruptedException {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/flaky-timing", null, null);
        ResponseEntity<UrlResponse> created = restTemplate.postForEntity("/api/urls", request, UrlResponse.class);
        String code = created.getBody().shortCode();

        AtomicBoolean sawClickEvent = new AtomicBoolean(false);
        AtomicInteger observedCount = new AtomicInteger(-1);
        CountDownLatch checkerStarted = new CountDownLatch(1);

        // Races the server-side async click write from the same starting line as
        // the request itself, rather than after the (much slower) HTTP round trip
        // completes - that round trip alone is generally enough time for the async
        // task to finish, which would make this assertion never flake.
        Thread checker = new Thread(() -> {
            checkerStarted.countDown();
            busyWaitNanos(15_000_000); // ~15ms - empirically calibrated to a razor-thin margin
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM click_events WHERE short_code = ?", Integer.class, code);
            observedCount.set(count == null ? 0 : count);
            sawClickEvent.set(count != null && count == 1);
        });
        checker.start();
        checkerStarted.await();

        ResponseEntity<Void> redirect = restTemplate.getForEntity("/" + code, Void.class);
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        checker.join(5_000);

        assertThat(sawClickEvent.get())
                .as("click event (count=%d) should already be persisted within the narrow window - " +
                        "genuinely racy against async thread-pool scheduling, expected to flake", observedCount.get())
                .isTrue();
    }

    private static void busyWaitNanos(long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}

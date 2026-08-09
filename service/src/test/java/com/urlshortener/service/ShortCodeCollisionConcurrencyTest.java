package com.urlshortener.service;

import com.urlshortener.dto.CreateUrlRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The most important test in this module: proves that the DB unique
 * constraint on {@code urls.short_code} is a genuine correctness backstop
 * under real concurrent writes, not just a happy-path illusion.
 *
 * <p>Strategy: shrink the short-code length down to 1 character (a 62-code
 * Base62 space), then pre-fill 60 of those 62 possible codes directly via
 * the repository, leaving exactly 2 free slots. 50 threads then fire
 * concurrent {@code POST /api/urls} requests at the same instant via a
 * {@link CountDownLatch} starting gate. Because only 2 codes remain
 * available, AT MOST 2 requests can possibly succeed - the rest are
 * mathematically guaranteed to exhaust {@code max-generation-attempts} and
 * receive 503, no matter how the race resolves. This makes the test
 * deterministic in its bounds while still exercising a real concurrent
 * race (which threads "win" the 2 slots is intentionally left to chance).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShortCodeCollisionConcurrencyTest {

    private static File dbFile;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        dbFile = File.createTempFile("collision-test", ".db");
        Files.deleteIfExists(dbFile.toPath());
        dbFile.deleteOnExit();
        registry.add("DB_PATH", () -> dbFile.getAbsolutePath());
        registry.add("app.short-code.length", () -> "1");
        registry.add("app.short-code.max-generation-attempts", () -> "5");
        // This test intentionally fires far more requests from a single "client" (the
        // test JVM) than the default rate limit allows within the same second; raise
        // the budget so the assertions are about collision handling, not rate limiting.
        registry.add("app.rate-limit.capacity", () -> "1000");
        registry.add("app.rate-limit.refill-per-second", () -> "1000");
    }

    @AfterAll
    static void cleanup() {
        if (dbFile != null) {
            dbFile.delete();
        }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CONCURRENT_REQUESTS = 50;
    private static final int FREE_SLOTS = 2;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentCreatesNeverProduceDuplicateShortCodesAndExhaustionYields503() throws InterruptedException {
        // Pre-fill every code except the last FREE_SLOTS characters of the alphabet,
        // leaving a tiny, known-size codespace for the concurrent burst to race over.
        int toReserve = ALPHABET.length() - FREE_SLOTS;
        for (int i = 0; i < toReserve; i++) {
            String code = String.valueOf(ALPHABET.charAt(i));
            jdbcTemplate.update(
                    "INSERT INTO urls (short_code, original_url, is_custom_alias, status, created_at, updated_at) " +
                            "VALUES (?, ?, 0, 'ACTIVE', datetime('now'), datetime('now'))",
                    code, "https://example.com/reserved/" + code
            );
        }

        int threadCount = CONCURRENT_REQUESTS;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Integer> statusCodes = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger serviceUnavailable = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    CreateUrlRequest request = new CreateUrlRequest(
                            "https://example.com/race/" + idx, null, null);
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "http://localhost:" + port + "/api/urls", request, String.class);
                    int status = response.getStatusCode().value();
                    synchronized (statusCodes) {
                        statusCodes.add(status);
                    }
                    if (status == HttpStatus.CREATED.value()) {
                        created.incrementAndGet();
                    } else if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                        serviceUnavailable.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("all requests should complete within timeout").isTrue();
        assertThat(other.get())
                .as("no unexpected status codes should occur, got: %s", statusCodes)
                .isZero();

        // Pigeonhole: only FREE_SLOTS codes were available, so at most that many
        // requests could possibly succeed - the rest MUST have exhausted retries.
        assertThat(created.get()).isLessThanOrEqualTo(FREE_SLOTS);
        assertThat(created.get() + serviceUnavailable.get()).isEqualTo(threadCount);
        assertThat(serviceUnavailable.get()).isGreaterThanOrEqualTo(threadCount - FREE_SLOTS);

        // The real assertion: zero duplicate short_code values ever landed in the DB,
        // even though many threads raced for the same tiny set of remaining codes.
        Integer totalRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM urls", Integer.class);
        Integer distinctCodes = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT short_code) FROM urls", Integer.class);
        assertThat(totalRows).isEqualTo(distinctCodes);

        // And the row count should be exactly reserved + however many actually won a free slot.
        assertThat(totalRows).isEqualTo(toReserve + created.get());
    }
}

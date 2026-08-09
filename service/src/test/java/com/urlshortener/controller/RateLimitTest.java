package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.ErrorResponse;
import com.urlshortener.dto.UrlResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kept in its own test class with a tiny, dedicated rate-limit budget so it
 * doesn't interfere with (or get interfered with by) the generous budgets
 * used everywhere else in the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        File dbFile = File.createTempFile("rate-limit-test", ".db");
        Files.deleteIfExists(dbFile.toPath());
        dbFile.deleteOnExit();
        registry.add("DB_PATH", () -> dbFile.getAbsolutePath());
        registry.add("app.rate-limit.capacity", () -> "3");
        registry.add("app.rate-limit.refill-per-second", () -> "0.001");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void exceedingCapacityReturns429() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/rate-limit", null, null);

        for (int i = 0; i < 3; i++) {
            ResponseEntity<UrlResponse> response = restTemplate.postForEntity("/api/urls", request, UrlResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<ErrorResponse> limited = restTemplate.postForEntity("/api/urls", request, ErrorResponse.class);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}

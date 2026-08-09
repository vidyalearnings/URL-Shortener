package com.urlshortener.controller;

import com.urlshortener.dto.AnalyticsResponse;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.ErrorResponse;
import com.urlshortener.dto.UpdateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full create -> redirect -> analytics -> update -> delete lifecycle,
 * exercised end-to-end over real HTTP against a running Spring context.
 * Uses {@link org.awaitility.Awaitility} (never {@code Thread.sleep}) to
 * assert on the async click-recording side effect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlShortenerIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        File dbFile = File.createTempFile("lifecycle-integration-test", ".db");
        Files.deleteIfExists(dbFile.toPath());
        dbFile.deleteOnExit();
        registry.add("DB_PATH", () -> dbFile.getAbsolutePath());
        registry.add("app.rate-limit.capacity", () -> "10000");
        registry.add("app.rate-limit.refill-per-second", () -> "1000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void disableRedirectFollowing() {
        TestRestTemplateRedirects.disable(restTemplate);
    }

    @Test
    void fullLifecycle() {
        // 1. Create
        CreateUrlRequest createRequest = new CreateUrlRequest("https://example.com/lifecycle", null, null);
        ResponseEntity<UrlResponse> createResponse = restTemplate.postForEntity("/api/urls", createRequest, UrlResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = createResponse.getBody().shortCode();

        // 2. Redirect - response must come back immediately (302), independent of the async click write
        ResponseEntity<Void> redirectResponse = restTemplate.getForEntity("/" + code, Void.class);
        assertThat(redirectResponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirectResponse.getHeaders().getLocation().toString()).isEqualTo("https://example.com/lifecycle");

        // 3. Eventually the async click event shows up in analytics - poll, don't sleep.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<AnalyticsResponse> analyticsResponse = restTemplate.getForEntity(
                    "/api/urls/" + code + "/analytics", AnalyticsResponse.class);
            assertThat(analyticsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(analyticsResponse.getBody().totalClicks()).isEqualTo(1);
            assertThat(analyticsResponse.getBody().lastAccessedAt()).isNotNull();
        });

        // 4. Update destination
        UpdateUrlRequest updateRequest = new UpdateUrlRequest("https://example.com/lifecycle-updated", null);
        ResponseEntity<UrlResponse> updateResponse = restTemplate.exchange(
                "/api/urls/" + code, HttpMethod.PUT, new HttpEntity<>(updateRequest), UrlResponse.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().originalUrl()).isEqualTo("https://example.com/lifecycle-updated");

        // Redirect now goes to the updated destination
        ResponseEntity<Void> redirectAfterUpdate = restTemplate.getForEntity("/" + code, Void.class);
        assertThat(redirectAfterUpdate.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirectAfterUpdate.getHeaders().getLocation().toString())
                .isEqualTo("https://example.com/lifecycle-updated");

        // 5. Delete (soft) and confirm the code is no longer resolvable
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/urls/" + code, HttpMethod.DELETE, null, Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<ErrorResponse> redirectAfterDelete = restTemplate.getForEntity("/" + code, ErrorResponse.class);
        assertThat(redirectAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

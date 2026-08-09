package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.ErrorResponse;
import com.urlshortener.dto.UpdateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlControllerTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        File dbFile = File.createTempFile("url-controller-test", ".db");
        Files.deleteIfExists(dbFile.toPath());
        dbFile.deleteOnExit();
        registry.add("DB_PATH", () -> dbFile.getAbsolutePath());
        // Generous budget: many assertions in this class each make a handful of
        // requests against the same shared in-memory bucket (same client IP).
        registry.add("app.rate-limit.capacity", () -> "10000");
        registry.add("app.rate-limit.refill-per-second", () -> "1000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createReturns201WithExpectedFields() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/some/page", null, null);

        ResponseEntity<UrlResponse> response = restTemplate.postForEntity("/api/urls", request, UrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UrlResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.shortCode()).hasSize(7);
        assertThat(body.originalUrl()).isEqualTo("https://example.com/some/page");
        assertThat(body.isCustomAlias()).isFalse();
        assertThat(body.shortUrl()).endsWith("/" + body.shortCode());
        assertThat(body.status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void getReturnsCreatedUrl() {
        UrlResponse created = create("https://example.com/get-me");

        ResponseEntity<UrlResponse> response = restTemplate.getForEntity(
                "/api/urls/" + created.shortCode(), UrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().originalUrl()).isEqualTo("https://example.com/get-me");
    }

    @Test
    void getMissingReturns404() {
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/api/urls/doesnotexist", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateChangesDestination() {
        UrlResponse created = create("https://example.com/before");

        UpdateUrlRequest update = new UpdateUrlRequest("https://example.com/after", null);
        ResponseEntity<UrlResponse> response = restTemplate.exchange(
                "/api/urls/" + created.shortCode(), HttpMethod.PUT,
                new HttpEntity<>(update), UrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().originalUrl()).isEqualTo("https://example.com/after");
    }

    @Test
    void updateMissingReturns404() {
        UpdateUrlRequest update = new UpdateUrlRequest("https://example.com/after", null);
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/urls/doesnotexist", HttpMethod.PUT,
                new HttpEntity<>(update), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateWithInvalidSchemeReturns400() {
        UrlResponse created = create("https://example.com/scheme-check");

        UpdateUrlRequest update = new UpdateUrlRequest("javascript:alert(1)", null);
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/urls/" + created.shortCode(), HttpMethod.PUT,
                new HttpEntity<>(update), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteSoftDeletesAndReturns204() {
        UrlResponse created = create("https://example.com/to-delete");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/urls/" + created.shortCode(), HttpMethod.DELETE, null, Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<UrlResponse> getResponse = restTemplate.getForEntity(
                "/api/urls/" + created.shortCode(), UrlResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().status().name()).isEqualTo("DELETED");
    }

    @Test
    void deleteMissingReturns404() {
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/urls/doesnotexist", HttpMethod.DELETE, null, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createWithCustomAliasSucceeds() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/aliased", "my-alias-1", null);
        ResponseEntity<UrlResponse> response = restTemplate.postForEntity("/api/urls", request, UrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().shortCode()).isEqualTo("my-alias-1");
        assertThat(response.getBody().isCustomAlias()).isTrue();
    }

    @Test
    void createWithDuplicateAliasReturns409() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/dup-1", "dup-alias", null);
        restTemplate.postForEntity("/api/urls", request, UrlResponse.class);

        CreateUrlRequest again = new CreateUrlRequest("https://example.com/dup-2", "dup-alias", null);
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/urls", again, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createWithReservedAliasReturns400() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/reserved", "admin", null);
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/urls", request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithInvalidAliasFormatReturns400() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/badformat", "a!", null);
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/urls", request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithNonHttpSchemeReturns400() {
        CreateUrlRequest request = new CreateUrlRequest("javascript:alert(1)", null, null);
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/urls", request, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        CreateUrlRequest dataUri = new CreateUrlRequest("data:text/html,hi", null, null);
        ResponseEntity<ErrorResponse> response2 = restTemplate.postForEntity("/api/urls", dataUri, ErrorResponse.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithBlankOriginalUrlReturns400() {
        CreateUrlRequest request = new CreateUrlRequest("", null, null);
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/urls", request, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void idempotencyKeyReplayWithSameBodyReturns200AndSameCode() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/idempotent", null, null);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Idempotency-Key", "idem-key-1");

        ResponseEntity<UrlResponse> first = restTemplate.postForEntity(
                "/api/urls", new HttpEntity<>(request, headers), UrlResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<UrlResponse> second = restTemplate.postForEntity(
                "/api/urls", new HttpEntity<>(request, headers), UrlResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().shortCode()).isEqualTo(first.getBody().shortCode());
    }

    @Test
    void idempotencyKeyReusedWithDifferentBodyReturns409() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Idempotency-Key", "idem-key-2");

        CreateUrlRequest first = new CreateUrlRequest("https://example.com/idem-a", null, null);
        ResponseEntity<UrlResponse> firstResponse = restTemplate.postForEntity(
                "/api/urls", new HttpEntity<>(first, headers), UrlResponse.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        CreateUrlRequest second = new CreateUrlRequest("https://example.com/idem-b", null, null);
        ResponseEntity<ErrorResponse> secondResponse = restTemplate.postForEntity(
                "/api/urls", new HttpEntity<>(second, headers), ErrorResponse.class);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createWithExpiresAtIsPersisted() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/expiring", null, expiresAt);
        ResponseEntity<UrlResponse> response = restTemplate.postForEntity("/api/urls", request, UrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().expiresAt()).isEqualTo(expiresAt);
    }

    private UrlResponse create(String originalUrl) {
        CreateUrlRequest request = new CreateUrlRequest(originalUrl, null, null);
        ResponseEntity<UrlResponse> response = restTemplate.postForEntity("/api/urls", request, UrlResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}

package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.ErrorResponse;
import com.urlshortener.dto.UrlResponse;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedirectControllerTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        File dbFile = File.createTempFile("redirect-controller-test", ".db");
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
    void redirectsWithLocationHeader() {
        UrlResponse created = create("https://example.com/redirect-target", null);

        ResponseEntity<Void> response = restTemplate.getForEntity("/" + created.shortCode(), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://example.com/redirect-target");
    }

    @Test
    void missingCodeReturns404() {
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity("/doesnotexist999", ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletedCodeReturns404() {
        UrlResponse created = create("https://example.com/will-delete", null);
        restTemplate.delete("/api/urls/" + created.shortCode());

        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity("/" + created.shortCode(), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void expiredCodeReturns410() {
        UrlResponse created = create("https://example.com/expired-target", Instant.now().minusSeconds(60));

        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity("/" + created.shortCode(), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    private UrlResponse create(String originalUrl, Instant expiresAt) {
        CreateUrlRequest request = new CreateUrlRequest(originalUrl, null, expiresAt);
        ResponseEntity<UrlResponse> response = restTemplate.postForEntity("/api/urls", request, UrlResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}

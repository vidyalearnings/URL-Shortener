package com.urlshortener.controller;

import com.urlshortener.dto.AnalyticsResponse;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        File dbFile = File.createTempFile("analytics-test", ".db");
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
    void analyticsAggregatesClicksReferrersAndUserAgents() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/analytics-target", null, null);
        ResponseEntity<UrlResponse> created = restTemplate.postForEntity("/api/urls", request, UrlResponse.class);
        String code = created.getBody().shortCode();

        click(code, "https://referrer-a.com", "AgentA");
        click(code, "https://referrer-a.com", "AgentA");
        click(code, "https://referrer-b.com", "AgentB");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<AnalyticsResponse> response = restTemplate.getForEntity(
                    "/api/urls/" + code + "/analytics", AnalyticsResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            AnalyticsResponse body = response.getBody();
            assertThat(body.totalClicks()).isEqualTo(3);
            assertThat(body.referrers()).containsEntry("https://referrer-a.com", 2L);
            assertThat(body.referrers()).containsEntry("https://referrer-b.com", 1L);
            assertThat(body.userAgents()).containsEntry("AgentA", 2L);
            assertThat(body.userAgents()).containsEntry("AgentB", 1L);
            assertThat(body.clicksPerDay()).isNotEmpty();
            assertThat(body.lastAccessedAt()).isNotNull();
        });
    }

    @Test
    void analyticsForUnknownCodeReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/urls/doesnotexist/analytics", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void click(String code, String referrer, String userAgent) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Referer", referrer);
        headers.add("User-Agent", userAgent);
        restTemplate.exchange("/" + code, HttpMethod.GET, new HttpEntity<>(headers), Void.class);
    }
}

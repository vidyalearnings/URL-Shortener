package com.urlshortener.controller;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * By default {@link TestRestTemplate}'s underlying {@code HttpURLConnection}-based
 * request factory follows HTTP redirects transparently, which means a plain
 * {@code getForEntity("/{code}", ...)} call against the redirect endpoint
 * silently chases the {@code Location} header (out to the real internet, in
 * the case of {@code https://example.com/...} test fixtures) instead of
 * handing back the raw 302. Tests that need to assert on the 3xx response
 * itself must disable that behavior first.
 */
final class TestRestTemplateRedirects {

    private TestRestTemplateRedirects() {
    }

    static void disable(TestRestTemplate restTemplate) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        restTemplate.getRestTemplate().setRequestFactory(factory);
    }
}

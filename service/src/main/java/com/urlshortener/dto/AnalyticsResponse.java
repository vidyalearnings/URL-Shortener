package com.urlshortener.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AnalyticsResponse(
        String shortCode,
        long totalClicks,
        List<DailyCount> clicksPerDay,
        Map<String, Long> referrers,
        Map<String, Long> userAgents,
        Instant lastAccessedAt
) {
    public record DailyCount(String date, long count) {
    }
}

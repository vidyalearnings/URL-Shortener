package com.urlshortener.repository;

import com.urlshortener.domain.ClickEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ClickEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClickEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(ClickEvent event) {
        jdbcTemplate.update(
                "INSERT INTO click_events (short_code, clicked_at, referrer, user_agent, ip_hash) VALUES (?, ?, ?, ?, ?)",
                event.shortCode(),
                event.clickedAt().toString(),
                event.referrer(),
                event.userAgent(),
                event.ipHash()
        );
    }

    public long countByShortCode(String shortCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM click_events WHERE short_code = ?", Long.class, shortCode);
        return count == null ? 0L : count;
    }

    /**
     * Click counts grouped by calendar day (UTC date portion of clicked_at),
     * ordered chronologically.
     */
    public List<Map.Entry<String, Long>> countByDay(String shortCode) {
        return jdbcTemplate.query(
                "SELECT substr(clicked_at, 1, 10) AS day, COUNT(*) AS cnt " +
                        "FROM click_events WHERE short_code = ? GROUP BY day ORDER BY day",
                (rs, rowNum) -> Map.entry(rs.getString("day"), rs.getLong("cnt")),
                shortCode
        );
    }

    public Map<String, Long> topReferrers(String shortCode, int limit) {
        return topGroupedBy("referrer", shortCode, limit);
    }

    public Map<String, Long> topUserAgents(String shortCode, int limit) {
        return topGroupedBy("user_agent", shortCode, limit);
    }

    private Map<String, Long> topGroupedBy(String column, String shortCode, int limit) {
        List<Map.Entry<String, Long>> rows = jdbcTemplate.query(
                "SELECT COALESCE(" + column + ", 'unknown') AS term, COUNT(*) AS cnt " +
                        "FROM click_events WHERE short_code = ? GROUP BY term ORDER BY cnt DESC LIMIT ?",
                (rs, rowNum) -> Map.entry(rs.getString("term"), rs.getLong("cnt")),
                shortCode, limit
        );
        Map<String, Long> result = new LinkedHashMap<>();
        rows.forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public java.util.Optional<Instant> lastClickedAt(String shortCode) {
        String value = jdbcTemplate.queryForObject(
                "SELECT MAX(clicked_at) FROM click_events WHERE short_code = ?", String.class, shortCode);
        return java.util.Optional.ofNullable(value).map(Instant::parse);
    }
}

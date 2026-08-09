package com.urlshortener.repository;

import com.urlshortener.domain.ShortUrl;
import com.urlshortener.domain.UrlStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Throwaway empirical experiment (kept as a regression test) to establish
 * exactly how a SQLite UNIQUE constraint violation surfaces through Spring's
 * JDBC exception translation with the sqlite-jdbc driver.
 *
 * <p><b>Empirical finding (observed by running this test):</b> Neither
 * {@link org.springframework.dao.DuplicateKeyException} NOR the broader
 * {@link org.springframework.dao.DataIntegrityViolationException} is
 * thrown. sqlite-jdbc does not populate {@code SQLException.getSQLState()}
 * (it comes back {@code null}), so Spring's
 * {@code SQLErrorCodeSQLExceptionTranslator} cannot classify the error by
 * SQL state class either (SQLite also has no entry in the bundled
 * {@code sql-error-codes.xml}). The result is that the exception falls all
 * the way through to the generic
 * {@link org.springframework.jdbc.UncategorizedSQLException} (itself a
 * {@code DataAccessException}, but NOT a
 * {@code DataIntegrityViolationException}).
 *
 * <p>What IS reliable: the underlying cause is always an
 * {@code org.sqlite.SQLiteException} whose {@code getResultCode()} is
 * {@code SQLITE_CONSTRAINT_UNIQUE} (vendor error code 19) and whose message
 * contains "UNIQUE constraint failed".
 *
 * <p>Consequence for {@code UrlShortenerService}: catch the broad
 * {@link org.springframework.dao.DataAccessException} around inserts, then
 * unwrap the cause chain looking for an {@code org.sqlite.SQLiteException}
 * with a UNIQUE/PRIMARY KEY constraint result code (falling back to a
 * message check) to decide "this was a collision" vs. rethrowing anything
 * else unchanged.
 */
class UrlRepositoryExceptionTranslationExperimentTest {

    @Test
    void duplicateShortCodeInsertSurfacesAsUncategorizedSqlExceptionWithSqliteConstraintCause() throws IOException {
        File dbFile = File.createTempFile("exception-experiment", ".db");
        dbFile.deleteOnExit();
        Files.deleteIfExists(dbFile.toPath());

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.sqlite.JDBC.class);
        dataSource.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE urls (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    short_code TEXT NOT NULL UNIQUE,
                    original_url TEXT NOT NULL,
                    is_custom_alias INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    expires_at TEXT,
                    last_accessed_at TEXT
                )
                """);

        UrlRepository repository = new UrlRepository(jdbcTemplate);
        ShortUrl first = new ShortUrl(null, "dupe123", "https://example.com/1", false, UrlStatus.ACTIVE,
                Instant.now(), Instant.now(), null, null);
        ShortUrl second = new ShortUrl(null, "dupe123", "https://example.com/2", false, UrlStatus.ACTIVE,
                Instant.now(), Instant.now(), null, null);

        repository.insert(first);

        try {
            repository.insert(second);
            fail("Expected a DataAccessException on duplicate short_code insert");
        } catch (DataAccessException e) {
            System.out.println("[EXPERIMENT] Exception class: " + e.getClass().getName());
            System.out.println("[EXPERIMENT] Message: " + e.getMessage());
            Throwable cause = e.getCause();
            System.out.println("[EXPERIMENT] Cause class: " + (cause == null ? "null" : cause.getClass().getName()));
            assertThat(cause).isInstanceOf(org.sqlite.SQLiteException.class);
            org.sqlite.SQLiteException sqliteEx = (org.sqlite.SQLiteException) cause;
            System.out.println("[EXPERIMENT] SQLite result code: " + sqliteEx.getResultCode());
            assertThat(sqliteEx.getResultCode()).isEqualTo(org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
            assertThat(sqliteEx.getMessage()).contains("UNIQUE constraint failed");

            // This is NOT a DataIntegrityViolationException - confirming the finding above.
            assertThat(e).isNotInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(e).isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class);
        }

        dbFile.delete();
    }
}

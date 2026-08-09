package com.urlshortener.repository;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.springframework.dao.DataAccessException;

/**
 * Helper to classify whether a {@link DataAccessException} thrown from a
 * JdbcTemplate write against SQLite was caused by a UNIQUE/PRIMARY KEY
 * constraint violation.
 *
 * <p>Empirically verified (see {@code UrlRepositoryExceptionTranslationExperimentTest}):
 * sqlite-jdbc does not populate {@code SQLException.getSQLState()}, and
 * SQLite has no entry in Spring's bundled {@code sql-error-codes.xml}, so
 * neither {@code DuplicateKeyException} nor even the broader
 * {@code DataIntegrityViolationException} is reliably thrown - the
 * exception surfaces as a generic
 * {@link org.springframework.jdbc.UncategorizedSQLException}. The only
 * reliable signal is unwrapping the cause chain for an
 * {@link org.sqlite.SQLiteException} whose result code is one of the
 * {@code SQLITE_CONSTRAINT_*} family (we match UNIQUE and PRIMARYKEY
 * specifically), falling back to a message check as a last resort in case a
 * future driver version changes how the code is surfaced.
 */
public final class SqliteConstraintViolations {

    private SqliteConstraintViolations() {
    }

    public static boolean isUniqueConstraintViolation(DataAccessException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLiteException sqliteEx) {
                SQLiteErrorCode code = sqliteEx.getResultCode();
                if (code == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                        || code == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                    return true;
                }
                String message = sqliteEx.getMessage();
                return message != null && message.toUpperCase().contains("UNIQUE CONSTRAINT");
            }
            current = current.getCause();
        }
        return false;
    }
}

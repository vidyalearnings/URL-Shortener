package com.urlshortener.orchestrator.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretScannerTest {

    private final SecretScanner scanner = new SecretScanner();

    @Test
    void detectsAwsAccessKey() {
        String content = "String key = \"AKIAABCDEFGHIJKLMNOP\";";
        List<SecretScanner.Violation> violations = scanner.scanContent("Config.java", content);
        assertTrue(violations.stream().anyMatch(v -> v.pattern().equals(SecretScanner.AWS_ACCESS_KEY.pattern())));
    }

    @Test
    void detectsPemPrivateKeyHeader() {
        String content = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----";
        List<SecretScanner.Violation> violations = scanner.scanContent("id_rsa.txt", content);
        assertTrue(violations.stream().anyMatch(v -> v.pattern().equals(SecretScanner.PEM_PRIVATE_KEY.pattern())));
    }

    @Test
    void detectsGenericPasswordAssignment() {
        String content = "String password = \"hunter2super\";";
        List<SecretScanner.Violation> violations = scanner.scanContent("Db.java", content);
        assertTrue(violations.stream().anyMatch(v -> v.pattern().equals(SecretScanner.GENERIC_SECRET.pattern())));
    }

    @Test
    void detectsApiKeyAndTokenAssignments() {
        assertTrue(scanner.scanContent("a.env", "api_key: 'sk-1234567890abcdef'").size() > 0);
        assertTrue(scanner.scanContent("b.env", "token=\"abcdef123456\"").size() > 0);
    }

    @Test
    void noFalsePositivesOnOrdinaryCode() {
        String content = """
                public class ShortCodeGenerator {
                    private static final int LENGTH = 7;

                    public String generate(String url) {
                        // fast, deterministic hash-based generation
                        return Integer.toHexString(url.hashCode());
                    }

                    public boolean isValid(String code) {
                        return code != null && code.length() == LENGTH;
                    }
                }
                """;
        List<SecretScanner.Violation> violations = scanner.scanContent("ShortCodeGenerator.java", content);
        assertTrue(violations.isEmpty(), "expected no violations on ordinary code, got: " + violations);
    }

    @Test
    void noFalsePositiveOnShortPlaceholderValues() {
        // Generic secret pattern requires 6+ chars inside quotes; short placeholders should not match
        // even though the keyword itself ("password") is present.
        String content = "String password = \"abc\";";
        List<SecretScanner.Violation> violations = scanner.scanContent("Labels.java", content);
        assertFalse(violations.stream().anyMatch(v -> v.pattern().equals(SecretScanner.GENERIC_SECRET.pattern())));
    }
}

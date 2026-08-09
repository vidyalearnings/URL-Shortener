package com.urlshortener.orchestrator.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Does the actual work behind {@code NoSecretsInChangedFilesRule}: finds changed files in a git
 * working tree (via {@code git status --porcelain} and {@code git diff --name-only}) and greps
 * their content for secret-shaped strings.
 */
public class SecretScanner {

    public static final Pattern AWS_ACCESS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");
    public static final Pattern PEM_PRIVATE_KEY = Pattern.compile("-----BEGIN.*PRIVATE KEY-----");
    public static final Pattern GENERIC_SECRET =
            Pattern.compile("(?i)(password|secret|api[_-]?key|token)\\s*[:=]\\s*['\"][^'\"]{6,}['\"]");

    private static final List<Pattern> PATTERNS = List.of(AWS_ACCESS_KEY, PEM_PRIVATE_KEY, GENERIC_SECRET);

    public record Violation(String file, String pattern, String snippet) {
    }

    /** Result of scanning a repo: the changed files considered, and any secret-shaped matches found. */
    public record ScanResult(List<String> changedFiles, List<Violation> violations) {
        public boolean hasViolations() {
            return !violations.isEmpty();
        }
    }

    /**
     * Lists files changed in the working tree (uncommitted, per {@code git status --porcelain})
     * plus files changed relative to HEAD (per {@code git diff --name-only}), then scans their
     * current on-disk content for secret-shaped strings.
     */
    public ScanResult scan(Path repoDir) {
        Set<String> changed = new LinkedHashSet<>();
        changed.addAll(runGitLines(repoDir, "status", "--porcelain"));
        changed.addAll(runGitLines(repoDir, "diff", "--name-only"));

        // "git status --porcelain" lines look like "XY path"; strip the status prefix.
        List<String> files = new ArrayList<>();
        for (String line : changed) {
            String cleaned = line.length() > 3 && (line.charAt(2) == ' ') ? line.substring(3).trim() : line.trim();
            if (!cleaned.isEmpty()) {
                files.add(cleaned);
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (String file : files) {
            Path path = repoDir.resolve(file);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String content;
            try {
                content = Files.readString(path);
            } catch (IOException | RuntimeException e) {
                // Binary or unreadable file - skip rather than fail the scan.
                continue;
            }
            violations.addAll(scanContent(file, content));
        }
        return new ScanResult(files, violations);
    }

    /** Scans raw text content (used directly by tests, without needing a real git repo). */
    public List<Violation> scanContent(String fileLabel, String content) {
        List<Violation> violations = new ArrayList<>();
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String snippet = matcher.group();
                if (snippet.length() > 80) {
                    snippet = snippet.substring(0, 80) + "...";
                }
                violations.add(new Violation(fileLabel, pattern.pattern(), snippet));
            }
        }
        return violations;
    }

    private List<String> runGitLines(Path repoDir, String... gitArgs) {
        try {
            if (!Files.isDirectory(repoDir)) {
                return List.of();
            }
            List<String> command = new ArrayList<>();
            command.add("git");
            for (String arg : gitArgs) {
                command.add(arg);
            }
            ProcessBuilder pb = new ProcessBuilder(command).directory(repoDir.toFile()).redirectErrorStream(false);
            Process process = pb.start();
            List<String> lines;
            try (var in = process.inputReader()) {
                lines = in.lines().filter(l -> !l.isBlank()).toList();
            }
            process.waitFor(30, TimeUnit.SECONDS);
            return lines;
        } catch (IOException e) {
            // git not on PATH, or repo doesn't exist yet - treat as "no changed files" rather than failing.
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}

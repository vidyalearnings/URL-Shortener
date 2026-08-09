package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rule 3: before each {@code implement-*} stage, scans changed files in the service repo for
 * secret-shaped strings (AWS access keys, PEM private key headers, generic password/secret/
 * token/api-key assignments). Blocks the stage if any are found. Actual scanning logic lives in
 * {@link SecretScanner}.
 *
 * <p>Category: SECURITY.
 */
public class NoSecretsInChangedFilesRule implements PolicyRule {

    public static final String ID = "no-secrets-in-changed-files";

    private final SecretScanner scanner;

    public NoSecretsInChangedFilesRule() {
        this(new SecretScanner());
    }

    public NoSecretsInChangedFilesRule(SecretScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.SECURITY;
    }

    @Override
    public PolicyResult evaluate(RunContext context, StageDef node, Map<String, Object> params) {
        if (context.getServiceRepoPath() == null) {
            return PolicyResult.allow();
        }
        SecretScanner.ScanResult result = scanner.scan(context.getServiceRepoPath());
        if (!result.hasViolations()) {
            return PolicyResult.allow();
        }
        String reason = "Secret-shaped strings found in changed files: " + result.violations().stream()
                .map(v -> v.file() + " (" + v.snippet() + ")")
                .collect(Collectors.joining("; "));
        return PolicyResult.block(reason);
    }
}

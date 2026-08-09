package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fallback for {@link DocumentationStage}: if the full documentation check (doc files +
 * OpenAPI schema validation) keeps failing after retries, degrade to the minimum acceptable
 * bar - just confirm the root README exists and is non-empty - rather than failing the whole
 * release-readiness path outright. A deliberately weaker check, used deliberately: this is what
 * "fallback" means (a different, lesser-but-acceptable path), as distinct from "retry" (try the
 * same check again) or "rollback" (undo state after failure).
 */
public class MinimalDocumentationStage implements StageExecutor {

    @Override
    public StageResult execute(RunContext context, StageDef def) {
        Path repoRoot = context.getServiceRepoPath() == null ? null
                : context.getServiceRepoPath().toAbsolutePath().normalize().getParent();
        if (repoRoot == null) {
            return StageResult.failure("no service repo path configured; cannot locate README.md", Map.of());
        }
        Path readme = repoRoot.resolve("README.md");
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("checkedFile", readme.toString());

        boolean exists = Files.isRegularFile(readme);
        boolean nonEmpty = false;
        if (exists) {
            try {
                nonEmpty = Files.size(readme) > 0;
            } catch (Exception ignored) {
                // treated as non-empty check failed below
            }
        }

        if (exists && nonEmpty) {
            return StageResult.success("Minimal documentation check passed: README.md exists and is non-empty "
                    + "(full doc/OpenAPI validation was skipped - this is the degraded fallback path).", outputs);
        }
        return StageResult.failure("Minimal documentation check failed: README.md missing or empty at " + readme, outputs);
    }
}

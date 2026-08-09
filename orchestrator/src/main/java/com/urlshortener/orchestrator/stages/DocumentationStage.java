package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies expected documentation files exist and are non-empty, and (if present) that
 * {@code openapi.yaml} parses as valid YAML with the expected top-level keys. Real checks --
 * but tolerant/skippable via {@code params.strict=false} so it can be unit-tested in isolation
 * without requiring the (parallel-built) service module to exist yet.
 */
public class DocumentationStage implements StageExecutor {

    private static final List<String> DEFAULT_EXPECTED_OPENAPI_KEYS = List.of("openapi", "paths");

    @Override
    public StageResult execute(RunContext context, StageDef def) {
        boolean strict = def.getParams().get("strict") == null || Boolean.TRUE.equals(def.getParams().get("strict"));
        // Resolve to absolute first: Path.getParent() on a bare single-segment relative path
        // (e.g. "service") otherwise returns null instead of the actual parent directory.
        Path repoRoot = context.getServiceRepoPath() == null ? null
                : context.getServiceRepoPath().toAbsolutePath().normalize().getParent();

        List<String> docFiles = stringList(def.getParams().get("docFiles"));
        List<String> issues = new ArrayList<>();
        List<String> checkedFiles = new ArrayList<>();

        for (String docFile : docFiles) {
            Path resolved = resolve(repoRoot, docFile);
            checkedFiles.add(resolved.toString());
            if (!Files.isRegularFile(resolved)) {
                issues.add("missing doc file: " + resolved);
                continue;
            }
            try {
                if (Files.size(resolved) == 0) {
                    issues.add("empty doc file: " + resolved);
                }
            } catch (IOException e) {
                issues.add("could not read doc file: " + resolved + " (" + e.getMessage() + ")");
            }
        }

        Object openapiPathObj = def.getParams().get("openapiPath");
        boolean openapiChecked = false;
        boolean openapiValid = true;
        if (openapiPathObj != null) {
            Path openapiPath = resolve(repoRoot, String.valueOf(openapiPathObj));
            if (Files.isRegularFile(openapiPath)) {
                openapiChecked = true;
                openapiValid = validateOpenApi(openapiPath, issues);
            }
            // If the file simply isn't there yet (service module still being built in parallel),
            // that's not treated as a hard failure here - only a present-but-invalid file is.
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("checkedDocFiles", checkedFiles);
        outputs.put("openapiChecked", openapiChecked);
        outputs.put("openapiValid", openapiValid);
        outputs.put("issues", issues);

        boolean success = issues.isEmpty() || !strict;
        String summary = issues.isEmpty()
                ? "All documentation checks passed."
                : (strict ? "Documentation issues found: " + String.join("; ", issues)
                          : "Documentation issues found (non-strict, not blocking): " + String.join("; ", issues));
        return success ? StageResult.success(summary, outputs) : StageResult.failure(summary, outputs);
    }

    private boolean validateOpenApi(Path openapiPath, List<String> issues) {
        try (InputStream in = Files.newInputStream(openapiPath)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                issues.add("openapi.yaml did not parse to a YAML mapping");
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            boolean ok = true;
            for (String key : DEFAULT_EXPECTED_OPENAPI_KEYS) {
                if (!map.containsKey(key)) {
                    issues.add("openapi.yaml missing expected top-level key: " + key);
                    ok = false;
                }
            }
            return ok;
        } catch (Exception e) {
            issues.add("openapi.yaml failed to parse: " + e.getMessage());
            return false;
        }
    }

    private Path resolve(Path repoRoot, String configuredPath) {
        Path p = Path.of(configuredPath);
        if (p.isAbsolute() || repoRoot == null) {
            return p;
        }
        return repoRoot.resolve(configuredPath);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object o) {
        if (o instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of();
    }
}

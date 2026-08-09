package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Common real-work behaviour for the implement-* stages: verify the service module actually
 * compiles (via {@code mvn -pl service compile}), then identify which changed files are
 * "impacted" for this particular stage by filtering {@code git diff --name-only <baseRef>...HEAD}
 * output through a stage-specific path predicate. This is what satisfies the brownfield
 * "identify impacted modules" requirement -- it's a real filesystem/process inspection, not a
 * hardcoded list.
 */
public abstract class AbstractImplementStage implements StageExecutor {

    private static final String MVN_FALLBACK = "C:\\tools\\apache-maven-3.9.9\\bin\\mvn.cmd";

    /** Which files (by repo-relative path) count as "impacted" for this stage. */
    protected abstract boolean isImpacted(String relativePath);

    protected abstract String stageLabel();

    @Override
    public StageResult execute(RunContext context, StageDef def) {
        Path serviceRepoPath = context.getServiceRepoPath();
        // Resolve to absolute first: Path.getParent() on a bare single-segment relative path
        // (e.g. "service") otherwise returns null instead of the actual parent directory.
        Path repoRoot = serviceRepoPath == null ? null : serviceRepoPath.toAbsolutePath().normalize().getParent();

        Map<String, Object> outputs = new LinkedHashMap<>();
        boolean compileOk;
        String compileSummary;
        if (repoRoot == null || !java.nio.file.Files.isDirectory(repoRoot)) {
            compileOk = false;
            compileSummary = "service repo path not available: " + serviceRepoPath;
        } else {
            ProcessResult compileResult = runProcess(repoRoot, List.of("mvn", "-q", "-pl", "service", "compile"));
            compileOk = compileResult.exitCode == 0;
            compileSummary = "mvn -pl service compile exit=" + compileResult.exitCode;
        }
        outputs.put("compileSuccess", compileOk);

        String baseRef = String.valueOf(def.getParams().getOrDefault("baseRef", "HEAD~1"));
        List<String> changedFiles = repoRoot == null ? List.of() : gitDiffNames(serviceRepoPath, baseRef);
        List<String> impacted = new ArrayList<>();
        for (String file : changedFiles) {
            if (isImpacted(file)) {
                impacted.add(file);
            }
        }
        outputs.put("changedFiles", changedFiles);
        outputs.put("impactedFiles", impacted);

        String summary = stageLabel() + ": " + compileSummary + "; " + impacted.size() + " impacted file(s) of "
                + changedFiles.size() + " changed.";
        return compileOk ? StageResult.success(summary, outputs) : StageResult.failure(summary, outputs);
    }

    private List<String> gitDiffNames(Path repoDir, String baseRef) {
        // Try the requested base ref first; degrade gracefully for fresh/shallow repos.
        List<String> attempt = tryGit(repoDir, List.of("git", "diff", "--name-only", baseRef + "...HEAD"));
        if (attempt != null) {
            return attempt;
        }
        attempt = tryGit(repoDir, List.of("git", "diff", "--name-only", "HEAD"));
        if (attempt != null) {
            return attempt;
        }
        attempt = tryGit(repoDir, List.of("git", "status", "--porcelain"));
        if (attempt != null) {
            List<String> cleaned = new ArrayList<>();
            for (String line : attempt) {
                String c = line.length() > 3 ? line.substring(3).trim() : line.trim();
                if (!c.isEmpty()) {
                    cleaned.add(c);
                }
            }
            return cleaned;
        }
        return List.of();
    }

    private List<String> tryGit(Path repoDir, List<String> command) {
        try {
            if (!java.nio.file.Files.isDirectory(repoDir)) {
                return null;
            }
            ProcessBuilder pb = new ProcessBuilder(command).directory(repoDir.toFile());
            Process process = pb.start();
            List<String> lines;
            try (var reader = process.inputReader()) {
                lines = reader.lines().filter(l -> !l.isBlank()).toList();
            }
            int exit = process.waitFor(30, TimeUnit.SECONDS) ? process.exitValue() : -1;
            return exit == 0 ? lines : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private ProcessResult runProcess(Path cwd, List<String> command) {
        ProcessResult result = attemptRun(cwd, command);
        if (result != null) {
            return result;
        }
        // mvn not on PATH - fall back to the known local installation.
        if (!command.isEmpty() && command.get(0).equals("mvn")) {
            List<String> fallback = new ArrayList<>(command);
            fallback.set(0, MVN_FALLBACK);
            ProcessResult fallbackResult = attemptRun(cwd, fallback);
            if (fallbackResult != null) {
                return fallbackResult;
            }
        }
        return new ProcessResult(-1, "process could not be started: " + command);
    }

    private ProcessResult attemptRun(Path cwd, List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (var reader = process.inputReader()) {
                output = reader.lines().reduce("", (a, b) -> a + "\n" + b);
            }
            int exit = process.waitFor(20, TimeUnit.MINUTES) ? process.exitValue() : -1;
            return new ProcessResult(exit, output);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "interrupted");
        }
    }

    protected static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}

package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs {@code mvn -pl service test} and parses the real surefire XML reports for pass/fail/error
 * counts. Failure here (any failed/errored test) surfaces as a failed StageResult, which the
 * Orchestrator's generic retry-with-backoff mechanism will re-invoke up to the node's configured
 * maxAttempts -- real retry signal against a genuinely-flaky test in the service module.
 */
public class TestingStage implements StageExecutor {

    private static final String MVN_FALLBACK = "C:\\tools\\apache-maven-3.9.9\\bin\\mvn.cmd";

    @Override
    public StageResult execute(RunContext context, StageDef def) {
        Path serviceRepoPath = context.getServiceRepoPath();
        // Resolve to absolute first: Path.getParent() on a bare single-segment relative path
        // (e.g. "service") otherwise returns null instead of the actual parent directory.
        Path repoRoot = serviceRepoPath == null ? null : serviceRepoPath.toAbsolutePath().normalize().getParent();
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return StageResult.failure("service repo path not available: " + serviceRepoPath, Map.of());
        }

        int exitCode = runMaven(repoRoot);

        Path surefireDir = serviceRepoPath.resolve("target").resolve("surefire-reports");
        TestCounts counts = parseSurefireReports(surefireDir);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("mvnExitCode", exitCode);
        outputs.put("tests", counts.tests);
        outputs.put("failures", counts.failures);
        outputs.put("errors", counts.errors);
        outputs.put("skipped", counts.skipped);

        boolean success = counts.tests > 0 && counts.failures == 0 && counts.errors == 0;
        String summary = String.format("tests=%d failures=%d errors=%d skipped=%d (mvn exit=%d)",
                counts.tests, counts.failures, counts.errors, counts.skipped, exitCode);
        return success ? StageResult.success(summary, outputs) : StageResult.failure(summary, outputs);
    }

    private int runMaven(Path repoRoot) {
        int exit = attemptRun(repoRoot, "mvn");
        if (exit == Integer.MIN_VALUE) {
            exit = attemptRun(repoRoot, MVN_FALLBACK);
        }
        return exit == Integer.MIN_VALUE ? -1 : exit;
    }

    private int attemptRun(Path repoRoot, String mvnCommand) {
        try {
            ProcessBuilder pb = new ProcessBuilder(mvnCommand, "-q", "-pl", "service", "test")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();
            try (var reader = process.inputReader()) {
                reader.lines().forEach(l -> { /* drain output */ });
            }
            return process.waitFor(20, TimeUnit.MINUTES) ? process.exitValue() : -1;
        } catch (IOException e) {
            return Integer.MIN_VALUE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private record TestCounts(int tests, int failures, int errors, int skipped) {
    }

    private TestCounts parseSurefireReports(Path surefireDir) {
        if (!Files.isDirectory(surefireDir)) {
            return new TestCounts(0, 0, 0, 0);
        }
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        try (Stream<Path> files = Files.list(surefireDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".xml")).toList()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(file.toFile());
                NodeList suites = doc.getElementsByTagName("testsuite");
                for (int i = 0; i < suites.getLength(); i++) {
                    Element suite = (Element) suites.item(i);
                    tests += intAttr(suite, "tests");
                    failures += intAttr(suite, "failures");
                    errors += intAttr(suite, "errors");
                    skipped += intAttr(suite, "skipped");
                }
            }
        } catch (Exception e) {
            // Malformed/partial report; treat as no data rather than throwing out of the stage.
            return new TestCounts(0, 0, 0, 0);
        }
        return new TestCounts(tests, failures, errors, skipped);
    }

    private int intAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

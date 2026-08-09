package com.urlshortener.orchestrator.cli;

import com.urlshortener.orchestrator.approvals.ApprovalGate;
import com.urlshortener.orchestrator.audit.AuditLogger;
import com.urlshortener.orchestrator.engine.Graph;
import com.urlshortener.orchestrator.engine.Orchestrator;
import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageState;
import com.urlshortener.orchestrator.metrics.Metrics;
import com.urlshortener.orchestrator.metrics.MetricsCalculator;
import com.urlshortener.orchestrator.policy.PolicyEngine;
import com.urlshortener.orchestrator.reporting.ReportGenerator;
import com.urlshortener.orchestrator.stages.ClarifyRequirementsStage;
import com.urlshortener.orchestrator.stages.DesignStage;
import com.urlshortener.orchestrator.stages.DocumentationStage;
import com.urlshortener.orchestrator.stages.ImplementAnalyticsStage;
import com.urlshortener.orchestrator.stages.ImplementApiStage;
import com.urlshortener.orchestrator.stages.ImplementReliabilityStage;
import com.urlshortener.orchestrator.stages.ReleaseReadinessStage;
import com.urlshortener.orchestrator.stages.RequirementsStage;
import com.urlshortener.orchestrator.stages.StageExecutor;
import com.urlshortener.orchestrator.stages.TestingStage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plain CLI entry point:
 * <pre>
 *   run-scenario --graph &lt;path&gt; --requirements-input &lt;path&gt; --approvals &lt;path&gt; --run-dir &lt;path&gt; [--interactive] [--service-repo &lt;path&gt;]
 *   metrics --run &lt;run-dir&gt;
 *   report  --run &lt;run-dir&gt;
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsageAndExit();
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        try {
            switch (command) {
                case "run-scenario" -> runScenario(options);
                case "metrics" -> printMetrics(options);
                case "report" -> printReport(options);
                default -> printUsageAndExit();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runScenario(Map<String, String> options) throws IOException {
        String graphPath = require(options, "graph");
        String requirementsInput = options.get("requirements-input");
        String approvalsPath = options.get("approvals");
        String runDir = require(options, "run-dir");
        boolean interactive = options.containsKey("interactive");
        String serviceRepoOpt = options.getOrDefault("service-repo", "service");

        Path runDirPath = Path.of(runDir);
        Files.createDirectories(runDirPath);
        Path auditFile = runDirPath.resolve("audit.jsonl");

        Graph graph = Graph.fromYamlFile(Path.of(graphPath));
        String runId = UUID.randomUUID().toString();
        String scenarioName = Path.of(graphPath).getFileName().toString();

        Map<String, Object> inputs = new LinkedHashMap<>();
        if (requirementsInput != null) {
            inputs.put("requirementsInputPath", requirementsInput);
        }

        RunContext context = new RunContext(runId, scenarioName, Path.of(serviceRepoOpt), inputs);

        AuditLogger auditLogger = new AuditLogger(auditFile);
        PolicyEngine policyEngine = PolicyEngine.fromYamlResource("policy/policy.yaml");
        ApprovalGate approvalGate = interactive
                ? ApprovalGate.interactive()
                : ApprovalGate.replay(Path.of(require(options, "approvals")));

        Map<String, StageExecutor> executors = defaultExecutors();

        Orchestrator orchestrator = new Orchestrator(graph, context, auditLogger, policyEngine, approvalGate, executors);
        System.out.println("Running scenario '" + scenarioName + "' (runId=" + runId + ") ...");
        RunContext finished = orchestrator.run();
        auditLogger.close();

        System.out.println("\nFinal stage states:");
        for (String name : graph.nodeNames()) {
            System.out.printf("  %-24s %s%n", name, finished.getState(name));
        }
        System.out.println("\nAudit log: " + auditFile.toAbsolutePath());

        boolean anyFailure = finished.getStates().values().stream()
                .anyMatch(s -> s == StageState.FAILED || s == StageState.ROLLED_BACK);
        if (anyFailure) {
            System.out.println("\nRun completed with failures.");
        } else {
            System.out.println("\nRun completed successfully.");
        }
    }

    private static void printMetrics(Map<String, String> options) {
        Path runDir = Path.of(require(options, "run"));
        Path auditFile = runDir.resolve("audit.jsonl");
        Metrics metrics = MetricsCalculator.calculate(auditFile);
        System.out.println(metrics.toTable());
    }

    private static void printReport(Map<String, String> options) throws IOException {
        Path runDir = Path.of(require(options, "run"));
        Path auditFile = runDir.resolve("audit.jsonl");
        String report = ReportGenerator.generate(auditFile, runDir.getFileName().toString());
        System.out.println(report);
        Path reportFile = runDir.resolve("report.md");
        Files.writeString(reportFile, report);
        System.err.println("(also written to " + reportFile.toAbsolutePath() + ")");
    }

    private static Map<String, StageExecutor> defaultExecutors() {
        Map<String, StageExecutor> executors = new LinkedHashMap<>();
        executors.put("requirements", new RequirementsStage());
        executors.put("clarify-requirements", new ClarifyRequirementsStage());
        executors.put("design", new DesignStage());
        executors.put("implement-api", new ImplementApiStage());
        executors.put("implement-analytics", new ImplementAnalyticsStage());
        executors.put("implement-reliability", new ImplementReliabilityStage());
        executors.put("testing", new TestingStage());
        executors.put("documentation", new DocumentationStage());
        executors.put("release-readiness", new ReleaseReadinessStage());
        return executors;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    options.put(key, args[i + 1]);
                    i++;
                } else {
                    options.put(key, "true");
                }
            }
        }
        return options;
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required --" + key + " argument");
        }
        return value;
    }

    private static void printUsageAndExit() {
        System.out.println("""
                Usage:
                  run-scenario --graph <path> --requirements-input <path> --approvals <path> --run-dir <path> [--interactive] [--service-repo <path>]
                  metrics --run <run-dir>
                  report  --run <run-dir>
                """);
        System.exit(1);
    }
}

package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.audit.AuditLogger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutable, thread-safe state shared across all stages of a single orchestration run.
 * Populated/updated concurrently by stage-executor worker threads.
 */
public class RunContext {

    private final String runId;
    private final String scenarioName;
    private final Path serviceRepoPath;
    private final Map<String, Object> inputs;
    private final Map<String, Map<String, Object>> outputs = new ConcurrentHashMap<>();
    private final List<DecisionRecord> decisions = new CopyOnWriteArrayList<>();
    private final Map<String, StageState> states = new ConcurrentHashMap<>();
    /** decisionId -> granted, populated by ApprovalGate as decisions are made. */
    private final Map<String, Boolean> approvals = new ConcurrentHashMap<>();

    /** Wired by the Orchestrator after construction so stage executors can perform real re-planning / logging. */
    private volatile AuditLogger auditLogger;
    private volatile Replanner replanner;
    private volatile Graph graph;

    public RunContext(String runId, String scenarioName, Path serviceRepoPath, Map<String, Object> inputs) {
        this.runId = runId;
        this.scenarioName = scenarioName;
        this.serviceRepoPath = serviceRepoPath;
        this.inputs = new ConcurrentHashMap<>(inputs == null ? Map.of() : inputs);
    }

    public String getRunId() {
        return runId;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public Path getServiceRepoPath() {
        return serviceRepoPath;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public Map<String, Map<String, Object>> getOutputs() {
        return outputs;
    }

    public void putOutput(String node, Map<String, Object> output) {
        outputs.put(node, output);
    }

    public List<DecisionRecord> getDecisions() {
        return decisions;
    }

    public void recordDecision(DecisionRecord decision) {
        decisions.add(decision);
    }

    public Map<String, StageState> getStates() {
        return states;
    }

    public StageState getState(String node) {
        return states.get(node);
    }

    public Map<String, Boolean> getApprovals() {
        return approvals;
    }

    public boolean isApproved(String decisionId) {
        return Boolean.TRUE.equals(approvals.get(decisionId));
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public Replanner getReplanner() {
        return replanner;
    }

    public void setReplanner(Replanner replanner) {
        this.replanner = replanner;
    }

    public Graph getGraph() {
        return graph;
    }

    public void setGraph(Graph graph) {
        this.graph = graph;
    }
}

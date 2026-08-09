package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.approvals.ApprovalDecision;
import com.urlshortener.orchestrator.approvals.ApprovalGate;
import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLogger;
import com.urlshortener.orchestrator.policy.PolicyEngine;
import com.urlshortener.orchestrator.stages.StageExecutor;
import com.urlshortener.orchestrator.stages.StageResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The scheduler: owns the graph, run context, audit logger, policy engine and approval gate.
 * Repeatedly promotes PENDING/STALE nodes whose dependencies are satisfied to READY and runs all
 * currently-READY nodes concurrently on a bounded thread pool -- genuine parallel fan-out/fan-in,
 * not a simulated one. Handles retries with real backoff, rollback + downstream cascade + safe
 * stop on unrecoverable failure, and approval/policy gating around each node.
 */
public class Orchestrator {

    private static final int DEFAULT_POOL_SIZE = 8;

    private final Graph graph;
    private final RunContext context;
    private final AuditLogger auditLogger;
    private final PolicyEngine policyEngine;
    private final ApprovalGate approvalGate;
    private final Map<String, StageExecutor> executors;
    private final int poolSize;
    private final AtomicBoolean haltNewWork = new AtomicBoolean(false);

    public Orchestrator(Graph graph, RunContext context, AuditLogger auditLogger, PolicyEngine policyEngine,
                         ApprovalGate approvalGate, Map<String, StageExecutor> executors) {
        this(graph, context, auditLogger, policyEngine, approvalGate, executors, DEFAULT_POOL_SIZE);
    }

    public Orchestrator(Graph graph, RunContext context, AuditLogger auditLogger, PolicyEngine policyEngine,
                         ApprovalGate approvalGate, Map<String, StageExecutor> executors, int poolSize) {
        this.graph = graph;
        this.context = context;
        this.auditLogger = auditLogger;
        this.policyEngine = policyEngine;
        this.approvalGate = approvalGate;
        this.executors = executors;
        this.poolSize = poolSize;
    }

    public RunContext getContext() {
        return context;
    }

    public Graph getGraph() {
        return graph;
    }

    /** Runs the full graph to completion (or safe-stop on unrecoverable failure) and returns the final context. */
    public RunContext run() {
        context.setAuditLogger(auditLogger);
        context.setReplanner(new Replanner(graph));
        context.setGraph(graph);

        for (String name : graph.nodeNames()) {
            context.getStates().putIfAbsent(name, StageState.PENDING);
        }

        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        ExecutorCompletionService<String> completionService = new ExecutorCompletionService<>(pool);
        int inFlight = 0;
        try {
            while (true) {
                if (!haltNewWork.get()) {
                    for (StageDef node : graph.allNodes()) {
                        String name = node.getName();
                        StageState state = context.getState(name);
                        if ((state == StageState.PENDING || state == StageState.STALE) && depsSatisfied(node)) {
                            context.getStates().put(name, StageState.READY);
                            completionService.submit(() -> {
                                runNode(node);
                                return name;
                            });
                            inFlight++;
                        }
                    }
                }
                if (inFlight == 0) {
                    break;
                }
                Future<String> completed = completionService.take();
                inFlight--;
                consumeQuietly(completed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
        return context;
    }

    private void consumeQuietly(Future<String> future) {
        try {
            future.get();
        } catch (Exception ignored) {
            // Node-level failures are handled and recorded inside runNode(); nothing to propagate here.
        }
    }

    private boolean depsSatisfied(StageDef node) {
        for (String dep : node.getDependsOn()) {
            StageState depState = context.getState(dep);
            if (depState == null || !depState.satisfiesDependency()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- node lifecycle

    private void runNode(StageDef node) {
        String name = node.getName();
        long nodeStart = System.currentTimeMillis();
        transition(name, StageState.RUNNING, "system", "stage started", null, null);

        try {
            if (node.isRequiresApproval() && node.getApprovalPoint() == ApprovalPoint.ENTRY) {
                transition(name, StageState.AWAITING_APPROVAL, "system", "awaiting entry approval", null, null);
                ApprovalDecision decision = approvalGate.requestApproval(context, node, node.getApprovalDecisionId(),
                        "Entry approval required for stage '" + name + "'");
                if (!decision.approved()) {
                    failNode(node, "entry approval denied: " + decision.comment(), nodeStart, false);
                    return;
                }
                transition(name, StageState.RUNNING, "system", "entry approval granted", null, null);
            }

            PolicyEngine.CheckOutcome preCheck = policyEngine.check(context, node);
            if (!preCheck.allowed()) {
                String reason = String.join("; ", preCheck.reasons());
                auditLog(AuditEventType.POLICY_VIOLATION, name, context.getState(name), StageState.FAILED,
                        "system", reason, null, null);
                failNode(node, "policy violation: " + reason, nodeStart, false);
                return;
            }

            StageExecutor executor = executors.get(node.getExecutor());
            if (executor == null) {
                failNode(node, "no StageExecutor registered for key '" + node.getExecutor() + "'", nodeStart, false);
                return;
            }

            RetryPolicyDef retryPolicy = node.getRetryPolicy();
            StageResult result = null;
            Exception lastException = null;
            int maxAttempts = retryPolicy.maxAttempts();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                lastException = null;
                try {
                    result = executor.execute(context, node);
                } catch (Exception e) {
                    lastException = e;
                    result = null;
                }
                boolean succeededThisAttempt = result != null && result.isSuccess();
                boolean lastAttempt = attempt == maxAttempts;
                if (succeededThisAttempt || lastAttempt) {
                    break;
                }
                double backoffSeconds = retryPolicy.backoffSecondsForAttempt(attempt);
                String failReason = lastException != null
                        ? String.valueOf(lastException.getMessage())
                        : (result != null ? result.getSummary() : "unknown failure");
                auditLog(AuditEventType.RETRY, name, StageState.RUNNING, StageState.RUNNING, "system",
                        "attempt " + attempt + "/" + maxAttempts + " failed: " + failReason + "; retrying after " + backoffSeconds + "s",
                        Map.of("attempt", attempt, "backoffSeconds", backoffSeconds), null);
                sleepSeconds(backoffSeconds);
            }

            boolean succeeded = result != null && result.isSuccess();
            if (!succeeded) {
                String primaryReason = lastException != null
                        ? "exception: " + lastException
                        : (result != null ? result.getSummary() : "stage failed with no result");

                if (node.getFallbackExecutor() != null) {
                    StageResult fallbackResult = attemptFallback(node, primaryReason);
                    if (fallbackResult != null && fallbackResult.isSuccess()) {
                        succeedViaFallback(node, fallbackResult, primaryReason, nodeStart);
                        return;
                    }
                    primaryReason = primaryReason + "; fallback '" + node.getFallbackExecutor() + "' also failed: "
                            + (fallbackResult != null ? fallbackResult.getSummary() : "threw or was unregistered");
                }

                failNode(node, primaryReason, nodeStart, true);
                return;
            }

            if (result.getOutputs() != null) {
                context.putOutput(name, result.getOutputs());
            }

            if (node.isRequiresApproval() && node.getApprovalPoint() == ApprovalPoint.EXIT) {
                transition(name, StageState.AWAITING_APPROVAL, "system", "awaiting exit approval", null, null);
                ApprovalDecision decision = approvalGate.requestApproval(context, node, node.getApprovalDecisionId(),
                        "Exit approval required for stage '" + name + "'");
                if (!decision.approved()) {
                    failNode(node, "exit approval denied: " + decision.comment(), nodeStart, false);
                    return;
                }
            }

            long durationMs = System.currentTimeMillis() - nodeStart;
            transition(name, StageState.SUCCEEDED, "system", result.getSummary(), null, durationMs);
        } catch (RuntimeException e) {
            failNode(node, "unexpected error: " + e, nodeStart, false);
        }
    }

    /**
     * Runs the configured fallback executor once (no retries of its own) after the primary
     * executor's retry budget is exhausted. Distinct from rollback (which undoes state after a
     * failure) and from retry (which re-attempts the same executor) - this attempts a different,
     * degraded-but-acceptable executor instead of failing outright.
     */
    private StageResult attemptFallback(StageDef node, String primaryReason) {
        String fallbackKey = node.getFallbackExecutor();
        StageExecutor fallback = executors.get(fallbackKey);
        if (fallback == null) {
            auditLog(AuditEventType.FALLBACK, node.getName(), StageState.RUNNING, StageState.RUNNING, "system",
                    "primary retries exhausted (" + primaryReason + "); fallback '" + fallbackKey
                            + "' not attempted - no StageExecutor registered for that key",
                    Map.of("fallbackExecutor", fallbackKey, "fallbackSucceeded", false), null);
            return null;
        }
        StageResult result;
        boolean ok;
        String outcome;
        try {
            result = fallback.execute(context, node);
            ok = result != null && result.isSuccess();
            outcome = ok ? "succeeded" : "failed: " + result.getSummary();
        } catch (Exception e) {
            result = null;
            ok = false;
            outcome = "threw: " + e;
        }
        auditLog(AuditEventType.FALLBACK, node.getName(), StageState.RUNNING, StageState.RUNNING, "system",
                "primary retries exhausted (" + primaryReason + "); fallback '" + fallbackKey + "' " + outcome,
                Map.of("fallbackExecutor", fallbackKey, "fallbackSucceeded", ok), null);
        return result;
    }

    private void succeedViaFallback(StageDef node, StageResult fallbackResult, String primaryReason, long nodeStart) {
        String name = node.getName();
        Map<String, Object> outputs = new LinkedHashMap<>(
                fallbackResult.getOutputs() == null ? Map.of() : fallbackResult.getOutputs());
        outputs.put("viaFallback", true);
        outputs.put("primaryFailureReason", primaryReason);
        context.putOutput(name, outputs);

        if (node.isRequiresApproval() && node.getApprovalPoint() == ApprovalPoint.EXIT) {
            transition(name, StageState.AWAITING_APPROVAL, "system", "awaiting exit approval (completed via fallback)", null, null);
            ApprovalDecision decision = approvalGate.requestApproval(context, node, node.getApprovalDecisionId(),
                    "Exit approval required for stage '" + name + "' (completed via fallback)");
            if (!decision.approved()) {
                failNode(node, "exit approval denied: " + decision.comment(), nodeStart, false);
                return;
            }
        }

        long durationMs = System.currentTimeMillis() - nodeStart;
        transition(name, StageState.SUCCEEDED, "system",
                "primary failed (" + primaryReason + "); recovered via fallback: " + fallbackResult.getSummary(),
                null, durationMs);
    }

    private void failNode(StageDef node, String reason, long nodeStart, boolean callRollback) {
        String name = node.getName();
        long durationMs = System.currentTimeMillis() - nodeStart;
        transition(name, StageState.FAILED, "system", reason, null, durationMs);

        if (callRollback) {
            StageExecutor executor = executors.get(node.getExecutor());
            if (executor != null) {
                try {
                    executor.rollback(context, node);
                    auditLog(AuditEventType.ROLLBACK, name, StageState.FAILED, StageState.FAILED, "system",
                            "rollback executed for '" + name + "'", null, null);
                } catch (Exception e) {
                    auditLog(AuditEventType.ROLLBACK, name, StageState.FAILED, StageState.FAILED, "system",
                            "rollback threw: " + e, null, null);
                }
            }
        }

        haltNewWork.set(true);
        cascadeFail(name);
    }

    /** Marks every transitive downstream dependent of {@code failedNode} ROLLED_BACK (safe-stop: no new work scheduled). */
    private void cascadeFail(String failedNode) {
        for (String downstream : graph.allDownstream(failedNode)) {
            StageState prev = context.getState(downstream);
            if (prev == null || !prev.isTerminal()) {
                transition(downstream, StageState.ROLLED_BACK, "system",
                        "upstream dependency '" + failedNode + "' failed; safe-stop", null, null);
            }
        }
    }

    private void transition(String name, StageState to, String actor, String reason, Map<String, Object> data, Long durationMs) {
        StageState from = context.getState(name);
        context.getStates().put(name, to);
        auditLog(AuditEventType.STAGE_TRANSITION, name, from, to, actor, reason, data, durationMs);
    }

    private void auditLog(AuditEventType type, String node, StageState from, StageState to, String actor,
                           String reason, Map<String, Object> data, Long durationMs) {
        if (auditLogger == null) {
            return;
        }
        auditLogger.log(new AuditEvent(Instant.now(), context.getRunId(), type, node, from, to, actor, reason,
                data == null ? Map.of() : data, durationMs));
    }

    private void sleepSeconds(double seconds) {
        try {
            Thread.sleep((long) Math.max(0, seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

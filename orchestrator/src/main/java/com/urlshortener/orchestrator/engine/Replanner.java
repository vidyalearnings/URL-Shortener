package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLogger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Real, callable dynamic re-planning support: insert new nodes into a live graph and cascade
 * staleness to downstream dependents when upstream outputs change. Invoked directly by stage
 * executors (e.g. {@code RequirementsStage} when it detects ambiguity), not just described.
 */
public class Replanner {

    private final Graph graph;

    public Replanner(Graph graph) {
        this.graph = graph;
    }

    public Graph getGraph() {
        return graph;
    }

    /**
     * Adds {@code newNode} to the live graph and registers it as PENDING in the run's state map.
     */
    public void insertNode(RunContext context, StageDef newNode) {
        graph.addNode(newNode);
        context.getStates().put(newNode.getName(), StageState.PENDING);
        logAudit(context, AuditEventType.NODE_INSERTED, newNode.getName(), null, StageState.PENDING,
                "Node '" + newNode.getName() + "' inserted at runtime, dependsOn=" + newNode.getDependsOn(),
                Map.of("dependsOn", newNode.getDependsOn(), "executor", newNode.getExecutor()));
    }

    /**
     * Rewires {@code nodeToGate} so it additionally depends on {@code gatingNode} (which must
     * already exist in the graph, typically just inserted via {@link #insertNode}), then cascades
     * STALE to {@code nodeToGate} and everything downstream of it so the engine knows they must
     * (re)run before the graph can complete.
     */
    public void gateNodeOn(RunContext context, String nodeToGate, String gatingNode) {
        graph.addDependency(nodeToGate, gatingNode);
        logAudit(context, AuditEventType.REPLAN, nodeToGate, context.getState(nodeToGate), StageState.STALE,
                "Node '" + nodeToGate + "' rewired to depend on newly-inserted node '" + gatingNode + "'",
                Map.of("newDependency", gatingNode));
        markStale(context, nodeToGate);
    }

    /**
     * Marks {@code nodeName} and every transitive downstream dependent as STALE, meaning they
     * must (re)run before the graph can be considered complete. Safe to call on a node that
     * hasn't started yet (e.g. still PENDING) or one that already succeeded.
     */
    public void markStale(RunContext context, String nodeName) {
        if (!graph.hasNode(nodeName)) {
            throw new IllegalArgumentException("Unknown node: " + nodeName);
        }
        StageState previous = context.getState(nodeName);
        context.getStates().put(nodeName, StageState.STALE);
        logAudit(context, AuditEventType.NODE_STALE, nodeName, previous, StageState.STALE,
                "Node '" + nodeName + "' marked STALE due to upstream re-plan", Map.of());

        for (String downstream : graph.allDownstream(nodeName)) {
            StageState prev = context.getState(downstream);
            if (prev != null && prev.isTerminal() && prev != StageState.STALE) {
                context.getStates().put(downstream, StageState.STALE);
                logAudit(context, AuditEventType.NODE_STALE, downstream, prev, StageState.STALE,
                        "Node '" + downstream + "' cascaded to STALE because upstream '" + nodeName + "' was re-planned",
                        Map.of("cascadedFrom", nodeName));
            } else if (prev == StageState.PENDING || prev == null) {
                context.getStates().put(downstream, StageState.STALE);
            }
        }
    }

    private void logAudit(RunContext context, AuditEventType type, String node, StageState from, StageState to,
                           String reason, Map<String, Object> data) {
        AuditLogger logger = context.getAuditLogger();
        if (logger == null) {
            return;
        }
        logger.log(new AuditEvent(Instant.now(), context.getRunId(), type, node, from, to, "system", reason, data, null));
    }

    List<String> downstreamOf(String node) {
        return graph.allDownstream(node);
    }
}

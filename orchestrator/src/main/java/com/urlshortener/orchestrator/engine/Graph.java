package com.urlshortener.orchestrator.engine;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stage dependency graph: a DAG of {@link StageDef} nodes, loaded from YAML (or built
 * programmatically for tests). Supports runtime mutation (node insertion, dependency rewiring)
 * so {@link Replanner} can perform genuine dynamic re-planning.
 */
public class Graph {

    private final Map<String, StageDef> nodes = new ConcurrentHashMap<>();
    /** Preserves YAML declaration order for readable iteration / reporting. */
    private final List<String> insertionOrder = java.util.Collections.synchronizedList(new ArrayList<>());

    public Graph(List<StageDef> initialNodes) {
        // Add all nodes first (without per-node validation), then validate once: unlike runtime
        // addNode() calls, a batch of nodes may legitimately reference each other regardless of
        // list order (e.g. node A appearing before the node it depends on).
        for (StageDef def : initialNodes) {
            if (nodes.containsKey(def.getName())) {
                throw new IllegalStateException("Duplicate node name in graph: " + def.getName());
            }
            nodes.put(def.getName(), def);
            insertionOrder.add(def.getName());
        }
        validateDag();
    }

    // ---------------------------------------------------------------- loading

    public static Graph fromYamlFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return fromYamlStream(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read graph YAML: " + path, e);
        }
    }

    public static Graph fromYamlResource(String resourcePath) {
        try (InputStream in = Graph.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Graph resource not found: " + resourcePath);
            }
            return fromYamlStream(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read graph resource: " + resourcePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Graph fromYamlStream(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);
        if (root == null || !root.containsKey("nodes")) {
            throw new IllegalArgumentException("Graph YAML must contain a top-level 'nodes' list");
        }
        List<Map<String, Object>> rawNodes = (List<Map<String, Object>>) root.get("nodes");
        List<StageDef> defs = new ArrayList<>();
        for (Map<String, Object> raw : rawNodes) {
            defs.add(parseNode(raw));
        }
        return new Graph(defs);
    }

    @SuppressWarnings("unchecked")
    private static StageDef parseNode(Map<String, Object> raw) {
        String name = requireString(raw, "name");
        List<String> dependsOn = new ArrayList<>();
        Object dep = raw.get("dependsOn");
        if (dep instanceof List<?> list) {
            for (Object o : list) {
                dependsOn.add(String.valueOf(o));
            }
        }
        String executor = requireString(raw, "executor");

        RetryPolicyDef retryPolicy = RetryPolicyDef.defaults();
        Object rp = raw.get("retryPolicy");
        if (rp instanceof Map<?, ?> rpMap) {
            int maxAttempts = intOr(rpMap.get("maxAttempts"), RetryPolicyDef.DEFAULT_MAX_ATTEMPTS);
            double base = doubleOr(rpMap.get("backoffBaseSeconds"), RetryPolicyDef.DEFAULT_BACKOFF_BASE_SECONDS);
            double mult = doubleOr(rpMap.get("backoffMultiplier"), RetryPolicyDef.DEFAULT_BACKOFF_MULTIPLIER);
            retryPolicy = new RetryPolicyDef(maxAttempts, base, mult);
        }

        boolean requiresApproval = Boolean.TRUE.equals(raw.get("requiresApproval"));
        ApprovalPoint approvalPoint = null;
        String approvalDecisionId = null;
        if (requiresApproval) {
            Object apRaw = raw.get("approvalPoint");
            if (apRaw == null) {
                throw new IllegalArgumentException("Node '" + name + "' requiresApproval but has no approvalPoint");
            }
            approvalPoint = ApprovalPoint.valueOf(String.valueOf(apRaw).trim().toUpperCase());
            Object decisionIdRaw = raw.get("approvalDecisionId");
            approvalDecisionId = decisionIdRaw == null ? name : String.valueOf(decisionIdRaw);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        Object p = raw.get("params");
        if (p instanceof Map<?, ?> pMap) {
            for (Map.Entry<?, ?> e : pMap.entrySet()) {
                params.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        Object fallbackRaw = raw.get("fallback");
        String fallbackExecutor = fallbackRaw == null ? null : String.valueOf(fallbackRaw);

        return new StageDef(name, dependsOn, executor, retryPolicy, requiresApproval, approvalPoint, approvalDecisionId,
                params, fallbackExecutor);
    }

    private static String requireString(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Graph node missing required field '" + key + "': " + raw);
        }
        return String.valueOf(v);
    }

    private static int intOr(Object v, int fallback) {
        return v == null ? fallback : Integer.parseInt(String.valueOf(v));
    }

    private static double doubleOr(Object v, double fallback) {
        return v == null ? fallback : Double.parseDouble(String.valueOf(v));
    }

    // ---------------------------------------------------------------- mutation (runtime replanning)

    /** Adds a new node to the live graph. Re-validates the DAG invariant afterwards. */
    public synchronized void addNode(StageDef def) {
        if (nodes.containsKey(def.getName())) {
            throw new IllegalStateException("Node already exists in graph: " + def.getName());
        }
        nodes.put(def.getName(), def);
        insertionOrder.add(def.getName());
        validateDag();
    }

    /** Adds an edge such that {@code node} now additionally depends on {@code newDependency}. */
    public synchronized void addDependency(String node, String newDependency) {
        StageDef def = nodes.get(node);
        if (def == null) {
            throw new IllegalArgumentException("Unknown node: " + node);
        }
        if (!nodes.containsKey(newDependency)) {
            throw new IllegalArgumentException("Unknown dependency node: " + newDependency);
        }
        def.addDependency(newDependency);
        validateDag();
    }

    // ---------------------------------------------------------------- lookups

    public StageDef getNode(String name) {
        return nodes.get(name);
    }

    public boolean hasNode(String name) {
        return nodes.containsKey(name);
    }

    /** Snapshot of all node names in declaration/insertion order. */
    public List<String> nodeNames() {
        synchronized (insertionOrder) {
            return new ArrayList<>(insertionOrder);
        }
    }

    /** Snapshot list of all node definitions (safe to iterate even if the graph mutates concurrently). */
    public List<StageDef> allNodes() {
        List<StageDef> result = new ArrayList<>();
        for (String name : nodeNames()) {
            StageDef def = nodes.get(name);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }

    public int size() {
        return nodes.size();
    }

    /** Direct dependents of {@code node} (nodes whose dependsOn contains it). */
    public List<String> directDependents(String node) {
        List<String> result = new ArrayList<>();
        for (StageDef def : allNodes()) {
            if (def.getDependsOn().contains(node)) {
                result.add(def.getName());
            }
        }
        return result;
    }

    /** All transitive downstream dependents of {@code node} (BFS), not including {@code node} itself. */
    public List<String> allDownstream(String node) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(directDependents(node));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (visited.add(next)) {
                result.add(next);
                queue.addAll(directDependents(next));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- validation

    private enum Mark { UNVISITED, IN_PROGRESS, DONE }

    /** Simple DFS-based cycle detection; throws if the graph is not a DAG or references unknown nodes. */
    public void validateDag() {
        Map<String, Mark> marks = new HashMap<>();
        for (String name : nodeNames()) {
            marks.put(name, Mark.UNVISITED);
        }
        for (String name : nodeNames()) {
            if (marks.get(name) == Mark.UNVISITED) {
                dfsCheck(name, marks, new ArrayDeque<>());
            }
        }
    }

    private void dfsCheck(String name, Map<String, Mark> marks, Deque<String> stack) {
        marks.put(name, Mark.IN_PROGRESS);
        stack.push(name);
        StageDef def = nodes.get(name);
        if (def == null) {
            throw new IllegalStateException("Graph references unknown node: " + name);
        }
        for (String dep : def.getDependsOn()) {
            if (!nodes.containsKey(dep)) {
                throw new IllegalArgumentException("Node '" + name + "' depends on unknown node '" + dep + "'");
            }
            Mark depMark = marks.get(dep);
            if (depMark == Mark.IN_PROGRESS) {
                stack.push(dep);
                throw new IllegalStateException("Cycle detected in stage graph: " + describeCycle(stack));
            } else if (depMark == Mark.UNVISITED) {
                dfsCheck(dep, marks, stack);
            }
        }
        stack.pop();
        marks.put(name, Mark.DONE);
    }

    private String describeCycle(Deque<String> stack) {
        List<String> list = new ArrayList<>(stack);
        java.util.Collections.reverse(list);
        return String.join(" -> ", list);
    }
}

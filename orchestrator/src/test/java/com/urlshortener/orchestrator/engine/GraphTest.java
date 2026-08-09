package com.urlshortener.orchestrator.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphTest {

    @Test
    void loadsValidGraphFromYamlResource() {
        Graph graph = Graph.fromYamlResource("graphs/valid-graph.yaml");
        assertEquals(4, graph.size());
        assertEquals(List.of("a"), graph.getNode("b").getDependsOn());
        assertEquals(List.of("b", "c"), graph.getNode("d").getDependsOn());
    }

    @Test
    void loadsGraphFromYamlFileOnDisk() {
        Path path = Path.of("src", "test", "resources", "graphs", "valid-graph.yaml");
        Graph graph = Graph.fromYamlFile(path);
        assertEquals(4, graph.size());
    }

    @Test
    void resolvesDirectAndTransitiveDependents() {
        Graph graph = Graph.fromYamlResource("graphs/valid-graph.yaml");
        assertEquals(List.of("b", "c"), graph.directDependents("a"));
        List<String> downstream = graph.allDownstream("a");
        assertTrue(downstream.containsAll(List.of("b", "c", "d")));
    }

    @Test
    void rejectsCyclicGraph() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Graph.fromYamlResource("graphs/cyclic-graph.yaml"));
        assertTrue(ex.getMessage().toLowerCase().contains("cycle"), "expected cycle message, got: " + ex.getMessage());
    }

    @Test
    void addingDependencyThatCreatesACycleIsRejected() {
        Graph graph = Graph.fromYamlResource("graphs/valid-graph.yaml");
        // 'a' has no deps; making it depend on 'd' (which transitively depends on 'a') is a cycle.
        assertThrows(IllegalStateException.class, () -> graph.addDependency("a", "d"));
    }

    @Test
    void addNodeAtRuntimeIsReflectedInLookups() {
        Graph graph = Graph.fromYamlResource("graphs/valid-graph.yaml");
        StageDef newNode = new StageDef("e", List.of("d"), "noop", null, false, null, null, null);
        graph.addNode(newNode);
        assertTrue(graph.hasNode("e"));
        assertEquals(List.of("e"), graph.directDependents("d"));
    }

    @Test
    void supportsTrimmedGraphsThatOmitSomeStages() {
        Graph graph = Graph.fromYamlResource("graphs/graph-brownfield.yaml");
        assertFalse(graph.hasNode("requirements"));
        assertFalse(graph.hasNode("design"));
        assertTrue(graph.hasNode("implement-reliability"));
        assertEquals(List.of(), graph.getNode("implement-reliability").getDependsOn());
    }
}

package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.Graph;
import com.urlshortener.orchestrator.engine.Replanner;
import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import com.urlshortener.orchestrator.engine.StageState;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementsStageTest {

    private final RequirementsStage stage = new RequirementsStage();

    private Path resource(String name) throws URISyntaxException {
        URL url = getClass().getClassLoader().getResource(name);
        return Path.of(url.toURI());
    }

    // -------------------------------------------------------------- pure ambiguity scanning

    @Test
    void flagsVagueUnquantifiedTerms() {
        String text = "The system should be fast and scalable. Some details are TBD.";
        List<Map<String, Object>> hits = stage.scanForAmbiguities(text, RequirementsStage.DEFAULT_VAGUE_TERMS);
        assertFalse(hits.isEmpty());
        List<String> terms = hits.stream().map(h -> String.valueOf(h.get("term"))).toList();
        assertTrue(terms.contains("should"));
        assertTrue(terms.contains("fast"));
        assertTrue(terms.contains("scalable"));
        assertTrue(terms.contains("tbd"));
    }

    @Test
    void wellSpecifiedTextIsNotFlagged() {
        String text = "The system shall respond within 200ms at p99 for up to 1000 requests/second.";
        List<Map<String, Object>> hits = stage.scanForAmbiguities(text, RequirementsStage.DEFAULT_VAGUE_TERMS);
        assertTrue(hits.isEmpty(), "expected no ambiguity hits, got: " + hits);
    }

    // -------------------------------------------------------------- full execute() + live re-planning

    private RunContext buildContextWithGraph(Graph graph, String requirementsInputResource) throws Exception {
        RunContext context = new RunContext(UUID.randomUUID().toString(), "requirements-test", Path.of("."),
                Map.of("requirementsInputPath", resource(requirementsInputResource).toString()));
        context.setGraph(graph);
        context.setReplanner(new Replanner(graph));
        return context;
    }

    private Graph buildRequirementsDesignImplementGraph() {
        StageDef requirements = new StageDef("requirements", List.of(), "requirements", null, false, null, null, null);
        StageDef design = new StageDef("design", List.of("requirements"), "design", null, false, null, null, null);
        StageDef implement = new StageDef("implement", List.of("design"), "implement", null, false, null, null, null);
        return new Graph(List.of(requirements, design, implement));
    }

    @Test
    void ambiguousRequirementsInsertClarifyNodeAndCascadeStaleDownstream() throws Exception {
        Graph graph = buildRequirementsDesignImplementGraph();
        RunContext context = buildContextWithGraph(graph, "requirements/vague-requirements.md");
        StageDef requirementsDef = graph.getNode("requirements");

        StageResult result = stage.execute(context, requirementsDef);

        assertTrue(result.isSuccess());
        assertEquals(Boolean.TRUE, result.getOutputs().get("flagged"));

        assertTrue(graph.hasNode("clarify-requirements"), "expected synthetic clarify-requirements node to be inserted");
        assertTrue(graph.getNode("design").getDependsOn().contains("clarify-requirements"),
                "expected 'design' to be rewired to depend on clarify-requirements");

        assertEquals(StageState.STALE, context.getState("design"));
        assertEquals(StageState.STALE, context.getState("implement"));

        assertFalse(context.getDecisions().isEmpty(), "expected a DecisionRecord to be recorded");
        assertTrue(context.getDecisions().get(0).rationale().toLowerCase().contains("fast")
                || context.getDecisions().get(0).summary().contains("ambiguous"));
    }

    @Test
    void wellSpecifiedRequirementsDoNotTriggerReplanning() throws Exception {
        Graph graph = buildRequirementsDesignImplementGraph();
        RunContext context = buildContextWithGraph(graph, "requirements/clear-requirements.md");
        StageDef requirementsDef = graph.getNode("requirements");

        StageResult result = stage.execute(context, requirementsDef);

        assertTrue(result.isSuccess());
        assertEquals(Boolean.FALSE, result.getOutputs().get("flagged"));
        assertFalse(graph.hasNode("clarify-requirements"));
        assertTrue(context.getDecisions().isEmpty());
    }
}

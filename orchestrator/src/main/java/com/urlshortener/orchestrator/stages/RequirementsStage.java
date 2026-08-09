package com.urlshortener.orchestrator.stages;

import com.urlshortener.orchestrator.engine.ApprovalPoint;
import com.urlshortener.orchestrator.engine.DecisionRecord;
import com.urlshortener.orchestrator.engine.Replanner;
import com.urlshortener.orchestrator.engine.RetryPolicyDef;
import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a requirements markdown file and flags vague / unquantified language. If any ambiguity
 * is found, this is where the literal, demonstrable dynamic re-planning happens: a synthetic
 * {@code clarify-requirements} node is inserted into the LIVE graph (gated by approval) and
 * wired in front of {@code design}, which is cascaded to STALE. This is not a description of a
 * capability -- it is the capability, exercised on every ambiguous requirements doc.
 */
public class RequirementsStage implements StageExecutor {

    public static final List<String> DEFAULT_VAGUE_TERMS = List.of(
            "fast", "scalable", "should", "tbd", "robust", "efficient",
            "user-friendly", "user friendly", "secure", "simple", "intuitive", "seamless"
    );

    /** Node the synthetic clarify-requirements node should gate, when this stage flags ambiguity. */
    public static final String GATED_NODE = "design";

    @Override
    public StageResult execute(RunContext context, StageDef def) throws Exception {
        Object inputPathObj = context.getInputs().get("requirementsInputPath");
        if (inputPathObj == null) {
            return StageResult.failure("no requirementsInputPath provided in context.inputs", Map.of());
        }
        Path inputPath = Path.of(String.valueOf(inputPathObj));
        String text;
        try {
            text = Files.readString(inputPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read requirements input: " + inputPath, e);
        }

        List<String> vagueTerms = resolveVagueTerms(def);
        List<Map<String, Object>> ambiguities = scanForAmbiguities(text, vagueTerms);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("normalizedSpec", text.strip());
        outputs.put("ambiguities", ambiguities);
        outputs.put("flagged", !ambiguities.isEmpty());

        if (!ambiguities.isEmpty()) {
            replan(context, def, ambiguities);
        }

        String summary = ambiguities.isEmpty()
                ? "Requirements scanned, no ambiguity flagged."
                : ambiguities.size() + " ambiguous term(s) flagged; clarify-requirements node inserted.";
        return StageResult.success(summary, outputs);
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveVagueTerms(StageDef def) {
        Object configured = def.getParams().get("vagueTerms");
        if (configured instanceof List<?> list && !list.isEmpty()) {
            List<String> terms = new ArrayList<>();
            for (Object o : list) {
                terms.add(String.valueOf(o).toLowerCase(Locale.ROOT));
            }
            return terms;
        }
        return DEFAULT_VAGUE_TERMS;
    }

    /** Package-visible for direct unit testing without a RunContext/StageDef. */
    List<Map<String, Object>> scanForAmbiguities(String text, List<String> vagueTerms) {
        List<Map<String, Object>> results = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String lower = line.toLowerCase(Locale.ROOT);
            for (String term : vagueTerms) {
                Pattern pattern = Pattern.compile("\\b" + Pattern.quote(term) + "\\b");
                Matcher matcher = pattern.matcher(lower);
                if (matcher.find()) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("line", i + 1);
                    hit.put("term", term);
                    hit.put("context", line.strip());
                    results.add(hit);
                }
            }
        }
        return results;
    }

    private void replan(RunContext context, StageDef requirementsDef, List<Map<String, Object>> ambiguities) {
        Replanner replanner = context.getReplanner();
        if (replanner == null) {
            return; // no orchestrator wired (e.g. isolated unit test of this stage only)
        }

        StageDef clarify = new StageDef(
                "clarify-requirements",
                List.of(requirementsDef.getName()),
                "clarify-requirements",
                RetryPolicyDef.defaults(),
                true,
                ApprovalPoint.ENTRY,
                "clarify-requirements-approval",
                Map.of("ambiguities", ambiguities)
        );

        replanner.insertNode(context, clarify);
        if (replanner.getGraph().hasNode(GATED_NODE)) {
            replanner.gateNodeOn(context, GATED_NODE, clarify.getName());
        }

        StringBuilder rationale = new StringBuilder("Flagged ambiguous/unquantified requirements language: ");
        for (Map<String, Object> a : ambiguities) {
            rationale.append("[line ").append(a.get("line")).append(": '").append(a.get("term")).append("'] ");
        }
        DecisionRecord decision = new DecisionRecord(
                Instant.now(),
                requirementsDef.getName(),
                ambiguities.size() + " ambiguous term(s) found; inserted clarify-requirements node",
                rationale.toString().strip(),
                Map.of("ambiguities", ambiguities)
        );
        context.recordDecision(decision);
        if (context.getAuditLogger() != null) {
            context.getAuditLogger().log(new com.urlshortener.orchestrator.audit.AuditEvent(
                    Instant.now(), context.getRunId(), com.urlshortener.orchestrator.audit.AuditEventType.DECISION,
                    requirementsDef.getName(), null, null, "system", decision.summary(),
                    Map.of("rationale", decision.rationale(), "ambiguities", ambiguities), null));
        }
    }
}

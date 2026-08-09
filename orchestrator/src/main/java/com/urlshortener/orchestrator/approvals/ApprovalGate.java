package com.urlshortener.orchestrator.approvals;

import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLogger;
import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import com.urlshortener.orchestrator.engine.StageState;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Human approval checkpoint. INTERACTIVE mode blocks on real stdin input; REPLAY mode looks up a
 * pre-supplied decision by decisionId from a YAML fixture, for reproducible scenario runs.
 * Every request logs APPROVAL_REQUESTED followed by APPROVAL_GRANTED or APPROVAL_DENIED.
 */
public class ApprovalGate {

    private final ApprovalMode mode;
    private final Map<String, ApprovalDecision> replayDecisions;
    private final Scanner stdinScanner;

    public ApprovalGate(ApprovalMode mode) {
        this(mode, Map.of());
    }

    private ApprovalGate(ApprovalMode mode, Map<String, ApprovalDecision> replayDecisions) {
        this.mode = mode;
        this.replayDecisions = new ConcurrentHashMap<>(replayDecisions);
        this.stdinScanner = mode == ApprovalMode.INTERACTIVE ? new Scanner(System.in) : null;
    }

    public static ApprovalGate interactive() {
        return new ApprovalGate(ApprovalMode.INTERACTIVE, Map.of());
    }

    public static ApprovalGate replay(Path fixtureFile) {
        return new ApprovalGate(ApprovalMode.REPLAY, loadFixture(fixtureFile));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ApprovalDecision> loadFixture(Path fixtureFile) {
        try (InputStream in = Files.newInputStream(fixtureFile)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            List<Map<String, Object>> entries = (List<Map<String, Object>>) loaded;
            Map<String, ApprovalDecision> result = new ConcurrentHashMap<>();
            if (entries != null) {
                for (Map<String, Object> entry : entries) {
                    String decisionId = String.valueOf(entry.get("decisionId"));
                    boolean approved = Boolean.TRUE.equals(entry.get("approved"));
                    String approver = String.valueOf(entry.getOrDefault("approver", "unknown"));
                    Object commentObj = entry.get("comment");
                    String comment = commentObj == null ? null : String.valueOf(commentObj);
                    result.put(decisionId, new ApprovalDecision(decisionId, approved, approver, comment));
                }
            }
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read approvals fixture: " + fixtureFile, e);
        }
    }

    public ApprovalMode getMode() {
        return mode;
    }

    /**
     * Requests approval for {@code node}'s configured decisionId (or an explicit override, e.g.
     * "release-approval"). Blocks until a decision is available, logs the full
     * requested/granted/denied lifecycle, and records the outcome on {@code context} so
     * {@code RequireHumanApprovalRule} can see it.
     */
    public ApprovalDecision requestApproval(RunContext context, StageDef node, String decisionId, String prompt) {
        AuditLogger logger = context.getAuditLogger();
        long start = System.currentTimeMillis();
        logAudit(logger, context, AuditEventType.APPROVAL_REQUESTED, node.getName(), StageState.AWAITING_APPROVAL,
                StageState.AWAITING_APPROVAL, "system", prompt, Map.of("decisionId", decisionId), null);

        ApprovalDecision decision = mode == ApprovalMode.INTERACTIVE
                ? decideInteractively(decisionId, prompt)
                : decideByReplay(decisionId);

        context.getApprovals().put(decisionId, decision.approved());
        long durationMs = System.currentTimeMillis() - start;
        String actor = "human:" + decision.approver();
        AuditEventType outcomeType = decision.approved() ? AuditEventType.APPROVAL_GRANTED : AuditEventType.APPROVAL_DENIED;
        logAudit(logger, context, outcomeType, node.getName(), StageState.AWAITING_APPROVAL, null, actor,
                decision.comment(), Map.of("decisionId", decisionId, "approver", decision.approver()), durationMs);
        return decision;
    }

    private ApprovalDecision decideInteractively(String decisionId, String prompt) {
        System.out.println("\n=== APPROVAL REQUIRED [" + decisionId + "] ===");
        System.out.println(prompt);
        System.out.print("Approve? (y/n): ");
        String line = stdinScanner.hasNextLine() ? stdinScanner.nextLine() : "n";
        boolean approved = line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes");
        System.out.print("Approver name: ");
        String approver = stdinScanner.hasNextLine() ? stdinScanner.nextLine() : System.getProperty("user.name", "unknown");
        if (approver.isBlank()) {
            approver = System.getProperty("user.name", "unknown");
        }
        return new ApprovalDecision(decisionId, approved, approver, "interactive decision");
    }

    private ApprovalDecision decideByReplay(String decisionId) {
        ApprovalDecision decision = replayDecisions.get(decisionId);
        if (decision == null) {
            return new ApprovalDecision(decisionId, false, "system", "no matching replay entry found for decisionId=" + decisionId);
        }
        return decision;
    }

    private void logAudit(AuditLogger logger, RunContext context, AuditEventType type, String node, StageState from,
                           StageState to, String actor, String reason, Map<String, Object> data, Long durationMs) {
        if (logger == null) {
            return;
        }
        logger.log(new AuditEvent(Instant.now(), context.getRunId(), type, node, from, to, actor, reason, data, durationMs));
    }
}

package com.urlshortener.orchestrator.approvals;

import com.urlshortener.orchestrator.audit.AuditEvent;
import com.urlshortener.orchestrator.audit.AuditEventType;
import com.urlshortener.orchestrator.audit.AuditLogger;
import com.urlshortener.orchestrator.engine.ApprovalPoint;
import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalGateTest {

    private Path fixturePath() throws URISyntaxException {
        URL resource = getClass().getClassLoader().getResource("approvals/approvals-fixture.yaml");
        return Path.of(resource.toURI());
    }

    private StageDef node(String name, String decisionId) {
        return new StageDef(name, List.of(), "noop", null, true, ApprovalPoint.EXIT, decisionId, null);
    }

    @Test
    void replayModeGrantsWhenFixtureSaysApproved(@TempDir Path tempDir) throws Exception {
        ApprovalGate gate = ApprovalGate.replay(fixturePath());
        RunContext context = new RunContext(UUID.randomUUID().toString(), "approval-test", tempDir, Map.of());
        AuditLogger logger = new AuditLogger(tempDir.resolve("audit.jsonl"));
        context.setAuditLogger(logger);

        ApprovalDecision decision = gate.requestApproval(context, node("design", "design-review"), "design-review", "prompt");

        assertTrue(decision.approved());
        assertEquals("alice", decision.approver());
        assertTrue(context.isApproved("design-review"));
        logger.close();

        List<AuditEvent> events = AuditLogger.readAll(tempDir.resolve("audit.jsonl"));
        assertTrue(events.stream().anyMatch(e -> e.eventType() == AuditEventType.APPROVAL_REQUESTED));
        assertTrue(events.stream().anyMatch(e -> e.eventType() == AuditEventType.APPROVAL_GRANTED));
        assertFalse(events.stream().anyMatch(e -> e.eventType() == AuditEventType.APPROVAL_DENIED));
    }

    @Test
    void replayModeDeniesWhenFixtureSaysDenied(@TempDir Path tempDir) throws Exception {
        ApprovalGate gate = ApprovalGate.replay(fixturePath());
        RunContext context = new RunContext(UUID.randomUUID().toString(), "approval-test", tempDir, Map.of());
        AuditLogger logger = new AuditLogger(tempDir.resolve("audit.jsonl"));
        context.setAuditLogger(logger);

        ApprovalDecision decision = gate.requestApproval(context, node("release-readiness", "release-approval"),
                "release-approval", "prompt");

        assertFalse(decision.approved());
        assertEquals("bob", decision.approver());
        assertFalse(context.isApproved("release-approval"));
        logger.close();

        List<AuditEvent> events = AuditLogger.readAll(tempDir.resolve("audit.jsonl"));
        assertTrue(events.stream().anyMatch(e -> e.eventType() == AuditEventType.APPROVAL_DENIED));
    }

    @Test
    void replayModeDeniesByDefaultWhenDecisionIdMissingFromFixture(@TempDir Path tempDir) throws Exception {
        ApprovalGate gate = ApprovalGate.replay(fixturePath());
        RunContext context = new RunContext(UUID.randomUUID().toString(), "approval-test", tempDir, Map.of());
        AuditLogger logger = new AuditLogger(tempDir.resolve("audit.jsonl"));
        context.setAuditLogger(logger);

        ApprovalDecision decision = gate.requestApproval(context, node("unknown-stage", "unknown-decision"),
                "unknown-decision", "prompt");

        assertFalse(decision.approved());
        logger.close();
    }
}

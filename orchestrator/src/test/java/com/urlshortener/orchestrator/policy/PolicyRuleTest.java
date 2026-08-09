package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import com.urlshortener.orchestrator.engine.StageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRuleTest {

    private RunContext newContext(Path serviceRepo) {
        return new RunContext(UUID.randomUUID().toString(), "policy-test", serviceRepo, Map.of());
    }

    private StageDef node(String name) {
        return new StageDef(name, List.of(), "noop", null, false, null, null, null);
    }

    // -------------------------------------------------------------- RequireStageSucceededRule

    @Test
    void requireStageSucceededRuleBlocksWhenRequiredStageNotSucceeded(@TempDir Path tmp) {
        RunContext context = newContext(tmp);
        context.getStates().put("testing", StageState.FAILED);
        RequireStageSucceededRule rule = new RequireStageSucceededRule();
        PolicyResult result = rule.evaluate(context, node("release-readiness"), Map.of("requiredStage", "testing"));
        assertFalse(result.allowed());
    }

    @Test
    void requireStageSucceededRuleAllowsWhenRequiredStageSucceeded(@TempDir Path tmp) {
        RunContext context = newContext(tmp);
        context.getStates().put("testing", StageState.SUCCEEDED);
        RequireStageSucceededRule rule = new RequireStageSucceededRule();
        PolicyResult result = rule.evaluate(context, node("release-readiness"), Map.of("requiredStage", "testing"));
        assertTrue(result.allowed());
    }

    // -------------------------------------------------------------- RequireHumanApprovalRule

    @Test
    void requireHumanApprovalRuleBlocksWithoutRecordedGrant(@TempDir Path tmp) {
        RunContext context = newContext(tmp);
        RequireHumanApprovalRule rule = new RequireHumanApprovalRule();
        PolicyResult result = rule.evaluate(context, node("release-readiness"), Map.of("decisionId", "release-approval"));
        assertFalse(result.allowed());
    }

    @Test
    void requireHumanApprovalRuleAllowsAfterRecordedGrant(@TempDir Path tmp) {
        RunContext context = newContext(tmp);
        context.getApprovals().put("release-approval", true);
        RequireHumanApprovalRule rule = new RequireHumanApprovalRule();
        PolicyResult result = rule.evaluate(context, node("release-readiness"), Map.of("decisionId", "release-approval"));
        assertTrue(result.allowed());
    }

    @Test
    void requireHumanApprovalRuleBlocksAfterRecordedDenial(@TempDir Path tmp) {
        RunContext context = newContext(tmp);
        context.getApprovals().put("release-approval", false);
        RequireHumanApprovalRule rule = new RequireHumanApprovalRule();
        PolicyResult result = rule.evaluate(context, node("release-readiness"), Map.of("decisionId", "release-approval"));
        assertFalse(result.allowed());
    }

    // -------------------------------------------------------------- NoSecretsInChangedFilesRule

    @Test
    void noSecretsRuleAllowsWhenScanClean(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("clean.txt"), "nothing to see here");
        NoSecretsInChangedFilesRule rule = new NoSecretsInChangedFilesRule(new StubScanner(List.of()));
        RunContext context = newContext(tmp);
        PolicyResult result = rule.evaluate(context, node("implement-api"), Map.of());
        assertTrue(result.allowed());
    }

    @Test
    void noSecretsRuleBlocksWhenViolationsFound(@TempDir Path tmp) {
        NoSecretsInChangedFilesRule rule = new NoSecretsInChangedFilesRule(
                new StubScanner(List.of(new SecretScanner.Violation("Config.java", "AWS_KEY", "AKIA..."))));
        RunContext context = newContext(tmp);
        PolicyResult result = rule.evaluate(context, node("implement-api"), Map.of());
        assertFalse(result.allowed());
    }

    /** Stub SecretScanner so this rule test doesn't depend on a real git repo being present. */
    private static class StubScanner extends SecretScanner {
        private final List<Violation> violations;

        StubScanner(List<Violation> violations) {
            this.violations = violations;
        }

        @Override
        public ScanResult scan(Path repoDir) {
            return new ScanResult(List.of(), violations);
        }
    }
}

package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;

import java.util.Map;

/**
 * A single policy guardrail. Deliberately NOT a generic rule engine/DSL — exactly the three
 * concrete rules required by the assignment implement this interface.
 */
public interface PolicyRule {

    /** Stable identifier used in policy.yaml and audit payloads. */
    String id();

    /** Evaluate this rule for {@code node}, given the run's current state. {@code params} come from policy.yaml. */
    PolicyResult evaluate(RunContext context, StageDef node, Map<String, Object> params);
}

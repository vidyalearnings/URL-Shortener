package com.urlshortener.orchestrator.policy;

import com.urlshortener.orchestrator.engine.RunContext;
import com.urlshortener.orchestrator.engine.StageDef;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code policy.yaml} and applies the (exactly three) concrete {@link PolicyRule}s to the
 * stages they're configured against. Deliberately not a generic rule engine.
 */
public class PolicyEngine {

    /** One configured rule binding: which rule, applied to which stages, with which params. */
    private record RuleBinding(String id, PolicyRule rule, Set<String> appliesTo, Map<String, Object> params) {
    }

    public record CheckOutcome(boolean allowed, List<String> reasons) {
        public static CheckOutcome allow() {
            return new CheckOutcome(true, List.of());
        }
    }

    private final List<RuleBinding> bindings;

    private PolicyEngine(List<RuleBinding> bindings) {
        this.bindings = bindings;
    }

    public static PolicyEngine empty() {
        return new PolicyEngine(new ArrayList<>());
    }

    public static PolicyEngine fromYamlFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return fromYamlStream(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read policy YAML: " + path, e);
        }
    }

    public static PolicyEngine fromYamlResource(String resourcePath) {
        try (InputStream in = PolicyEngine.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Policy resource not found: " + resourcePath);
            }
            return fromYamlStream(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read policy resource: " + resourcePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static PolicyEngine fromYamlStream(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);
        List<RuleBinding> bindings = new ArrayList<>();
        if (root == null || root.get("rules") == null) {
            return new PolicyEngine(bindings);
        }
        List<Map<String, Object>> rawRules = (List<Map<String, Object>>) root.get("rules");
        for (Map<String, Object> raw : rawRules) {
            String id = String.valueOf(raw.get("id"));
            String type = String.valueOf(raw.get("type"));
            List<?> appliesToRaw = (List<?>) raw.getOrDefault("appliesTo", List.of());
            Set<String> appliesTo = new java.util.LinkedHashSet<>();
            for (Object o : appliesToRaw) {
                appliesTo.add(String.valueOf(o));
            }
            Map<String, Object> params = new LinkedHashMap<>();
            Object p = raw.get("params");
            if (p instanceof Map<?, ?> pMap) {
                for (Map.Entry<?, ?> e : pMap.entrySet()) {
                    params.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            PolicyRule rule = instantiate(type);
            bindings.add(new RuleBinding(id, rule, appliesTo, params));
        }
        return new PolicyEngine(bindings);
    }

    private static PolicyRule instantiate(String type) {
        return switch (type) {
            case "RequireStageSucceededRule" -> new RequireStageSucceededRule();
            case "RequireHumanApprovalRule" -> new RequireHumanApprovalRule();
            case "NoSecretsInChangedFilesRule" -> new NoSecretsInChangedFilesRule();
            default -> throw new IllegalArgumentException("Unknown policy rule type: " + type);
        };
    }

    /** Runs every rule bound to {@code node.getName()} and aggregates the result. */
    public CheckOutcome check(RunContext context, StageDef node) {
        List<String> reasons = new ArrayList<>();
        for (RuleBinding binding : bindings) {
            if (!binding.appliesTo().contains(node.getName())) {
                continue;
            }
            PolicyResult result = binding.rule().evaluate(context, node, binding.params());
            if (!result.allowed()) {
                reasons.add("[" + binding.id() + "] " + result.reason());
            }
        }
        return reasons.isEmpty() ? CheckOutcome.allow() : new CheckOutcome(false, reasons);
    }
}

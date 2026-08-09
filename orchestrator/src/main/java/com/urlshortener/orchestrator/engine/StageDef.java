package com.urlshortener.orchestrator.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of a single node in the stage graph. Mutable (dependsOn can be rewritten at
 * runtime by {@link Replanner}) but individually thread-safe fields are synchronized-list backed
 * where mutation after graph construction is expected.
 */
public class StageDef {

    private final String name;
    private final List<String> dependsOn;
    private final String executor;
    private final RetryPolicyDef retryPolicy;
    private final boolean requiresApproval;
    private final ApprovalPoint approvalPoint;
    private final String approvalDecisionId;
    private final Map<String, Object> params;

    public StageDef(String name,
                     List<String> dependsOn,
                     String executor,
                     RetryPolicyDef retryPolicy,
                     boolean requiresApproval,
                     ApprovalPoint approvalPoint,
                     String approvalDecisionId,
                     Map<String, Object> params) {
        this.name = name;
        this.dependsOn = Collections.synchronizedList(new ArrayList<>(dependsOn == null ? List.of() : dependsOn));
        this.executor = executor;
        this.retryPolicy = retryPolicy == null ? RetryPolicyDef.defaults() : retryPolicy;
        this.requiresApproval = requiresApproval;
        this.approvalPoint = approvalPoint;
        this.approvalDecisionId = approvalDecisionId == null ? name : approvalDecisionId;
        this.params = new LinkedHashMap<>(params == null ? Map.of() : params);
    }

    public String getName() {
        return name;
    }

    /** Live, thread-safe snapshot of current dependencies (mutable via {@link #addDependency(String)}). */
    public List<String> getDependsOn() {
        synchronized (dependsOn) {
            return new ArrayList<>(dependsOn);
        }
    }

    void addDependency(String dependency) {
        synchronized (dependsOn) {
            if (!dependsOn.contains(dependency)) {
                dependsOn.add(dependency);
            }
        }
    }

    public String getExecutor() {
        return executor;
    }

    public RetryPolicyDef getRetryPolicy() {
        return retryPolicy;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public ApprovalPoint getApprovalPoint() {
        return approvalPoint;
    }

    public String getApprovalDecisionId() {
        return approvalDecisionId;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    @Override
    public String toString() {
        return "StageDef{" + name + ", dependsOn=" + getDependsOn() + ", executor=" + executor + "}";
    }
}

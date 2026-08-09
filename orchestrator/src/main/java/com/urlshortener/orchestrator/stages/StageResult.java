package com.urlshortener.orchestrator.stages;

import java.util.LinkedHashMap;
import java.util.Map;

/** Outcome of a single {@link StageExecutor#execute} invocation. */
public final class StageResult {

    private final boolean success;
    private final Map<String, Object> outputs;
    private final String summary;

    private StageResult(boolean success, Map<String, Object> outputs, String summary) {
        this.success = success;
        this.outputs = outputs;
        this.summary = summary;
    }

    public static StageResult success(String summary, Map<String, Object> outputs) {
        return new StageResult(true, outputs == null ? new LinkedHashMap<>() : outputs, summary);
    }

    public static StageResult failure(String summary, Map<String, Object> outputs) {
        return new StageResult(false, outputs == null ? new LinkedHashMap<>() : outputs, summary);
    }

    public boolean isSuccess() {
        return success;
    }

    public Map<String, Object> getOutputs() {
        return outputs;
    }

    public String getSummary() {
        return summary;
    }
}

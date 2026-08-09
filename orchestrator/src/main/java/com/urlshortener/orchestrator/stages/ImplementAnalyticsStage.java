package com.urlshortener.orchestrator.stages;

/** Cares about anything with "analytics" in its path/name. */
public class ImplementAnalyticsStage extends AbstractImplementStage {

    @Override
    protected boolean isImpacted(String relativePath) {
        return containsIgnoreCase(relativePath, "analytics");
    }

    @Override
    protected String stageLabel() {
        return "implement-analytics";
    }
}

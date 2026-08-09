package com.urlshortener.orchestrator.stages;

/** Cares about controller/API-layer files. */
public class ImplementApiStage extends AbstractImplementStage {

    @Override
    protected boolean isImpacted(String relativePath) {
        return containsIgnoreCase(relativePath, "controller/") || containsIgnoreCase(relativePath, "controller\\");
    }

    @Override
    protected String stageLabel() {
        return "implement-api";
    }
}

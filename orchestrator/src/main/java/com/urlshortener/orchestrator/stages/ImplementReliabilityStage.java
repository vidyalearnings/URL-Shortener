package com.urlshortener.orchestrator.stages;

/** Cares about rate-limiting, short-code generation, and exception-handling files. */
public class ImplementReliabilityStage extends AbstractImplementStage {

    @Override
    protected boolean isImpacted(String relativePath) {
        return containsIgnoreCase(relativePath, "ratelimit")
                || containsIgnoreCase(relativePath, "shortcodegenerator")
                || containsIgnoreCase(relativePath, "exception");
    }

    @Override
    protected String stageLabel() {
        return "implement-reliability";
    }
}

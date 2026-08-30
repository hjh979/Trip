package com.zkry.service;

/** Reports progress for evidence recall and planning stages. */
@FunctionalInterface
public interface TripResearchProgressReporter {
    void report(String stage, int progress, String message);

    static TripResearchProgressReporter noop() {
        return (stage, progress, message) -> { };
    }
}

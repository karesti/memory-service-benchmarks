package io.github.memory.benchmark;

/**
 * Represents the different modes in which a benchmark can run.
 * Each mode uses a different strategy for generating answers to questions.
 */
public enum BenchmarkMode {
    /**
     * Cognition mode: Uses memory-service with cognition processor enabled.
     * Ingests conversations, waits for cognition extraction, then searches memories.
     */
    COGNITION,
    
    /**
     * Substrate mode: Uses memory-service without cognition processor.
     * Ingests conversations as raw entries, then searches entries directly.
     */
    SUBSTRATE,
    
    /**
     * Long-context mode: Bypasses memory-service entirely.
     * Packs conversation history into LLM context window with truncation.
     */
    LONG_CONTEXT;
    
    /**
     * Determines the benchmark mode based on configuration.
     * Validates that only one mode is enabled at a time.
     * 
     * @param config the benchmark configuration
     * @return the determined benchmark mode
     * @throws IllegalStateException if multiple modes are enabled simultaneously
     */
    public static BenchmarkMode determine(BenchmarkConfig config) {
        boolean longContextEnabled = config.longContext().enabled();
        boolean cognitionEnabled = config.cognition().enabled();
        
        // Validate mutual exclusivity
        if (longContextEnabled && cognitionEnabled) {
            throw new IllegalStateException(
                "Cannot enable both long-context mode and cognition mode simultaneously. " +
                "Please set only one of: benchmark.long-context.enabled=true OR benchmark.cognition.enabled=true"
            );
        }
        
        // Determine mode
        if (longContextEnabled) {
            return LONG_CONTEXT;
        }
        
        return cognitionEnabled ? COGNITION : SUBSTRATE;
    }
}

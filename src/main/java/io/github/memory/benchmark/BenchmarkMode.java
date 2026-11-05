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
    LONG_CONTEXT,
    
    /**
     * Blind mode: No retrieval, no context.
     * Sends questions directly to LLM with no memory or conversation context.
     * Useful for measuring minimum token usage baseline. Expected accuracy: ~0%.
     */
    BLIND;
    
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
        boolean blindEnabled = config.blind().enabled();
        
        // Explicit modes (blind, long-context) take precedence over cognition
        // This allows users to enable blind/long-context without explicitly disabling cognition
        if (blindEnabled && longContextEnabled) {
            throw new IllegalStateException(
                "Cannot enable multiple modes simultaneously. " +
                "Please set only one of: benchmark.long-context.enabled=true or benchmark.blind.enabled=true"
            );
        }
        
        // Determine mode (explicit modes override cognition default)
        if (blindEnabled) {
            return BLIND;
        }
        
        if (longContextEnabled) {
            return LONG_CONTEXT;
        }
        
        return cognitionEnabled ? COGNITION : SUBSTRATE;
    }
}

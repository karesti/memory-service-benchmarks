package io.github.memory.benchmark;

/**
 * Defines how conversation history should be truncated when it exceeds the token budget.
 * Used by LongContextBuilder to fit conversations into the LLM's context window.
 */
public enum TruncationMode {
    /**
     * Keep the most recent conversation turns (default).
     * Truncates from the beginning, preserving recent context.
     * Best for conversations where recent information is most relevant.
     */
    KEEP_NEWEST,
    
    /**
     * Keep the oldest conversation turns.
     * Truncates from the end, preserving early context.
     * Matches the approach used in the LoCoMo reference paper.
     */
    KEEP_OLDEST,
    
    /**
     * No truncation - include the entire conversation.
     * May exceed the model's context window if conversation is too long.
     * Use only when you're certain the conversation fits within the token budget.
     */
    NONE;
    
    /**
     * Parses a truncation mode from a string, case-insensitive.
     * 
     * @param value the string value to parse
     * @return the corresponding TruncationMode
     * @throws IllegalArgumentException if the value is not a valid truncation mode
     */
    public static TruncationMode fromString(String value) {
        if (value == null) {
            return KEEP_NEWEST; // default
        }
        
        try {
            return TruncationMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid truncation mode: " + value + ". " +
                "Valid values are: KEEP_NEWEST, KEEP_OLDEST, NONE"
            );
        }
    }
}

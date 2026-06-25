package io.github.memory.benchmark;

import java.util.List;

public record BenchmarkResult(
        String questionId,
        int conversationIndex,
        int category,
        String question,
        String groundTruth,
        String generatedAnswer,
        String verdict,
        String reason,
        double searchLatencyMs,
        int memoriesRetrieved,
        List<String> topMemories
) {

    public boolean isCorrect() {
        return "CORRECT".equalsIgnoreCase(verdict);
    }
}

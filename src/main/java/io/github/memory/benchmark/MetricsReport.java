package io.github.memory.benchmark;

import java.util.*;
import java.util.stream.Collectors;

public class MetricsReport {

    private static final Map<Integer, String> CATEGORY_NAMES = Map.of(
            1, "Multi-hop",
            2, "Temporal",
            3, "Causal",
            4, "Factual",
            5, "Adversarial"
    );

    private static final Map<Integer, String> CATEGORY_DESC = Map.of(
            1, "Connecting facts across sessions",
            2, "Dates, timing, sequences",
            3, "Reasoning about causes",
            4, "Direct fact recall",
            5, "Questions about non-existent events"
    );

    public record CategoryMetrics(String name, String description, int total, int correct, double accuracy) {}

    public record Summary(
            double overallAccuracy,
            int totalQuestions,
            int totalCorrect,
            double avgSearchLatencyMs,
            double avgMemoriesRetrieved,
            List<CategoryMetrics> byCategory
    ) {}

    public static Summary compute(List<BenchmarkResult> results) {
        if (results.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0, List.of());
        }

        int totalCorrect = (int) results.stream().filter(BenchmarkResult::isCorrect).count();
        double avgLatency = results.stream().mapToDouble(BenchmarkResult::searchLatencyMs).average().orElse(0);
        double avgMemories = results.stream().mapToDouble(BenchmarkResult::memoriesRetrieved).average().orElse(0);

        Map<Integer, List<BenchmarkResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(BenchmarkResult::category));

        List<CategoryMetrics> categoryMetrics = byCategory.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    int cat = e.getKey();
                    List<BenchmarkResult> catResults = e.getValue();
                    int catCorrect = (int) catResults.stream().filter(BenchmarkResult::isCorrect).count();
                    return new CategoryMetrics(
                            CATEGORY_NAMES.getOrDefault(cat, "unknown-" + cat),
                            CATEGORY_DESC.getOrDefault(cat, ""),
                            catResults.size(),
                            catCorrect,
                            catResults.isEmpty() ? 0 : (double) catCorrect / catResults.size()
                    );
                })
                .toList();

        return new Summary(
                (double) totalCorrect / results.size(),
                results.size(),
                totalCorrect,
                avgLatency,
                avgMemories,
                categoryMetrics
        );
    }

    public static String format(Summary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("┌─────────────────────────────────────────────────────────────┐\n");
        sb.append("│                  LoCoMo Benchmark Results                   │\n");
        sb.append("├─────────────────────────────────────────────────────────────┤\n");
        sb.append("│                                                             │\n");
        sb.append(String.format("│  Overall Accuracy:   %5.1f%%  (%d / %d questions)%s│\n",
                s.overallAccuracy() * 100, s.totalCorrect(), s.totalQuestions(),
                pad(43 - digits(s.totalCorrect()) - digits(s.totalQuestions()))));
        sb.append("│                                                             │\n");
        sb.append("├─────────────────────────────────────────────────────────────┤\n");
        sb.append("│  Category Breakdown                                        │\n");
        sb.append("│                                                             │\n");

        for (CategoryMetrics cm : s.byCategory()) {
            String bar = progressBar(cm.accuracy(), 15);
            sb.append(String.format("│  %-10s %s %5.1f%%  (%2d / %2d)  %-25s│\n",
                    cm.name(), bar, cm.accuracy() * 100, cm.correct(), cm.total(), cm.description()));
        }

        sb.append("│                                                             │\n");
        sb.append("├─────────────────────────────────────────────────────────────┤\n");
        sb.append("│  Performance                                               │\n");
        sb.append("│                                                             │\n");
        sb.append(String.format("│  Avg search latency:    %6.0f ms                           │\n", s.avgSearchLatencyMs()));
        sb.append(String.format("│  Avg memories / query:  %6.1f                              │\n", s.avgMemoriesRetrieved()));
        sb.append("│                                                             │\n");
        sb.append("└─────────────────────────────────────────────────────────────┘\n");

        return sb.toString();
    }

    private static String progressBar(double ratio, int width) {
        int filled = (int) Math.round(ratio * width);
        return "█".repeat(filled) + "░".repeat(width - filled);
    }

    private static int digits(int n) {
        return String.valueOf(n).length();
    }

    private static String pad(int n) {
        return " ".repeat(Math.max(0, n));
    }
}

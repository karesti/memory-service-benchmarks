package io.github.memory.benchmark;

import java.util.*;
import java.util.stream.Collectors;

public class MetricsReport {

    private static final Map<Integer, String> CATEGORY_NAMES = Map.of(
            1, "multi-hop",
            2, "temporal",
            3, "causal",
            4, "factual",
            5, "adversarial"
    );

    public record CategoryMetrics(String name, int total, int correct, double accuracy) {}

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

    public static String format(Summary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════╗\n");
        sb.append("║          LoCoMo Benchmark Results                ║\n");
        sb.append("╠══════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Overall Accuracy:  %.1f%% (%d/%d)%s║\n",
                summary.overallAccuracy() * 100, summary.totalCorrect(), summary.totalQuestions(),
                " ".repeat(Math.max(0, 18 - String.valueOf(summary.totalCorrect()).length() - String.valueOf(summary.totalQuestions()).length()))));
        sb.append(String.format("║  Avg Search Latency: %.0f ms%s║\n",
                summary.avgSearchLatencyMs(), " ".repeat(Math.max(0, 23 - String.format("%.0f", summary.avgSearchLatencyMs()).length()))));
        sb.append(String.format("║  Avg Memories/Query: %.1f%s║\n",
                summary.avgMemoriesRetrieved(), " ".repeat(Math.max(0, 27 - String.format("%.1f", summary.avgMemoriesRetrieved()).length()))));
        sb.append("╠══════════════════════════════════════════════════╣\n");
        sb.append("║  Category Breakdown:                            ║\n");
        for (CategoryMetrics cm : summary.byCategory()) {
            sb.append(String.format("║    %-14s  %.1f%% (%d/%d)%s║\n",
                    cm.name(), cm.accuracy() * 100, cm.correct(), cm.total(),
                    " ".repeat(Math.max(0, 20 - cm.name().length() + 6 - String.valueOf(cm.correct()).length() - String.valueOf(cm.total()).length()))));
        }
        sb.append("╚══════════════════════════════════════════════════╝\n");
        return sb.toString();
    }
}

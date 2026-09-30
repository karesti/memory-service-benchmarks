package io.github.memory.benchmark;

import java.util.*;
import java.util.stream.Collectors;

public class MetricsReport {

    public record CategoryMetrics(String name, int total, int correct, double accuracy) {}

    public record Summary(
            String benchmarkName,
            double overallAccuracy,
            int totalQuestions,
            int totalCorrect,
            double avgF1,
            double avgBleu,
            double avgSearchLatencyMs,
            double avgMemoriesRetrieved,
            List<CategoryMetrics> byCategory,
            long totalInputTokens,
            long totalOutputTokens,
            long totalTokens,
            double avgInputTokensPerQuestion,
            double avgOutputTokensPerQuestion
    ) {}

    public static Summary compute(String benchmarkName, List<BenchmarkResult> results) {
        if (results.isEmpty()) {
            return new Summary(benchmarkName, 0, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, 0);
        }

        double totalScore = results.stream().mapToDouble(BenchmarkResult::score).sum();
        int totalCorrect = (int) Math.round(totalScore);
        double avgF1 = results.stream().mapToDouble(BenchmarkResult::f1).average().orElse(0);
        double avgBleu = results.stream().mapToDouble(BenchmarkResult::bleu).average().orElse(0);
        double avgLatency = results.stream().mapToDouble(BenchmarkResult::searchLatencyMs).average().orElse(0);
        double avgMemories = results.stream().mapToDouble(BenchmarkResult::memoriesRetrieved).average().orElse(0);

        long totalInputTokens = results.stream().mapToLong(r -> {
            long inAns = r.answerInputTokens() != null ? r.answerInputTokens() : 0L;
            long inJudge = r.judgeInputTokens() != null ? r.judgeInputTokens() : 0L;
            return inAns + inJudge;
        }).sum();

        long totalOutputTokens = results.stream().mapToLong(r -> {
            long outAns = r.answerOutputTokens() != null ? r.answerOutputTokens() : 0L;
            long outJudge = r.judgeOutputTokens() != null ? r.judgeOutputTokens() : 0L;
            return outAns + outJudge;
        }).sum();

        long totalTokens = results.stream().mapToLong(r ->
            r.totalTokens() != null ? r.totalTokens() : 0L
        ).sum();

        double avgInputTokensPerQuestion = (double) totalInputTokens / results.size();
        double avgOutputTokensPerQuestion = (double) totalOutputTokens / results.size();

        Map<String, List<BenchmarkResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(BenchmarkResult::category));

        List<CategoryMetrics> categoryMetrics = byCategory.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<BenchmarkResult> catResults = e.getValue();
                    double catScore = catResults.stream().mapToDouble(BenchmarkResult::score).sum();
                    int catCorrect = (int) Math.round(catScore);
                    return new CategoryMetrics(
                            e.getKey(),
                            catResults.size(),
                            catCorrect,
                            catResults.isEmpty() ? 0 : catScore / catResults.size()
                    );
                })
                .toList();

        return new Summary(
                benchmarkName,
                totalScore / results.size(),
                results.size(),
                totalCorrect,
                avgF1,
                avgBleu,
                avgLatency,
                avgMemories,
                categoryMetrics,
                totalInputTokens,
                totalOutputTokens,
                totalTokens,
                avgInputTokensPerQuestion,
                avgOutputTokensPerQuestion
        );
    }

    public static String format(Summary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("┌─────────────────────────────────────────────────────────────┐\n");
        sb.append(String.format("│            %s Benchmark Results%s│\n",
                s.benchmarkName(), pad(60 - 25 - s.benchmarkName().length())));
        sb.append("├─────────────────────────────────────────────────────────────┤\n");
        sb.append("│                                                             │\n");
        sb.append(String.format("│  LLM Judge Accuracy: %5.1f%%  (%d / %d questions)%s│\n",
                s.overallAccuracy() * 100, s.totalCorrect(), s.totalQuestions(),
                pad(43 - digits(s.totalCorrect()) - digits(s.totalQuestions()))));
        sb.append(String.format("│  F1 Score:           %5.1f%%                                  │\n", s.avgF1() * 100));
        sb.append(String.format("│  BLEU Score:         %5.1f%%                                  │\n", s.avgBleu() * 100));
        sb.append("│                                                             │\n");
        sb.append("├─────────────────────────────────────────────────────────────┤\n");
        sb.append("│  Category Breakdown                                        │\n");
        sb.append("│                                                             │\n");

        int maxNameLen = s.byCategory().stream()
                .mapToInt(cm -> cm.name().length())
                .max().orElse(10);
        maxNameLen = Math.max(maxNameLen, 10);

        for (CategoryMetrics cm : s.byCategory()) {
            String bar = progressBar(cm.accuracy(), 12);
            String line = String.format("│  %-" + maxNameLen + "s %s %5.1f%%  (%2d / %2d)",
                    cm.name(), bar, cm.accuracy() * 100, cm.correct(), cm.total());
            int remaining = 62 - line.length();
            sb.append(line).append(pad(remaining)).append("│\n");
        }

        sb.append("│                                                             │\n");
        sb.append("├─────────────────────────────────────────────────────────────┤\n");
        sb.append("│  Performance & Token Usage                                 │\n");
        sb.append("│                                                             │\n");
        sb.append(String.format("│  Avg search latency:    %6.0f ms                           │\n", s.avgSearchLatencyMs()));
        sb.append(String.format("│  Avg memories / query:  %6.1f                              │\n", s.avgMemoriesRetrieved()));
        sb.append(String.format("│  Total tokens:          %8d (in: %d, out: %d)%s│\n",
                s.totalTokens(), s.totalInputTokens(), s.totalOutputTokens(),
                pad(62 - String.format("│  Total tokens:          %8d (in: %d, out: %d)",
                        s.totalTokens(), s.totalInputTokens(), s.totalOutputTokens()).length() - 1)));
        sb.append(String.format("│  Avg tokens / question: %8.1f (in: %.1f, out: %.1f)%s│\n",
                (double) s.totalTokens() / (s.totalQuestions() > 0 ? s.totalQuestions() : 1),
                s.avgInputTokensPerQuestion(), s.avgOutputTokensPerQuestion(),
                pad(62 - String.format("│  Avg tokens / question: %8.1f (in: %.1f, out: %.1f)",
                        (double) s.totalTokens() / (s.totalQuestions() > 0 ? s.totalQuestions() : 1),
                        s.avgInputTokensPerQuestion(), s.avgOutputTokensPerQuestion()).length() - 1)));
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

package io.github.memory.benchmark;

import java.util.*;
import java.util.stream.Collectors;

public class TextMetrics {

    public record Scores(double f1, double bleu) {}

    public static Scores compute(String reference, String hypothesis) {
        if (reference == null || hypothesis == null) {
            return new Scores(0, 0);
        }

        List<String> refTokens = tokenize(reference);
        List<String> hypTokens = tokenize(hypothesis);

        double f1 = computeF1(refTokens, hypTokens);
        double bleu = computeBleu(refTokens, hypTokens);

        return new Scores(f1, bleu);
    }

    private static double computeF1(List<String> reference, List<String> hypothesis) {
        if (reference.isEmpty() || hypothesis.isEmpty()) return 0;

        Set<String> refSet = new HashSet<>(reference);
        Set<String> hypSet = new HashSet<>(hypothesis);

        long overlap = hypSet.stream().filter(refSet::contains).count();
        if (overlap == 0) return 0;

        double precision = (double) overlap / hypSet.size();
        double recall = (double) overlap / refSet.size();

        return 2 * precision * recall / (precision + recall);
    }

    private static double computeBleu(List<String> reference, List<String> hypothesis) {
        if (reference.isEmpty() || hypothesis.isEmpty()) return 0;

        double score = 0;
        int maxN = Math.min(4, Math.min(reference.size(), hypothesis.size()));
        if (maxN == 0) return 0;

        for (int n = 1; n <= maxN; n++) {
            Map<List<String>, Integer> refNgrams = ngrams(reference, n);
            Map<List<String>, Integer> hypNgrams = ngrams(hypothesis, n);

            int clipped = 0;
            int total = 0;
            for (var entry : hypNgrams.entrySet()) {
                int hypCount = entry.getValue();
                int refCount = refNgrams.getOrDefault(entry.getKey(), 0);
                clipped += Math.min(hypCount, refCount);
                total += hypCount;
            }

            if (total == 0) return 0;
            score += Math.log((double) clipped / total);
        }

        score /= maxN;

        // Brevity penalty
        double bp = 1.0;
        if (hypothesis.size() < reference.size()) {
            bp = Math.exp(1.0 - (double) reference.size() / hypothesis.size());
        }

        return bp * Math.exp(score);
    }

    private static Map<List<String>, Integer> ngrams(List<String> tokens, int n) {
        Map<List<String>, Integer> counts = new HashMap<>();
        for (int i = 0; i <= tokens.size() - n; i++) {
            List<String> ngram = tokens.subList(i, i + n);
            counts.merge(ngram, 1, Integer::sum);
        }
        return counts;
    }

    private static List<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

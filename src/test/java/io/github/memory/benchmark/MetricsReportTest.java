package io.github.memory.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MetricsReportTest {

    @Test
    void testComputeTokenMetrics() {
        BenchmarkResult r1 = new BenchmarkResult(
                "q1", "locomo", "factual",
                "What is X?", "Y", "Y",
                "CORRECT", "matches", 1.0, 1.0, 1.0,
                50.0, 5, List.of("m1"),
                100, 20, 50, 10, 180
        );

        BenchmarkResult r2 = new BenchmarkResult(
                "q2", "locomo", "temporal",
                "When is X?", "2023", "2024",
                "WRONG", "mismatch", 0.0, 0.0, 0.0,
                70.0, 3, List.of("m2"),
                200, 30, 60, 10, 300
        );

        MetricsReport.Summary summary = MetricsReport.compute("locomo", List.of(r1, r2));

        assertEquals(2, summary.totalQuestions());
        assertEquals(1, summary.totalCorrect());
        assertEquals(0.5, summary.overallAccuracy());

        // Token metrics
        // r1: in=100+50=150, out=20+10=30, total=180
        // r2: in=200+60=260, out=30+10=40, total=300
        assertEquals(410, summary.totalInputTokens());
        assertEquals(70, summary.totalOutputTokens());
        assertEquals(480, summary.totalTokens());
        assertEquals(205.0, summary.avgInputTokensPerQuestion());
        assertEquals(35.0, summary.avgOutputTokensPerQuestion());

        String formatted = MetricsReport.format(summary);
        assertNotNull(formatted);
    }
}

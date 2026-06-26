package io.github.memory.benchmark.longmemeval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.memory.benchmark.BenchmarkConfig;
import io.github.memory.benchmark.BenchmarkResult;
import io.github.memory.benchmark.LlmAnswerGenerator;
import io.github.memory.benchmark.LlmJudge;
import io.github.memory.benchmark.MemoryServiceClient;
import io.github.memory.benchmark.MetricsReport;
import io.github.memory.benchmark.TextMetrics;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command(name = "longmemeval", description = "Run LongMemEval benchmark (ICLR 2025)")
public class LongMemEvalBenchmark implements Runnable {

    private static final Logger log = Logger.getLogger(LongMemEvalBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Inject
    MemoryServiceClient memoryService;

    @Inject
    LlmAnswerGenerator answerGenerator;

    @Inject
    LlmJudge verdictJudge;

    @Inject
    BenchmarkConfig config;

    @Override
    public void run() {
        try {
            execute();
        } catch (Exception e) {
            log.error("Benchmark failed", e);
        }
    }

    private void execute() throws Exception {
        BenchmarkConfig.LongMemEval lmeConfig = config.longmemeval();

        log.info("Loading LongMemEval dataset...");
        List<LongMemEvalDataset.Question> allQuestions = LongMemEvalDataset.load(Path.of(lmeConfig.dataset()));
        log.infof("Loaded %d questions", allQuestions.size());

        // Filter by question types if specified
        if (lmeConfig.questionTypes().isPresent()) {
            Set<String> types = Arrays.stream(lmeConfig.questionTypes().get().split(","))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            if (!types.isEmpty()) {
                allQuestions = LongMemEvalDataset.filterByTypes(allQuestions, types);
                log.infof("Filtered to %d questions of types: %s", allQuestions.size(), types);
            }
        }

        // Sample questions
        int perType = lmeConfig.perType();
        List<LongMemEvalDataset.Question> questions;
        if (perType <= 0) {
            questions = allQuestions;
            log.infof("Running ALL %d questions", questions.size());
        } else {
            questions = LongMemEvalDataset.sampleStratified(allQuestions, perType, lmeConfig.seed());
            log.infof("Sampled %d questions (%d per type, seed=%d)", questions.size(), perType, lmeConfig.seed());
        }

        // Show question type distribution
        Map<String, Long> typeCounts = questions.stream()
                .collect(Collectors.groupingBy(LongMemEvalDataset.Question::questionType, Collectors.counting()));
        for (var entry : typeCounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            log.infof("  %s: %d questions", entry.getKey(), entry.getValue());
        }

        log.infof("Running benchmark: cognition=%s, topK=%d", config.cognition().enabled(), config.topK());

        List<BenchmarkResult> allResults = new ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            LongMemEvalDataset.Question question = questions.get(i);
            String userId = "longmemeval_" + question.questionId();

            int sessionCount = question.haystackSessions().size();
            int turnCount = question.haystackSessions().stream().mapToInt(List::size).sum();

            log.infof("=== Question %d/%d [%s] %s (%d sessions, %d turns) ===",
                    i + 1, questions.size(), question.questionType(), question.questionId(),
                    sessionCount, turnCount);

            // Phase 1: Ingest all haystack sessions
            ingestQuestion(userId, question);

            // Phase 2: Wait for cognition (if enabled)
            if (config.cognition().enabled()) {
                log.infof("Waiting for cognition for user=%s...", userId);
                int memCount = memoryService.waitForCognition(userId);
                log.infof("Cognition ready: %d memories for user=%s", memCount, userId);
            }

            // Phase 3: Search + answer + judge
            try {
                BenchmarkResult result = processQuestion(question, userId);
                allResults.add(result);

                String status = result.isCorrect() ? "CORRECT" : "WRONG";
                log.infof("  [%s] %s", status,
                        question.question().length() > 80
                                ? question.question().substring(0, 80) + "..."
                                : question.question());
            } catch (Exception e) {
                log.warnf("Failed to process question %s: %s", question.questionId(), e.getMessage());
            }

            // Running accuracy
            if (!allResults.isEmpty()) {
                long correct = allResults.stream().filter(BenchmarkResult::isCorrect).count();
                log.infof("  Running: %d/%d (%.1f%%)", correct, allResults.size(),
                        (double) correct / allResults.size() * 100);
            }
        }

        MetricsReport.Summary summary = MetricsReport.compute("LongMemEval", allResults);
        log.info(MetricsReport.format(summary));

        writeResults(allResults, summary);
    }

    private void ingestQuestion(String userId, LongMemEvalDataset.Question question) throws Exception {
        String convId = memoryService.createConversation(userId,
                "longmemeval-" + question.questionId());

        List<LongMemEvalDataset.SortedSession> sortedSessions =
                LongMemEvalDataset.sortSessionsChronologically(question);

        int entryCount = 0;
        for (LongMemEvalDataset.SortedSession session : sortedSessions) {
            for (LongMemEvalDataset.Turn turn : session.turns()) {
                if (turn.content() == null || turn.content().isBlank()) continue;
                String role = "user".equals(turn.role()) ? "USER" : "AI";
                memoryService.appendEntry(userId, convId, role, turn.content());
                entryCount++;
            }
        }
        log.infof("Ingested %d entries for question %s", entryCount, question.questionId());
    }

    private BenchmarkResult processQuestion(LongMemEvalDataset.Question question, String userId) throws Exception {
        long searchStart = System.nanoTime();
        List<MemoryServiceClient.MemoryResult> memories =
                memoryService.searchMemories(userId, question.question(), config.topK());
        double searchLatencyMs = (System.nanoTime() - searchStart) / 1_000_000.0;

        String memoriesText = formatMemories(memories);
        List<String> topMemoryTexts = memories.stream()
                .limit(5)
                .map(MemoryServiceClient.MemoryResult::memory)
                .toList();

        String generatedAnswer;
        try {
            generatedAnswer = answerGenerator.generateAnswer(memoriesText, question.question());
        } catch (Exception e) {
            generatedAnswer = "ERROR: " + e.getMessage();
        }

        String verdict = "WRONG";
        String reason = "";
        try {
            String judgeResponse = verdictJudge.judge(question.question(), question.answer(), generatedAnswer);
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = mapper.readValue(extractJson(judgeResponse), Map.class);
            verdict = parsed.getOrDefault("verdict", "WRONG");
            reason = parsed.getOrDefault("reason", "");
        } catch (Exception e) {
            reason = "Judge parsing failed: " + e.getMessage();
        }

        double score = "CORRECT".equalsIgnoreCase(verdict) ? 1.0 : 0.0;
        TextMetrics.Scores textScores = TextMetrics.compute(question.answer(), generatedAnswer);

        return new BenchmarkResult(
                question.questionId(), "longmemeval", question.questionType(),
                question.question(), question.answer(), generatedAnswer,
                verdict, reason, score, textScores.f1(), textScores.bleu(),
                searchLatencyMs, memories.size(), topMemoryTexts
        );
    }

    private String formatMemories(List<MemoryServiceClient.MemoryResult> memories) {
        if (memories.isEmpty()) {
            return "(No memories found)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < memories.size(); i++) {
            MemoryServiceClient.MemoryResult m = memories.get(i);
            sb.append(String.format("[%d] (score=%.2f) %s\n", i + 1, m.score(), m.memory()));
        }
        return sb.toString();
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private void writeResults(List<BenchmarkResult> results, MetricsReport.Summary summary) throws Exception {
        Path outDir = Path.of(config.outputDir());
        Files.createDirectories(outDir);

        String timestamp = Instant.now().toString().replace(":", "-").substring(0, 19);
        String mode = config.cognition().enabled() ? "cognition" : "substrate";
        Path outPath = outDir.resolve("longmemeval_" + mode + "_" + timestamp + ".json");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("metadata", Map.of(
                "benchmark", "longmemeval",
                "mode", mode,
                "cognition_enabled", config.cognition().enabled(),
                "top_k", config.topK(),
                "per_type", config.longmemeval().perType(),
                "seed", config.longmemeval().seed(),
                "timestamp", Instant.now().toString(),
                "dataset", config.longmemeval().dataset(),
                "total_questions", results.size()
        ));
        output.put("summary", Map.of(
                "overall_accuracy", summary.overallAccuracy(),
                "total_questions", summary.totalQuestions(),
                "total_correct", summary.totalCorrect(),
                "avg_search_latency_ms", summary.avgSearchLatencyMs(),
                "avg_memories_retrieved", summary.avgMemoriesRetrieved()
        ));
        output.put("by_category", summary.byCategory().stream().map(cm -> Map.of(
                "category", cm.name(),
                "accuracy", cm.accuracy(),
                "correct", cm.correct(),
                "total", cm.total()
        )).toList());
        output.put("results", results);

        mapper.writeValue(outPath.toFile(), output);
        log.infof("Results written to: %s", outPath);
    }
}

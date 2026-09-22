package io.github.memory.benchmark.locomo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.memory.benchmark.BenchmarkConfig;
import io.github.memory.benchmark.BenchmarkMode;
import io.github.memory.benchmark.BenchmarkResult;
import io.github.memory.benchmark.LlmAnswerGenerator;
import io.github.memory.benchmark.LlmJudge;
import io.github.memory.benchmark.LlmLongContextAnswerGenerator;
import io.github.memory.benchmark.LongContextBuilder;
import io.github.memory.benchmark.MemoryServiceClient;
import io.github.memory.benchmark.MetricsReport;
import io.github.memory.benchmark.TextMetrics;
import io.github.memory.benchmark.TokenCounter;
import io.github.memory.benchmark.TruncationMode;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command(name = "locomo", description = "Run LoCoMo benchmark (ACL 2024)")
public class LoCoMoBenchmark implements Runnable {

    private static final Logger log = Logger.getLogger(LoCoMoBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final Map<Integer, String> CATEGORY_NAMES = Map.of(
            1, "multi-hop",
            2, "temporal",
            3, "causal",
            4, "factual",
            5, "adversarial"
    );

    @Inject
    MemoryServiceClient memoryService;

    @Inject
    LlmAnswerGenerator answerGenerator;

    @Inject
    LlmLongContextAnswerGenerator longContextAnswerGenerator;

    @Inject
    LongContextBuilder contextBuilder;

    @Inject
    TokenCounter tokenCounter;

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

    private String determineMode() {
        BenchmarkMode mode = BenchmarkMode.determine(config);
        return switch (mode) {
            case LONG_CONTEXT -> "long-context";
            case COGNITION -> "cognition";
            case SUBSTRATE -> "substrate";
        };
    }

    private void logConfiguration(String mode) {
        log.info("=".repeat(80));
        log.info("BENCHMARK CONFIGURATION");
        log.info("=".repeat(80));
        log.infof("Mode: %s", mode);
        log.infof("Dataset: %s", config.dataset());
        log.infof("Conversations: %s", config.conversations());
        log.infof("Output directory: %s", config.outputDir());
        log.infof("Skip ingest: %s", config.skipIngest());
        log.infof("Top-K: %d", config.topK());
        
        if (mode.equals("cognition") || mode.equals("substrate")) {
            log.info("--- Retrieval Mode Settings ---");
            log.infof("Cognition enabled: %s", config.cognition().enabled());
            if (config.cognition().enabled()) {
                log.infof("Cognition namespace: %s", config.cognition().namespace());
                log.infof("Wait timeout: %d seconds", config.cognition().waitTimeoutSeconds());
                log.infof("Poll interval: %d seconds", config.cognition().pollIntervalSeconds());
                log.infof("Stable seconds: %d", config.cognition().stableSeconds());
            }
        }
        
        if (mode.equals("long-context")) {
            log.info("--- Long-Context Mode Settings ---");
            log.infof("Max tokens: %d", config.longContext().maxTokens());
            log.infof("Truncation: %s", config.longContext().truncation());
            log.infof("Encoding: %s", config.longContext().encoding());
            log.infof("Answer token budget: %d", config.longContext().answerTokenBudget());
            log.infof("Batch size: %d", config.longContext().batchSize());
            
            // Warn if encoding is not supported
            if (!TokenCounter.isSupported(config.longContext().encoding())) {
                log.warnf("Encoding '%s' not supported by JTokkit, will use character-based estimation", 
                    config.longContext().encoding());
            }
            
            log.info("Note: Memory-service and cognition-processor NOT required in this mode");
        }
        
        log.info("=".repeat(80));
    }

    private void execute() throws Exception {
        String mode = determineMode();
        logConfiguration(mode);
        
        log.info("Loading LoCoMo dataset...");
        List<LoCoMoDataset.Conversation> dataset = LoCoMoDataset.load(Path.of(config.dataset()));
        log.infof("Loaded %d conversations", dataset.size());

        Set<Integer> targetConvs = Arrays.stream(config.conversations().split(","))
                .map(String::strip)
                .map(Integer::parseInt)
                .collect(Collectors.toCollection(TreeSet::new));

        // Branch based on mode
        if (mode.equals("long-context")) {
            executeLongContext(dataset, targetConvs);
        } else {
            executeRetrieval(dataset, targetConvs, mode);
        }
    }

    private void executeLongContext(List<LoCoMoDataset.Conversation> dataset, Set<Integer> targetConvs) throws Exception {
        log.infof("Running long-context mode: conversations=%s", targetConvs);
        
        TruncationMode truncationMode = TruncationMode.fromString(config.longContext().truncation());
        List<BenchmarkResult> allResults = new ArrayList<>();

        for (int convIdx : targetConvs) {
            if (convIdx >= dataset.size()) {
                log.warnf("Conversation %d out of range (dataset has %d)", convIdx, dataset.size());
                continue;
            }

            LoCoMoDataset.Conversation conv = dataset.get(convIdx);
            log.infof("=== Conversation %d: %s & %s (%d sessions, %d questions) ===",
                    convIdx, conv.speakerA(), conv.speakerB(),
                    conv.sessions().size(), conv.questions().size());

            List<BenchmarkResult> convResults = processQuestionsLongContext(conv, convIdx, truncationMode);
            allResults.addAll(convResults);

            long correct = convResults.stream().filter(BenchmarkResult::isCorrect).count();
            log.infof("Conversation %d: %d/%d correct (%.1f%%)",
                    convIdx, correct, convResults.size(),
                    convResults.isEmpty() ? 0 : (double) correct / convResults.size() * 100);
        }

        logConfiguration("long-context");

        MetricsReport.Summary summary = MetricsReport.compute("LoCoMo", allResults);
        log.info(MetricsReport.format(summary));

        writeResults(allResults, summary, "long-context");
    }

    private void executeRetrieval(List<LoCoMoDataset.Conversation> dataset, Set<Integer> targetConvs, String mode) throws Exception {
        log.infof("Running retrieval mode: conversations=%s, cognition=%s, topK=%d",
                targetConvs, config.cognition().enabled(), config.topK());

        List<BenchmarkResult> allResults = new ArrayList<>();

        for (int convIdx : targetConvs) {
            if (convIdx >= dataset.size()) {
                log.warnf("Conversation %d out of range (dataset has %d)", convIdx, dataset.size());
                continue;
            }

            LoCoMoDataset.Conversation conv = dataset.get(convIdx);
            String userId = config.userIdFormat()
                    .map(format -> format.replace("{convIdx}", String.valueOf(convIdx))
                                         .replace("{speakerA}", conv.speakerA())
                                         .replace("{speakerB}", conv.speakerB()))
                    .orElse(conv.speakerA().toLowerCase().replaceAll("[^a-z0-9_-]", "_"));

            log.infof("=== Conversation %d: %s (USER) & %s (AI) (%d sessions, %d questions) ===",
                    convIdx, conv.speakerA(), conv.speakerB(),
                    conv.sessions().size(), conv.questions().size());

            if (config.skipIngest()) {
                log.infof("Skipping ingest (skip-ingest=true), using existing memories for user=%s", userId);
            } else {
                ingestConversation(userId, conv);

                if (config.cognition().enabled()) {
                    log.infof("Waiting for cognition processor to extract memories for user=%s...", userId);
                    int memCount = memoryService.waitForCognition(userId);
                    log.infof("Cognition ready: %d memories extracted for user=%s", memCount, userId);
                    
                    // Generate user profile after cognition is stable
                    try {
                        memoryService.generateUserProfile(userId);
                    } catch (Exception e) {
                        log.warnf("Failed to generate profile for user=%s: %s", userId, e.getMessage());
                    }
                }
            }

            List<BenchmarkResult> convResults = processQuestions(conv, userId);
            allResults.addAll(convResults);

            long correct = convResults.stream().filter(BenchmarkResult::isCorrect).count();
            log.infof("Conversation %d: %d/%d correct (%.1f%%)",
                    convIdx, correct, convResults.size(),
                    convResults.isEmpty() ? 0 : (double) correct / convResults.size() * 100);
        }

        logConfiguration(mode);

        MetricsReport.Summary summary = MetricsReport.compute("LoCoMo", allResults);
        log.info(MetricsReport.format(summary));

        writeResults(allResults, summary, mode);
    }

    private List<BenchmarkResult> processQuestionsLongContext(
            LoCoMoDataset.Conversation conv,
            int convIdx,
            TruncationMode truncationMode
    ) {
        List<BenchmarkResult> results = new ArrayList<>();

        for (LoCoMoDataset.QA qa : conv.questions()) {
            if (qa.category() == 5) continue;

            try {
                String questionId = "conv" + convIdx + "_q" + qa.index();
                String categoryName = CATEGORY_NAMES.getOrDefault(qa.category(), "unknown-" + qa.category());

                // Build context for this question
                String context = contextBuilder.buildContext(
                    conv,
                    qa.question(),
                    truncationMode,
                    config.longContext().maxTokens(),
                    config.longContext().answerTokenBudget(),
                    config.longContext().batchSize()
                );

                // Generate answer from context
                String generatedAnswer;
                try {
                    generatedAnswer = longContextAnswerGenerator.generateAnswer(context, qa.question());
                } catch (Exception e) {
                    generatedAnswer = "ERROR: " + e.getMessage();
                }

                // Judge the answer
                String verdict = "WRONG";
                String reason = "";
                try {
                    String judgeResponse = verdictJudge.judge(qa.question(), qa.answer(), generatedAnswer);
                    @SuppressWarnings("unchecked")
                    Map<String, String> parsed = mapper.readValue(
                            extractJson(judgeResponse), Map.class);
                    verdict = parsed.getOrDefault("verdict", "WRONG");
                    reason = parsed.getOrDefault("reason", "");
                } catch (Exception e) {
                    reason = "Judge parsing failed: " + e.getMessage();
                }

                double score = "CORRECT".equalsIgnoreCase(verdict) ? 1.0 : 0.0;
                TextMetrics.Scores textScores = TextMetrics.compute(qa.answer(), generatedAnswer);

                // Create result with zero retrieval metrics
                BenchmarkResult result = new BenchmarkResult(
                        questionId, "locomo", categoryName,
                        qa.question(), qa.answer(), generatedAnswer,
                        verdict, reason, score, textScores.f1(), textScores.bleu(),
                        0.0, // searchLatencyMs = 0 (no search)
                        0,   // memoriesRetrieved = 0 (no retrieval)
                        List.of() // topMemoryTexts = empty
                );
                results.add(result);

                String status = result.isCorrect() ? "CORRECT" : "WRONG";
                log.infof("  [%s] cat=%s q=%s", status, result.category(),
                        qa.question().length() > 60 ? qa.question().substring(0, 60) + "..." : qa.question());

                // Apply delay between questions if configured
                int delayMs = config.longContext().questionDelayMs();
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Question delay interrupted");
                    }
                }

            } catch (Exception e) {
                log.warnf("Failed to process question %d for conv %d: %s", qa.index(), convIdx, e.getMessage());
            }
        }

        return results;
    }

    private void ingestConversation(String userId, LoCoMoDataset.Conversation conv) throws Exception {
        String convId = memoryService.createConversation(userId, "locomo-conv-" + conv.index());

        int entryCount = 0;
        for (LoCoMoDataset.Session session : conv.sessions()) {
            String sessionDate = session.dateTime();
            for (LoCoMoDataset.Turn turn : session.turns()) {
                String role = turn.speaker().equals(conv.speakerA()) ? "USER" : "AI";
                String text = turn.speaker() + ": " + turn.text();
                if (sessionDate != null && !sessionDate.isBlank()) {
                    text = "[" + sessionDate + "] " + text;
                }
                memoryService.appendEntry(userId, convId, role, text);
                entryCount++;
            }
        }
        log.infof("Ingested %d entries into conversation %s", entryCount, convId);
    }

    private List<BenchmarkResult> processQuestions(LoCoMoDataset.Conversation conv, String userId) {
        List<BenchmarkResult> results = new ArrayList<>();

        for (LoCoMoDataset.QA qa : conv.questions()) {
            if (qa.category() == 5) continue;

            try {
                BenchmarkResult result = processQuestion(conv.index(), qa, userId);
                results.add(result);

                String status = result.isCorrect() ? "CORRECT" : "WRONG";
                log.infof("  [%s] cat=%s q=%s", status, result.category(),
                        qa.question().length() > 60 ? qa.question().substring(0, 60) + "..." : qa.question());

            } catch (Exception e) {
                log.warnf("Failed to process question %d for conv %d: %s", qa.index(), conv.index(), e.getMessage());
            }
        }

        return results;
    }

    private BenchmarkResult processQuestion(int convIdx, LoCoMoDataset.QA qa, String userId) throws Exception {
        String questionId = "conv" + convIdx + "_q" + qa.index();
        String categoryName = CATEGORY_NAMES.getOrDefault(qa.category(), "unknown-" + qa.category());

        long searchStart = System.nanoTime();
        List<MemoryServiceClient.MemoryResult> memories = memoryService.searchMemories(userId, qa.question(), config.topK());
        double searchLatencyMs = (System.nanoTime() - searchStart) / 1_000_000.0;

        // Log question and retrieved memories
        log.infof("Question: %s", qa.question());
        log.infof("Retrieved %d memories (search took %.2fms):", memories.size(), searchLatencyMs);
        for (int i = 0; i < Math.min(memories.size(), 10); i++) {
            MemoryServiceClient.MemoryResult m = memories.get(i);
            log.infof("  [%d] (score=%.3f) %s", i + 1, m.score(), 
                    m.memory().length() > 150 ? m.memory().substring(0, 150) + "..." : m.memory());
        }
        if (memories.size() > 10) {
            log.infof("  ... and %d more memories", memories.size() - 10);
        }

        String memoriesText = formatMemories(memories);
        List<String> topMemoryTexts = memories.stream()
                .limit(5)
                .map(MemoryServiceClient.MemoryResult::memory)
                .toList();

        String generatedAnswer;
        try {
            generatedAnswer = answerGenerator.generateAnswer(memoriesText, qa.question());
        } catch (Exception e) {
            generatedAnswer = "ERROR: " + e.getMessage();
        }

        String verdict = "WRONG";
        String reason = "";
        try {
            String judgeResponse = verdictJudge.judge(qa.question(), qa.answer(), generatedAnswer);
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = mapper.readValue(
                    extractJson(judgeResponse), Map.class);
            verdict = parsed.getOrDefault("verdict", "WRONG");
            reason = parsed.getOrDefault("reason", "");
        } catch (Exception e) {
            reason = "Judge parsing failed: " + e.getMessage();
        }

        double score = "CORRECT".equalsIgnoreCase(verdict) ? 1.0 : 0.0;
        TextMetrics.Scores textScores = TextMetrics.compute(qa.answer(), generatedAnswer);

        return new BenchmarkResult(
                questionId, "locomo", categoryName,
                qa.question(), qa.answer(), generatedAnswer,
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

    private void writeResults(List<BenchmarkResult> results, MetricsReport.Summary summary, String mode) throws Exception {
        Path outDir = Path.of(config.outputDir());
        Files.createDirectories(outDir);

        String timestamp = Instant.now().toString().replace(":", "-").substring(0, 19);
        Path outPath = outDir.resolve("locomo_" + mode + "_" + timestamp + ".json");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("benchmark", "locomo");
        metadata.put("mode", mode);
        metadata.put("timestamp", Instant.now().toString());
        metadata.put("dataset", config.dataset());
        
        if (mode.equals("long-context")) {
            metadata.put("long_context", Map.of(
                    "max_tokens", config.longContext().maxTokens(),
                    "truncation", config.longContext().truncation(),
                    "encoding", config.longContext().encoding(),
                    "answer_token_budget", config.longContext().answerTokenBudget(),
                    "batch_size", config.longContext().batchSize()
            ));
        } else {
            metadata.put("cognition_enabled", config.cognition().enabled());
            metadata.put("top_k", config.topK());
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("metadata", metadata);
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

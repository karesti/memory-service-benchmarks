package io.github.chirino.memory.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@QuarkusMain
public class LocomoBenchmark implements QuarkusApplication {

    private static final Logger log = Logger.getLogger(LocomoBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Inject
    MemoryServiceClient memoryService;

    @Inject
    LlmAnswerGenerator answerGenerator;

    @Inject
    LlmJudge verdictJudge;

    @ConfigProperty(name = "benchmark.dataset", defaultValue = "datasets/locomo10.json")
    String datasetPath;

    @ConfigProperty(name = "benchmark.conversations", defaultValue = "0,1,2,3,4,5,6,7,8,9")
    String conversationsConfig;

    @ConfigProperty(name = "benchmark.top-k", defaultValue = "50")
    int topK;

    @ConfigProperty(name = "benchmark.cognition.enabled", defaultValue = "true")
    boolean cognitionEnabled;

    @ConfigProperty(name = "benchmark.output-dir", defaultValue = "results")
    String outputDir;

    @Override
    public int run(String... args) {
        try {
            execute();
            return 0;
        } catch (Exception e) {
            log.error("Benchmark failed", e);
            return 1;
        }
    }

    private void execute() throws Exception {
        log.info("Loading LoCoMo dataset...");
        List<LocomoDataset.Conversation> dataset = LocomoDataset.load(Path.of(datasetPath));
        log.infof("Loaded %d conversations", dataset.size());

        Set<Integer> targetConvs = Arrays.stream(conversationsConfig.split(","))
                .map(String::strip)
                .map(Integer::parseInt)
                .collect(Collectors.toCollection(TreeSet::new));

        log.infof("Running benchmark: conversations=%s, cognition=%s, topK=%d",
                targetConvs, cognitionEnabled, topK);

        List<BenchmarkResult> allResults = new ArrayList<>();

        for (int convIdx : targetConvs) {
            if (convIdx >= dataset.size()) {
                log.warnf("Conversation %d out of range (dataset has %d)", convIdx, dataset.size());
                continue;
            }

            LocomoDataset.Conversation conv = dataset.get(convIdx);
            String userId = "locomo_" + convIdx;

            log.infof("=== Conversation %d: %s & %s (%d sessions, %d questions) ===",
                    convIdx, conv.speakerA(), conv.speakerB(),
                    conv.sessions().size(), conv.questions().size());

            // Phase 1: Ingest conversation into memory-service
            ingestConversation(userId, conv);

            // Phase 2: Wait for cognition processor (if enabled)
            if (cognitionEnabled) {
                log.infof("Waiting for cognition processor to extract memories for user=%s...", userId);
                int memCount = memoryService.waitForCognition(userId);
                log.infof("Cognition ready: %d memories extracted for user=%s", memCount, userId);
            }

            // Phase 3: Process questions
            List<BenchmarkResult> convResults = processQuestions(conv, userId);
            allResults.addAll(convResults);

            // Per-conversation summary
            long correct = convResults.stream().filter(BenchmarkResult::isCorrect).count();
            log.infof("Conversation %d: %d/%d correct (%.1f%%)",
                    convIdx, correct, convResults.size(),
                    convResults.isEmpty() ? 0 : (double) correct / convResults.size() * 100);
        }

        // Compute and display metrics
        MetricsReport.Summary summary = MetricsReport.compute(allResults);
        log.info(MetricsReport.format(summary));

        // Write results
        writeResults(allResults, summary);
    }

    private void ingestConversation(String userId, LocomoDataset.Conversation conv) throws Exception {
        String convId = memoryService.createConversation(userId, "locomo-conv-" + conv.index());

        int entryCount = 0;
        for (LocomoDataset.Session session : conv.sessions()) {
            for (LocomoDataset.Turn turn : session.turns()) {
                String role = turn.speaker().equals(conv.speakerA()) ? "USER" : "AI";
                String text = turn.speaker() + ": " + turn.text();
                memoryService.appendEntry(userId, convId, role, text);
                entryCount++;
            }
        }
        log.infof("Ingested %d entries into conversation %s", entryCount, convId);
    }

    private List<BenchmarkResult> processQuestions(LocomoDataset.Conversation conv, String userId) {
        List<BenchmarkResult> results = new ArrayList<>();

        for (LocomoDataset.QA qa : conv.questions()) {
            // Skip category 5 (adversarial/unanswerable) for now — needs special handling
            if (qa.category() == 5) continue;

            try {
                BenchmarkResult result = processQuestion(conv.index(), qa, userId);
                results.add(result);

                String status = result.isCorrect() ? "CORRECT" : "WRONG";
                log.infof("  [%s] cat=%d q=%s", status, qa.category(),
                        qa.question().length() > 60 ? qa.question().substring(0, 60) + "..." : qa.question());

            } catch (Exception e) {
                log.warnf("Failed to process question %d for conv %d: %s", qa.index(), conv.index(), e.getMessage());
            }
        }

        return results;
    }

    private BenchmarkResult processQuestion(int convIdx, LocomoDataset.QA qa, String userId) throws Exception {
        String questionId = "conv" + convIdx + "_q" + qa.index();

        // Search memories
        long searchStart = System.nanoTime();
        List<MemoryServiceClient.MemoryResult> memories = memoryService.searchMemories(userId, qa.question(), topK);
        double searchLatencyMs = (System.nanoTime() - searchStart) / 1_000_000.0;

        // Format memories for LLM
        String memoriesText = formatMemories(memories);
        List<String> topMemoryTexts = memories.stream()
                .limit(5)
                .map(MemoryServiceClient.MemoryResult::memory)
                .toList();

        // Generate answer
        String generatedAnswer;
        try {
            generatedAnswer = answerGenerator.generateAnswer(memoriesText, qa.question());
        } catch (Exception e) {
            generatedAnswer = "ERROR: " + e.getMessage();
        }

        // Judge answer
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

        return new BenchmarkResult(
                questionId, convIdx, qa.category(),
                qa.question(), qa.answer(), generatedAnswer,
                verdict, reason, searchLatencyMs,
                memories.size(), topMemoryTexts
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
        Path outDir = Path.of(outputDir);
        Files.createDirectories(outDir);

        String timestamp = Instant.now().toString().replace(":", "-").substring(0, 19);
        String mode = cognitionEnabled ? "cognition" : "substrate";
        Path outPath = outDir.resolve("locomo_" + mode + "_" + timestamp + ".json");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("metadata", Map.of(
                "benchmark", "locomo",
                "mode", mode,
                "cognition_enabled", cognitionEnabled,
                "top_k", topK,
                "timestamp", Instant.now().toString(),
                "dataset", datasetPath
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

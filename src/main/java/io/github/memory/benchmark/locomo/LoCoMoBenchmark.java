package io.github.memory.benchmark.locomo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.memory.benchmark.BenchmarkConfig;
import io.github.memory.benchmark.BenchmarkResult;
import io.github.memory.benchmark.LlmAnswerGenerator;
import io.github.memory.benchmark.LlmJudge;
import io.github.memory.benchmark.MemoryServiceClient;
import io.github.memory.benchmark.MetricsReport;
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
        log.info("Loading LoCoMo dataset...");
        List<LoCoMoDataset.Conversation> dataset = LoCoMoDataset.load(Path.of(config.dataset()));
        log.infof("Loaded %d conversations", dataset.size());

        Set<Integer> targetConvs = Arrays.stream(config.conversations().split(","))
                .map(String::strip)
                .map(Integer::parseInt)
                .collect(Collectors.toCollection(TreeSet::new));

        log.infof("Running benchmark: conversations=%s, cognition=%s, topK=%d",
                targetConvs, config.cognition().enabled(), config.topK());

        List<BenchmarkResult> allResults = new ArrayList<>();

        for (int convIdx : targetConvs) {
            if (convIdx >= dataset.size()) {
                log.warnf("Conversation %d out of range (dataset has %d)", convIdx, dataset.size());
                continue;
            }

            LoCoMoDataset.Conversation conv = dataset.get(convIdx);
            String userId = "locomo_" + convIdx;

            log.infof("=== Conversation %d: %s & %s (%d sessions, %d questions) ===",
                    convIdx, conv.speakerA(), conv.speakerB(),
                    conv.sessions().size(), conv.questions().size());

            ingestConversation(userId, conv);

            if (config.cognition().enabled()) {
                log.infof("Waiting for cognition processor to extract memories for user=%s...", userId);
                int memCount = memoryService.waitForCognition(userId);
                log.infof("Cognition ready: %d memories extracted for user=%s", memCount, userId);
            }

            List<BenchmarkResult> convResults = processQuestions(conv, userId);
            allResults.addAll(convResults);

            long correct = convResults.stream().filter(BenchmarkResult::isCorrect).count();
            log.infof("Conversation %d: %d/%d correct (%.1f%%)",
                    convIdx, correct, convResults.size(),
                    convResults.isEmpty() ? 0 : (double) correct / convResults.size() * 100);
        }

        MetricsReport.Summary summary = MetricsReport.compute("LoCoMo", allResults);
        log.info(MetricsReport.format(summary));

        writeResults(allResults, summary);
    }

    private void ingestConversation(String userId, LoCoMoDataset.Conversation conv) throws Exception {
        String convId = memoryService.createConversation(userId, "locomo-conv-" + conv.index());

        int entryCount = 0;
        for (LoCoMoDataset.Session session : conv.sessions()) {
            for (LoCoMoDataset.Turn turn : session.turns()) {
                String role = turn.speaker().equals(conv.speakerA()) ? "USER" : "AI";
                String text = turn.speaker() + ": " + turn.text();
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

        return new BenchmarkResult(
                questionId, "locomo", categoryName,
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
        Path outDir = Path.of(config.outputDir());
        Files.createDirectories(outDir);

        String timestamp = Instant.now().toString().replace(":", "-").substring(0, 19);
        String mode = config.cognition().enabled() ? "cognition" : "substrate";
        Path outPath = outDir.resolve("locomo_" + mode + "_" + timestamp + ".json");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("metadata", Map.of(
                "benchmark", "locomo",
                "mode", mode,
                "cognition_enabled", config.cognition().enabled(),
                "top_k", config.topK(),
                "timestamp", Instant.now().toString(),
                "dataset", config.dataset()
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

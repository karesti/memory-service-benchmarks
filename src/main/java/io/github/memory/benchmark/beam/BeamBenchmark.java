package io.github.memory.benchmark.beam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.github.memory.benchmark.BenchmarkConfig;
import io.github.memory.benchmark.BenchmarkResult;
import io.github.memory.benchmark.LlmAnswerGenerator;
import io.github.memory.benchmark.LlmNuggetJudge;
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

@CommandLine.Command(name = "beam", description = "Run BEAM benchmark (ICLR 2026)")
public class BeamBenchmark implements Runnable {

    private static final Logger log = Logger.getLogger(BeamBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Inject
    MemoryServiceClient memoryService;

    @Inject
    LlmAnswerGenerator answerGenerator;

    @Inject
    LlmNuggetJudge nuggetJudge;

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
        BenchmarkConfig.Beam beamConfig = config.beam();

        List<String> chatSizes = Arrays.stream(beamConfig.chatSizes().split(","))
                .map(String::strip)
                .toList();

        log.infof("Loading BEAM dataset from %s (sizes=%s, maxChats=%d)...",
                beamConfig.datasetDir(), chatSizes, beamConfig.maxChats());

        List<BeamDataset.Chat> chats = BeamDataset.loadChats(
                Path.of(beamConfig.datasetDir()), chatSizes, beamConfig.maxChats());

        log.infof("Loaded %d chats", chats.size());

        int totalQuestions = chats.stream().mapToInt(c -> c.probingQuestions().size()).sum();
        log.infof("Running benchmark: %d chats, %d questions, cognition=%s, topK=%d",
                chats.size(), totalQuestions, config.cognition().enabled(), config.topK());

        List<BenchmarkResult> allResults = new ArrayList<>();
        int questionNum = 0;

        for (int chatIdx = 0; chatIdx < chats.size(); chatIdx++) {
            BeamDataset.Chat chat = chats.get(chatIdx);
            String userId = "beam_" + chat.size() + "_" + chat.chatId();

            log.infof("=== Chat %d/%d [%s/%s] (%d turns, %d questions) ===",
                    chatIdx + 1, chats.size(), chat.size(), chat.chatId(),
                    chat.turns().size(), chat.probingQuestions().size());

            // Phase 1: Ingest
            ingestChat(userId, chat);

            // Phase 2: Wait for cognition
            if (config.cognition().enabled()) {
                log.infof("Waiting for cognition for user=%s...", userId);
                int memCount = memoryService.waitForCognition(userId);
                log.infof("Cognition ready: %d memories for user=%s", memCount, userId);
            }

            // Phase 3: Process questions
            for (BeamDataset.ProbingQuestion pq : chat.probingQuestions()) {
                questionNum++;
                try {
                    BenchmarkResult result = processQuestion(chat, pq, userId, questionNum, totalQuestions);
                    allResults.add(result);

                    log.infof("  [%.1f] %s: %s",
                            result.score(), pq.questionType(),
                            pq.question().length() > 70
                                    ? pq.question().substring(0, 70) + "..."
                                    : pq.question());
                } catch (Exception e) {
                    log.warnf("Failed question %s in chat %s/%s: %s",
                            pq.questionType(), chat.size(), chat.chatId(), e.getMessage());
                }
            }

            // Running accuracy
            if (!allResults.isEmpty()) {
                double avgScore = allResults.stream().mapToDouble(BenchmarkResult::score).average().orElse(0);
                log.infof("  Running: %.1f%% avg score (%d questions)", avgScore * 100, allResults.size());
            }
        }

        MetricsReport.Summary summary = MetricsReport.compute("BEAM", allResults);
        log.info(MetricsReport.format(summary));

        writeResults(allResults, summary);
    }

    private void ingestChat(String userId, BeamDataset.Chat chat) throws Exception {
        String convId = memoryService.createConversation(userId,
                "beam-" + chat.size() + "-" + chat.chatId());

        int entryCount = 0;
        for (BeamDataset.Turn turn : chat.turns()) {
            String role = "user".equalsIgnoreCase(turn.role()) ? "USER" : "AI";
            memoryService.appendEntry(userId, convId, role, turn.content());
            entryCount++;
        }
        log.infof("Ingested %d entries for chat %s/%s", entryCount, chat.size(), chat.chatId());
    }

    private BenchmarkResult processQuestion(BeamDataset.Chat chat, BeamDataset.ProbingQuestion pq,
                                             String userId, int questionNum, int totalQuestions) throws Exception {
        String questionId = "beam_" + chat.size() + "_" + chat.chatId() + "_" + questionNum;

        // Search
        long searchStart = System.nanoTime();
        List<MemoryServiceClient.MemoryResult> memories =
                memoryService.searchMemories(userId, pq.question(), config.topK());
        double searchLatencyMs = (System.nanoTime() - searchStart) / 1_000_000.0;

        String memoriesText = formatMemories(memories);
        List<String> topMemoryTexts = memories.stream()
                .limit(5)
                .map(MemoryServiceClient.MemoryResult::memory)
                .toList();

        // Generate answer
        String generatedAnswer;
        Integer answerInTokens = null;
        Integer answerOutTokens = null;
        try {
            Result<String> answerResp = answerGenerator.generateAnswer(memoriesText, pq.question());
            generatedAnswer = answerResp.content() != null ? answerResp.content() : "";
            TokenUsage tu = answerResp.tokenUsage();
            if (tu != null) {
                answerInTokens = tu.inputTokenCount();
                answerOutTokens = tu.outputTokenCount();
            }
        } catch (Exception e) {
            generatedAnswer = "ERROR: " + e.getMessage();
        }

        // Judge with rubric nuggets
        double score = 0.0;
        StringBuilder reasons = new StringBuilder();
        Integer judgeInTokens = null;
        Integer judgeOutTokens = null;

        if (pq.rubric().isEmpty()) {
            score = generatedAnswer.startsWith("ERROR") ? 0.0 : 0.5;
            reasons.append("No rubric — default score");
        } else {
            double totalNuggetScore = 0.0;
            int totalJudgeIn = 0;
            int totalJudgeOut = 0;
            boolean anyJudgeTokens = false;

            for (int i = 0; i < pq.rubric().size(); i++) {
                String rubricItem = pq.rubric().get(i);
                double nuggetScore = 0.0;
                String nuggetReason = "";

                try {
                    Result<String> judgeResp = nuggetJudge.judgeNugget(pq.question(), rubricItem, generatedAnswer);
                    String judgeResponse = judgeResp.content() != null ? judgeResp.content() : "";
                    TokenUsage tu = judgeResp.tokenUsage();
                    if (tu != null) {
                        anyJudgeTokens = true;
                        if (tu.inputTokenCount() != null) totalJudgeIn += tu.inputTokenCount();
                        if (tu.outputTokenCount() != null) totalJudgeOut += tu.outputTokenCount();
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = mapper.readValue(extractJson(judgeResponse), Map.class);
                    Object scoreVal = parsed.get("score");
                    if (scoreVal instanceof Number n) {
                        nuggetScore = n.doubleValue();
                    }
                    nuggetReason = (String) parsed.getOrDefault("reason", "");
                } catch (Exception e) {
                    nuggetReason = "Judge failed: " + e.getMessage();
                }

                totalNuggetScore += nuggetScore;
                if (i > 0) reasons.append(" | ");
                reasons.append(String.format("[%.1f] %s", nuggetScore, nuggetReason));
            }
            score = totalNuggetScore / pq.rubric().size();
            if (anyJudgeTokens) {
                judgeInTokens = totalJudgeIn;
                judgeOutTokens = totalJudgeOut;
            }
        }

        int totalIn = (answerInTokens != null ? answerInTokens : 0) + (judgeInTokens != null ? judgeInTokens : 0);
        int totalOut = (answerOutTokens != null ? answerOutTokens : 0) + (judgeOutTokens != null ? judgeOutTokens : 0);
        Integer totalTokens = (answerInTokens != null || judgeInTokens != null || answerOutTokens != null || judgeOutTokens != null)
                ? (totalIn + totalOut) : null;

        String verdict = score >= 0.5 ? "CORRECT" : "WRONG";
        TextMetrics.Scores textScores = TextMetrics.compute(pq.answer(), generatedAnswer);

        return new BenchmarkResult(
                questionId, "beam", pq.questionType(),
                pq.question(), pq.answer(), generatedAnswer,
                verdict, reasons.toString(), score, textScores.f1(), textScores.bleu(),
                searchLatencyMs, memories.size(), topMemoryTexts,
                answerInTokens, answerOutTokens, judgeInTokens, judgeOutTokens, totalTokens
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
        Path outPath = outDir.resolve("beam_" + mode + "_" + timestamp + ".json");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("metadata", Map.of(
                "benchmark", "beam",
                "mode", mode,
                "cognition_enabled", config.cognition().enabled(),
                "top_k", config.topK(),
                "chat_sizes", config.beam().chatSizes(),
                "max_chats", config.beam().maxChats(),
                "timestamp", Instant.now().toString(),
                "total_questions", results.size()
        ));
        Map<String, Object> summaryMap = new LinkedHashMap<>();
        summaryMap.put("overall_accuracy", summary.overallAccuracy());
        summaryMap.put("total_questions", summary.totalQuestions());
        summaryMap.put("total_correct", summary.totalCorrect());
        summaryMap.put("avg_search_latency_ms", summary.avgSearchLatencyMs());
        summaryMap.put("avg_memories_retrieved", summary.avgMemoriesRetrieved());
        summaryMap.put("total_input_tokens", summary.totalInputTokens());
        summaryMap.put("total_output_tokens", summary.totalOutputTokens());
        summaryMap.put("total_tokens", summary.totalTokens());
        summaryMap.put("avg_input_tokens_per_question", summary.avgInputTokensPerQuestion());
        summaryMap.put("avg_output_tokens_per_question", summary.avgOutputTokensPerQuestion());
        output.put("summary", summaryMap);
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

package io.github.memory.benchmark.beam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class BeamDataset {

    private static final ObjectMapper mapper = new ObjectMapper();

    public record Turn(String role, String content) {}

    public record ProbingQuestion(
            String questionType,
            String question,
            String answer,
            List<String> rubric,
            String difficulty
    ) {}

    public record Chat(
            String chatId,
            String size,
            List<Turn> turns,
            List<ProbingQuestion> probingQuestions
    ) {}

    public static List<Chat> loadChats(Path datasetDir, List<String> chatSizes, int maxChats) throws IOException {
        List<Chat> chats = new ArrayList<>();

        for (String size : chatSizes) {
            Path sizeDir = datasetDir.resolve(size);
            if (!Files.isDirectory(sizeDir)) {
                continue;
            }

            List<Path> chatDirs;
            try (Stream<Path> stream = Files.list(sizeDir)) {
                chatDirs = stream
                        .filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> parseChatId(p.getFileName().toString())))
                        .toList();
            }

            int limit = maxChats > 0 ? Math.min(maxChats, chatDirs.size()) : chatDirs.size();

            for (int i = 0; i < limit; i++) {
                Path chatDir = chatDirs.get(i);
                String chatId = chatDir.getFileName().toString();

                Chat chat = loadChat(chatDir, chatId, size);
                if (chat != null) {
                    chats.add(chat);
                }
            }
        }

        return chats;
    }

    @SuppressWarnings("unchecked")
    private static Chat loadChat(Path chatDir, String chatId, String size) throws IOException {
        Path chatFile = chatDir.resolve("chat.json");
        Path questionsFile = chatDir.resolve("probing_questions").resolve("probing_questions.json");

        if (!Files.exists(chatFile) || !Files.exists(questionsFile)) {
            return null;
        }

        // Parse chat.json — list of batches with nested turn lists
        List<Object> rawBatches = mapper.readValue(chatFile.toFile(), new TypeReference<>() {});
        List<Turn> turns = new ArrayList<>();

        for (Object rawBatch : rawBatches) {
            if (rawBatch instanceof Map<?, ?> batchMap) {
                Object rawTurns = batchMap.get("turns");
                if (rawTurns instanceof List<?> turnList) {
                    flattenTurns(turnList, turns);
                }
            } else if (rawBatch instanceof List<?> turnList) {
                flattenTurns(turnList, turns);
            }
        }

        // Parse probing_questions.json — dict of type → list of questions
        Map<String, List<Map<String, Object>>> rawQuestions = mapper.readValue(
                questionsFile.toFile(), new TypeReference<>() {});

        List<ProbingQuestion> probingQuestions = new ArrayList<>();
        for (var entry : rawQuestions.entrySet()) {
            String questionType = entry.getKey();
            for (Map<String, Object> rawQ : entry.getValue()) {
                String question = (String) rawQ.get("question");
                String answer = extractAnswer(rawQ);
                List<String> rubric = rawQ.containsKey("rubric")
                        ? mapper.convertValue(rawQ.get("rubric"), new TypeReference<>() {})
                        : List.of();
                String difficulty = (String) rawQ.getOrDefault("difficulty", "unknown");

                probingQuestions.add(new ProbingQuestion(questionType, question, answer, rubric, difficulty));
            }
        }

        return new Chat(chatId, size, turns, probingQuestions);
    }

    @SuppressWarnings("unchecked")
    private static void flattenTurns(List<?> items, List<Turn> turns) {
        for (Object item : items) {
            if (item instanceof Map<?, ?> turnMap) {
                String role = (String) turnMap.get("role");
                String content = (String) turnMap.get("content");
                if (role != null && content != null && !content.isBlank()) {
                    turns.add(new Turn(role, content));
                }
            } else if (item instanceof List<?> nested) {
                flattenTurns(nested, turns);
            }
        }
    }

    private static String extractAnswer(Map<String, Object> rawQ) {
        for (String key : List.of("answer", "ideal_answer", "ideal_response", "ideal_summary")) {
            Object val = rawQ.get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    private static int parseChatId(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}

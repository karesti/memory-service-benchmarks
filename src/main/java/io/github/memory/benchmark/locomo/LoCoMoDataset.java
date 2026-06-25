package io.github.memory.benchmark.locomo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoCoMoDataset {

    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("h:mm a 'on' d MMMM, yyyy")
            .toFormatter(Locale.ENGLISH);

    private static final Pattern SESSION_KEY = Pattern.compile("^session_(\\d+)$");

    public record Turn(String speaker, String text, String diaId) {}

    public record Session(String key, String dateTime, List<Turn> turns) {}

    public record QA(int index, String question, String answer, int category, List<String> evidence) {}

    public record Conversation(
            int index,
            String speakerA,
            String speakerB,
            List<Session> sessions,
            List<QA> questions
    ) {}

    public static List<Conversation> load(Path datasetPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> raw = mapper.readValue(
                datasetPath.toFile(),
                new TypeReference<>() {}
        );

        List<Conversation> conversations = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            conversations.add(parseConversation(i, raw.get(i)));
        }
        return conversations;
    }

    @SuppressWarnings("unchecked")
    private static Conversation parseConversation(int index, Map<String, Object> raw) {
        Map<String, Object> conv = (Map<String, Object>) raw.get("conversation");
        String speakerA = (String) conv.get("speaker_a");
        String speakerB = (String) conv.get("speaker_b");

        // Parse sessions
        List<Session> sessions = new ArrayList<>();
        for (String key : conv.keySet()) {
            Matcher m = SESSION_KEY.matcher(key);
            if (!m.matches()) continue;
            String dateKey = key + "_date_time";
            String dateTime = (String) conv.getOrDefault(dateKey, "");
            List<Map<String, Object>> turns = (List<Map<String, Object>>) conv.get(key);
            if (turns == null) continue;

            List<Turn> parsedTurns = new ArrayList<>();
            for (Map<String, Object> turn : turns) {
                String speaker = (String) turn.getOrDefault("speaker", "");
                String text = (String) turn.getOrDefault("text", "");
                String diaId = (String) turn.getOrDefault("dia_id", "");

                // Handle image content
                String blip = (String) turn.getOrDefault("blip_caption", "");
                String query = (String) turn.getOrDefault("query", "");
                if (query != null && !query.isEmpty() && blip != null && !blip.isEmpty()) {
                    text += " [Sharing image - query: " + query + ". The image shows: " + blip + "]";
                } else if (query != null && !query.isEmpty()) {
                    text += " [Sharing image - query for: " + query + "]";
                } else if (blip != null && !blip.isEmpty()) {
                    text += " [Sharing image that shows: " + blip + "]";
                }

                if (!text.isBlank()) {
                    parsedTurns.add(new Turn(speaker, text.strip(), diaId));
                }
            }
            sessions.add(new Session(key, dateTime, parsedTurns));
        }

        // Sort sessions chronologically
        sessions.sort((a, b) -> {
            LocalDateTime da = parseDate(a.dateTime());
            LocalDateTime db = parseDate(b.dateTime());
            if (da != null && db != null) return da.compareTo(db);
            if (da != null) return -1;
            if (db != null) return 1;
            return extractNum(a.key()) - extractNum(b.key());
        });

        // Parse QA
        List<QA> questions = new ArrayList<>();
        List<Map<String, Object>> qaList = (List<Map<String, Object>>) raw.getOrDefault("qa",
                raw.getOrDefault("qa_pairs", List.of()));
        for (int qi = 0; qi < qaList.size(); qi++) {
            Map<String, Object> qa = qaList.get(qi);
            String question = (String) qa.get("question");
            String answer = String.valueOf(qa.get("answer"));
            int category = ((Number) qa.get("category")).intValue();
            List<String> evidence = (List<String>) qa.getOrDefault("evidence", List.of());
            questions.add(new QA(qi, question, answer, category, evidence));
        }

        return new Conversation(index, speakerA, speakerB, sessions, questions);
    }

    private static LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDateTime.parse(dateStr, DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private static int extractNum(String sessionKey) {
        Matcher m = Pattern.compile("\\d+").matcher(sessionKey);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    public static String formatTurnsAsMessages(Conversation conv) {
        StringBuilder sb = new StringBuilder();
        for (Session session : conv.sessions()) {
            if (!session.dateTime().isBlank()) {
                sb.append("[Session: ").append(session.dateTime()).append("]\n");
            }
            for (Turn turn : session.turns()) {
                sb.append(turn.speaker()).append(": ").append(turn.text()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}

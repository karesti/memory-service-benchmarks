package io.github.memory.benchmark.longmemeval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LongMemEvalDataset {

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{4})/(\\d{2})/(\\d{2})\\s+\\([A-Za-z]+\\)\\s+(\\d{2}):(\\d{2})");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(String role, String content,
                       @JsonProperty("has_answer") Boolean hasAnswer) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(
            @JsonProperty("question_id") String questionId,
            @JsonProperty("question_type") String questionType,
            String question,
            String answer,
            @JsonProperty("question_date") String questionDate,
            @JsonProperty("answer_session_ids") List<String> answerSessionIds,
            @JsonProperty("haystack_dates") List<String> haystackDates,
            @JsonProperty("haystack_session_ids") List<String> haystackSessionIds,
            @JsonProperty("haystack_sessions") List<List<Turn>> haystackSessions
    ) {
        public boolean isAbstention() {
            return questionId != null && questionId.endsWith("_abs");
        }
    }

    public static List<Question> load(Path datasetPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(datasetPath.toFile(), new TypeReference<>() {});
    }

    public static List<Question> sampleStratified(List<Question> questions, int perType, int seed) {
        Map<String, List<Question>> byType = questions.stream()
                .collect(Collectors.groupingBy(Question::questionType));

        Random rng = new Random(seed);
        List<Question> sampled = new ArrayList<>();

        for (String type : byType.keySet().stream().sorted().toList()) {
            List<Question> group = new ArrayList<>(byType.get(type));
            group.sort(Comparator.comparing(Question::questionId));
            int n = Math.min(perType, group.size());
            Collections.shuffle(group, rng);
            sampled.addAll(group.subList(0, n));
        }

        sampled.sort(Comparator.comparing(Question::questionId));
        return sampled;
    }

    public static List<Question> filterByTypes(List<Question> questions, Set<String> types) {
        if (types.isEmpty()) return questions;
        return questions.stream()
                .filter(q -> types.contains(q.questionType()))
                .toList();
    }

    public static List<SortedSession> sortSessionsChronologically(Question question) {
        List<SortedSession> sessions = new ArrayList<>();
        for (int i = 0; i < question.haystackSessions().size(); i++) {
            String sessionId = i < question.haystackSessionIds().size()
                    ? question.haystackSessionIds().get(i) : "session_" + i;
            String date = i < question.haystackDates().size()
                    ? question.haystackDates().get(i) : "";
            sessions.add(new SortedSession(sessionId, date, question.haystackSessions().get(i)));
        }

        sessions.sort(Comparator.comparing(s -> parseDateToSortKey(s.date())));
        return sessions;
    }

    public record SortedSession(String sessionId, String date, List<Turn> turns) {}

    private static long parseDateToSortKey(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return Long.MAX_VALUE;
        Matcher m = DATE_PATTERN.matcher(dateStr);
        if (!m.find()) return Long.MAX_VALUE;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        return (long) year * 100_000_000L + month * 1_000_000L + day * 10_000L + hour * 100L + minute;
    }
}

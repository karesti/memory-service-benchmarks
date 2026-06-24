package io.github.chirino.memory.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class MemoryServiceClient {

    private static final Logger log = Logger.getLogger(MemoryServiceClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @ConfigProperty(name = "memory-service.url", defaultValue = "http://localhost:8082")
    String baseUrl;

    @ConfigProperty(name = "memory-service.api-key", defaultValue = "agent-api-key-1")
    String apiKey;

    @ConfigProperty(name = "benchmark.cognition.namespace", defaultValue = "cognition.v1")
    String cognitionNamespace;

    @ConfigProperty(name = "benchmark.cognition.wait-timeout-seconds", defaultValue = "600")
    int waitTimeoutSeconds;

    @ConfigProperty(name = "benchmark.cognition.poll-interval-seconds", defaultValue = "10")
    int pollIntervalSeconds;

   @ConfigProperty(name = "benchmark.cognition.stable-seconds", defaultValue = "90")
   int stableSeconds;

   private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

   private final Map<String, String> conversationIds = new HashMap<>();

    public String createConversation(String userId, String title) throws Exception {
        String cached = conversationIds.get(userId);
        if (cached != null) return cached;

        Map<String, Object> body = Map.of(
                "title", title,
                "metadata", Map.of("benchmark", true, "user_id", userId)
        );

        HttpResponse<String> resp = post(userId, "/v1/conversations", body);
        if (resp.statusCode() >= 300) {
            throw new RuntimeException("Failed to create conversation: " + resp.statusCode() + " " + resp.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
        String convId = (String) data.get("id");
        conversationIds.put(userId, convId);
        log.infof("Created conversation %s for user=%s", convId, userId);
        return convId;
    }

    public void appendEntry(String userId, String conversationId, String role, String text) throws Exception {
        Map<String, Object> content = Map.of("role", role, "text", text);
        Map<String, Object> body = Map.of(
                "channel", "history",
                "contentType", "history",
                "content", List.of(content)
        );

        HttpResponse<String> resp = post(userId, "/v1/conversations/" + conversationId + "/entries", body);
        if (resp.statusCode() >= 300) {
            throw new RuntimeException("Failed to append entry: " + resp.statusCode() + " " + resp.body());
        }
    }

    public record MemoryResult(String id, String memory, double score) {}

    public List<MemoryResult> searchMemories(String userId, String query, int topK) throws Exception {
        Map<String, Object> body = Map.of(
                "namespace_prefix", List.of("user", userId, cognitionNamespace),
                "query", query,
                "limit", Math.min(topK, 100)
        );

        HttpResponse<String> resp = post(userId, "/v1/memories/search", body);
        if (resp.statusCode() == 501) {
            log.warn("Semantic search not available (501). Check MEMORY_SERVICE_VECTOR_KIND.");
            return List.of();
        }
        if (resp.statusCode() >= 300) {
            log.warnf("Memory search failed: %d %s", resp.statusCode(), resp.body());
            return List.of();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.getOrDefault("items", List.of());

        List<MemoryResult> results = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String id = (String) item.getOrDefault("id", "");
            double score = item.get("score") instanceof Number n ? n.doubleValue() : 0.0;

            @SuppressWarnings("unchecked")
            Map<String, Object> value = item.get("value") instanceof Map ? (Map<String, Object>) item.get("value") : Map.of();
            String memoryText = (String) value.getOrDefault("content",
                    value.getOrDefault("statement", ""));

            if (memoryText != null && !memoryText.isBlank()) {
                results.add(new MemoryResult(id, memoryText, score));
            }
        }

        results.sort(Comparator.comparingDouble(MemoryResult::score).reversed());
        return results;
    }

    public int waitForCognition(String userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (waitTimeoutSeconds * 1000L);
        int lastCount = 0;
        long lastChangeTime = 0;

        log.infof("Waiting for cognition (timeout=%ds, stable=%ds)...", waitTimeoutSeconds, stableSeconds);

        while (System.currentTimeMillis() < deadline) {
            try {
                List<MemoryResult> results = searchMemories(userId, "", 100);
                int count = results.size();
                long elapsed = (waitTimeoutSeconds * 1000L - (deadline - System.currentTimeMillis())) / 1000;

                if (count != lastCount) {
                    log.infof("Cognition progress for user=%s: %d memories found (%ds elapsed)", userId, count, elapsed);
                    lastCount = count;
                    lastChangeTime = System.currentTimeMillis();
                }

                if (count > 0 && lastChangeTime > 0) {
                    long stableFor = (System.currentTimeMillis() - lastChangeTime) / 1000;
                    if (stableFor >= stableSeconds) {
                        log.infof("Cognition stable for %ds with %d memories — proceeding", stableFor, count);
                        return count;
                    }
                }
            } catch (Exception e) {
                log.debugf("Cognition poll error for user=%s: %s", userId, e.getMessage());
            }
            Thread.sleep(pollIntervalSeconds * 1000L);
        }

        log.warnf("Cognition wait timed out for user=%s after %ds (%d memories found)", userId, waitTimeoutSeconds, lastCount);
        return lastCount;
    }

    private HttpResponse<String> post(String userId, String path, Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .header("Authorization", "Bearer " + userId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(60))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

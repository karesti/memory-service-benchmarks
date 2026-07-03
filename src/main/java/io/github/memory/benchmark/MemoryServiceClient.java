package io.github.memory.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

   @Inject
   MemoryServiceConfig serviceConfig;

   @Inject
   BenchmarkConfig benchmarkConfig;

   private HttpClient http;
   private final Map<String, String> conversationIds = new HashMap<>();

   // -- Response types --------------------------------------------------------

   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ConversationResponse(String id, String title) {}

   @JsonIgnoreProperties(ignoreUnknown = true)
   public record SearchResponse(List<MemoryItem> items) {
      public SearchResponse { if (items == null) items = List.of(); }
   }

   @JsonIgnoreProperties(ignoreUnknown = true)
   public record MemoryItem(String id, Double score, MemoryValue value) {}

   @JsonIgnoreProperties(ignoreUnknown = true)
   public record MemoryValue(String content, String statement) {
      public String text() {
         if (content != null && !content.isBlank()) return content;
         if (statement != null && !statement.isBlank()) return statement;
         return "";
      }
   }

   public record MemoryResult(String id, String memory, double score) {}

   // -- Public API ------------------------------------------------------------

   private HttpClient http() {
      if (http == null) {
         http = HttpClient.newBuilder()
                 .connectTimeout(Duration.ofSeconds(benchmarkConfig.httpclient().connectionTimeout()))
                 .build();
      }
      return http;
   }

   public String createConversation(String userId, String title) throws Exception {
      String cached = conversationIds.get(userId);
      if (cached != null) return cached;

      Map<String, Object> body = Map.of("title", title, "metadata", Map.of("benchmark", true, "user_id", userId));

      HttpResponse<String> resp = post(userId, "/v1/conversations", body);
      if (resp.statusCode() >= 300) {
         throw new RuntimeException("Failed to create conversation: " + resp.statusCode() + " " + resp.body());
      }

      ConversationResponse conversation = mapper.readValue(resp.body(), ConversationResponse.class);
      conversationIds.put(userId, conversation.id());
      log.infof("Created conversation %s for user=%s", conversation.id(), userId);
      return conversation.id();
   }

   public void appendEntry(String userId, String conversationId, String role, String text) throws Exception {
      Map<String, Object> content = Map.of("role", role, "text", text);
      Map<String, Object> body = Map.of("channel", "history", "contentType", "history", "content", List.of(content));

      HttpResponse<String> resp = post(userId, "/v1/conversations/" + conversationId + "/entries", body);
      if (resp.statusCode() >= 300) {
         throw new RuntimeException("Failed to append entry: " + resp.statusCode() + " " + resp.body());
      }
   }

   public List<MemoryResult> searchMemories(String userId, String query, int topK) throws Exception {
      Map<String, Object> body = Map.of(
              "namespace_prefix", List.of("user", userId, benchmarkConfig.cognition().namespace()),
              "query", query,
              "limit", Math.min(topK, 100));

      HttpResponse<String> resp = post(userId, "/v1/memories/search", body);
      if (resp.statusCode() == 501) {
         log.warn("Semantic search not available (501). Check MEMORY_SERVICE_VECTOR_KIND.");
         return List.of();
      }
      if (resp.statusCode() >= 300) {
         log.warnf("Memory search failed: %d %s", resp.statusCode(), resp.body());
         return List.of();
      }

      SearchResponse search = mapper.readValue(resp.body(), SearchResponse.class);

      List<MemoryResult> results = new ArrayList<>();
      for (MemoryItem item : search.items()) {
         if (item.value() == null) continue;
         String memoryText = item.value().text();
         if (!memoryText.isEmpty()) {
            double score = item.score() != null ? item.score() : 0.0;
            results.add(new MemoryResult(item.id(), memoryText, score));
         }
      }

      results.sort(Comparator.comparingDouble(MemoryResult::score).reversed());
      return results;
   }

   public int waitForCognition(String userId) throws InterruptedException {
      var cognition = benchmarkConfig.cognition();
      long deadline = System.currentTimeMillis() + (cognition.waitTimeoutSeconds() * 1000L);
      int lastCount = 0;
      long lastChangeTime = 0;

      log.infof("Waiting for cognition (timeout=%ds, stable=%ds)...",
              cognition.waitTimeoutSeconds(), cognition.stableSeconds());

      while (System.currentTimeMillis() < deadline) {
         try {
            List<MemoryResult> results = searchMemories(userId, "", 100);
            int count = results.size();
            long elapsed = (cognition.waitTimeoutSeconds() * 1000L - (deadline - System.currentTimeMillis())) / 1000;

            if (count != lastCount) {
               log.infof("Cognition progress for user=%s: %d memories found (%ds elapsed)", userId, count, elapsed);
               lastCount = count;
               lastChangeTime = System.currentTimeMillis();
            }

            if (count > 0 && lastChangeTime > 0) {
               long stableFor = (System.currentTimeMillis() - lastChangeTime) / 1000;
               if (stableFor >= cognition.stableSeconds()) {
                  log.infof("Cognition stable for %ds with %d memories — proceeding", stableFor, count);
                  return count;
               }
            }
         } catch (Exception e) {
            log.debugf("Cognition poll error for user=%s: %s", userId, e.getMessage());
         }
         Thread.sleep(cognition.pollIntervalSeconds() * 1000L);
      }

      log.warnf("Cognition wait timed out for user=%s after %ds (%d memories found)",
              userId, cognition.waitTimeoutSeconds(), lastCount);
      return lastCount;
   }

   // -- HTTP ------------------------------------------------------------------

   private HttpResponse<String> post(String userId, String path, Map<String, Object> body) throws Exception {
      String json = mapper.writeValueAsString(body);
      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(serviceConfig.url() + path))
              .header("Content-Type", "application/json")
              .header("X-API-Key", serviceConfig.apiKey())
              .header("Authorization", "Bearer " + userId)
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .timeout(Duration.ofSeconds(60))
              .build();
      return http().send(request, HttpResponse.BodyHandlers.ofString());
   }
}

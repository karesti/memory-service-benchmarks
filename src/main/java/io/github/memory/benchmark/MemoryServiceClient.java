package io.github.memory.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
   private final Map<String, CachedToken> tokenCache = new HashMap<>();
   private final Set<String> provisionedUsers = new HashSet<>();
   private Keycloak keycloakAdmin;

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
   @JsonIgnoreProperties(ignoreUnknown = true)
   private record TokenResponse(String access_token, Long expires_in) {}
   private record CachedToken(String token, long expiresAtMillis) {}

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

      String effectiveUserId = effectiveUserId(userId);
      Map<String, Object> body = Map.of(
              "title", title,
              "metadata", Map.of(
                      "benchmark", true,
                      "user_id", effectiveUserId,
                      "benchmark_user_id", userId));

      HttpResponse<String> resp = post(userId, "/v1/conversations", body);
      if (resp.statusCode() >= 300) {
         throw new RuntimeException("Failed to create conversation: " + resp.statusCode() + " " + resp.body());
      }

      ConversationResponse conversation = mapper.readValue(resp.body(), ConversationResponse.class);
      conversationIds.put(userId, conversation.id());
      log.infof("Created conversation %s for benchmark user=%s (oidc user=%s)",
              conversation.id(), userId, effectiveUserId);
      return conversation.id();
   }

   public void appendEntry(String userId, String conversationId, String role, String text) throws Exception {
      Map<String, Object> content = Map.of("role", role, "text", text);
      Map<String, Object> body = Map.of("channel", "history", "contentType", "history", "content", List.of(content), "indexedContent", text);

      HttpResponse<String> resp = post(userId, "/v1/conversations/" + conversationId + "/entries", body);
      if (resp.statusCode() >= 300) {
         throw new RuntimeException("Failed to append entry: " + resp.statusCode() + " " + resp.body());
      }
   }

   public List<MemoryResult> searchMemories(String userId, String query, int topK) throws Exception {
      String effectiveUserId = effectiveUserId(userId);
      Map<String, Object> body = Map.of(
              "namespace_prefix", List.of("user", effectiveUserId, benchmarkConfig.cognition().namespace()),
              "query", query,
              "limit", topK);

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

   public int countMemories(String userId) {
      try {
         String effectiveUserId = effectiveUserId(userId);
         Map<String, Object> body = Map.of(
                 "namespace_prefix", List.of("user", effectiveUserId, benchmarkConfig.cognition().namespace()),
                 "limit", 1000);

         String json = mapper.writeValueAsString(body);
         HttpRequest.Builder request = HttpRequest.newBuilder()
                 .uri(URI.create(serviceConfig.url() + "/admin/memories/search"))
                 .header("Content-Type", "application/json")
                 .header("X-API-Key", serviceConfig.adminApiKey())
                 .POST(HttpRequest.BodyPublishers.ofString(json))
                 .timeout(Duration.ofSeconds(30));

         HttpResponse<String> resp = http().send(request.build(), HttpResponse.BodyHandlers.ofString());
         if (resp.statusCode() >= 300) return -1;

         SearchResponse search = mapper.readValue(resp.body(), SearchResponse.class);
         return search.items().size();
      } catch (Exception e) {
         log.debugf("countMemories failed: %s", e.getMessage());
         return -1;
      }
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
            int count = countMemories(userId);
            if (count < 0) {
               count = searchMemories(userId, "", 100).size();
            }
            long elapsed = (cognition.waitTimeoutSeconds() * 1000L - (deadline - System.currentTimeMillis())) / 1000;

            if (count != lastCount) {
               log.infof("Cognition progress for user=%s: %d memories extracted (%ds elapsed)", userId, count, elapsed);
               lastCount = count;
               lastChangeTime = System.currentTimeMillis();
            }

            if (count > 0 && lastChangeTime > 0) {
               long stableFor = (System.currentTimeMillis() - lastChangeTime) / 1000;
               if (stableFor >= cognition.stableSeconds()) {
                  log.infof("Cognition ready: %d memories extracted for user=%s", count, userId);
                  return count;
               }
            }
         } catch (Exception e) {
            log.debugf("Cognition poll error for user=%s: %s", userId, e.getMessage());
         }
         Thread.sleep(cognition.pollIntervalSeconds() * 1000L);
      }

      log.warnf("Cognition wait timed out for user=%s after %ds (%d memories found via search)",
              userId, cognition.waitTimeoutSeconds(), lastCount);
      return lastCount;
   }

   // -- HTTP ------------------------------------------------------------------

   public void generateUserProfile(String userId) throws Exception {
      if (!serviceConfig.cognition().generateProfile()) {
         log.debugf("Profile generation disabled for user=%s", userId);
         return;
      }

      String consolidateUrl = serviceConfig.cognition().url() + "/api/consolidate/" + userId;

      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(consolidateUrl))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.noBody())
              .timeout(Duration.ofSeconds(120))
              .build();

      HttpResponse<String> resp = http().send(request, HttpResponse.BodyHandlers.ofString());

      if (resp.statusCode() >= 300) {
         log.warnf("Profile generation failed for user=%s: %d %s",
                 userId, resp.statusCode(), resp.body());
         return;
      }

      log.infof("Generated user profile for user=%s", userId);
   }


   private HttpResponse<String> post(String userId, String path, Map<String, Object> body) throws Exception {
      String json = mapper.writeValueAsString(body);
      HttpRequest.Builder request = HttpRequest.newBuilder()
              .uri(URI.create(serviceConfig.url() + path))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + bearerToken(userId))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .timeout(Duration.ofSeconds(60));

      if (serviceConfig.apiKey() != null && !serviceConfig.apiKey().isBlank()) {
         request.header("X-API-Key", serviceConfig.apiKey());
      }

      return http().send(request.build(), HttpResponse.BodyHandlers.ofString());
   }

   private String effectiveUserId(String benchmarkUserId) {
      return benchmarkUserId;
   }

   private String bearerToken(String benchmarkUserId) throws Exception {
      if (!serviceConfig.oidc().enabled()) {
         return benchmarkUserId;
      }
      ensureKeycloakUser(benchmarkUserId);

      CachedToken cached = tokenCache.get(benchmarkUserId);
      long now = System.currentTimeMillis();
      if (cached != null && cached.expiresAtMillis() > now) {
         return cached.token();
      }

      TokenResponse token = loginUser(benchmarkUserId);
      if (token.access_token() == null || token.access_token().isBlank()) {
         throw new RuntimeException("OIDC login did not return an access_token for user " + benchmarkUserId);
      }

      long expiresInSeconds = token.expires_in() != null ? token.expires_in() : 300;
      long maxCacheSeconds = 240;
      long effectiveExpiry = Math.min(expiresInSeconds, maxCacheSeconds);
      long refreshSkewMillis = Math.max(0, serviceConfig.oidc().refreshSkewSeconds()) * 1000L;
      long expiresAtMillis = now + (effectiveExpiry * 1000L) - refreshSkewMillis;
      tokenCache.put(benchmarkUserId, new CachedToken(token.access_token(), expiresAtMillis));
      log.infof("Logged in to Keycloak as benchmark user=%s (token expires_in=%ds, cache=%ds)",
              benchmarkUserId, expiresInSeconds, effectiveExpiry);
      return token.access_token();
   }

   private TokenResponse loginUser(String userId) throws Exception {
      var oidc = serviceConfig.oidc();
      String form = form(
              "grant_type", "password",
              "client_id", oidc.clientId(),
              "client_secret", oidc.clientSecret(),
              "username", userId,
              "password", oidc.userPassword());

      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(oidc.tokenUrl()))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .timeout(Duration.ofSeconds(30))
              .build();

      HttpResponse<String> resp = http().send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 300) {
         throw new RuntimeException("OIDC login failed for user " + userId
                 + ": " + resp.statusCode() + " " + resp.body());
      }
      return mapper.readValue(resp.body(), TokenResponse.class);
   }

   private void ensureKeycloakUser(String userId) throws Exception {
      if (!serviceConfig.oidc().provisionUsers() || provisionedUsers.contains(userId)) {
         return;
      }

      RealmResource realm = benchmarkRealm();
      UserRepresentation user = keycloakUser(userId);

      try (Response resp = realm.users().create(user)) {
         if (resp.getStatus() == 201) {
            String keycloakUserId = CreatedResponseUtil.getCreatedId(resp);
            if (keycloakUserId == null || keycloakUserId.isBlank()) {
               keycloakUserId = findKeycloakUserId(realm, userId);
            }
            configureKeycloakUser(realm, userId, keycloakUserId);
            assignUserRole(realm, userId, keycloakUserId);
            provisionedUsers.add(userId);
            log.infof("Provisioned Keycloak user %s", userId);
            return;
         }

         if (resp.getStatus() == 409) {
            provisionedUsers.add(userId);
            log.debugf("Keycloak user %s already exists", userId);
            return;
         }

         throw new RuntimeException("Failed to provision Keycloak user " + userId
                 + ": " + resp.getStatus() + " " + responseBody(resp));
      }
   }

   private RealmResource benchmarkRealm() {
      return keycloakAdmin().realm(serviceConfig.oidc().realm());
   }

   private Keycloak keycloakAdmin() {
      if (keycloakAdmin == null) {
         var admin = serviceConfig.oidc().admin();
         keycloakAdmin = KeycloakBuilder.builder()
                 .serverUrl(admin.serverUrl())
                 .realm(admin.realm())
                 .clientId(admin.clientId())
                 .username(admin.username())
                 .password(admin.password())
                 .build();
      }
      return keycloakAdmin;
   }

   private UserRepresentation keycloakUser(String username) {
      UserRepresentation user = new UserRepresentation();
      user.setUsername(username);
      user.setEnabled(true);
      user.setEmail(emailForUser(username));
      user.setEmailVerified(true);
      user.setFirstName("Benchmark");
      user.setLastName(username);
      user.setRequiredActions(List.of());
      user.setCredentials(List.of(passwordCredential()));
      return user;
   }

   private CredentialRepresentation passwordCredential() {
      CredentialRepresentation credential = new CredentialRepresentation();
      credential.setType(CredentialRepresentation.PASSWORD);
      credential.setValue(serviceConfig.oidc().userPassword());
      credential.setTemporary(false);
      return credential;
   }

   private String findKeycloakUserId(RealmResource realm, String username) {
      List<UserRepresentation> users = realm.users().searchByUsername(username, true);
      if (users.isEmpty()) {
         throw new RuntimeException("Keycloak user " + username + " was not found after provisioning");
      }
      return users.getFirst().getId();
   }

   private void configureKeycloakUser(RealmResource realm, String username, String userId) {
      if (userId == null || userId.isBlank()) {
         throw new RuntimeException("Cannot configure Keycloak user " + username + ": missing user id");
      }

      realm.users().get(userId).update(keycloakUser(username));
      realm.users().get(userId).resetPassword(passwordCredential());
   }

   private void assignUserRole(RealmResource realm, String username, String userId) {
      if (userId == null || userId.isBlank()) {
         log.warnf("Could not assign Keycloak 'user' role to %s: missing user id", username);
         return;
      }

      try {
         RoleRepresentation role = realm.roles().get("user").toRepresentation();
         realm.users().get(userId).roles().realmLevel().add(List.of(role));
      } catch (RuntimeException e) {
         log.warnf("Could not assign Keycloak 'user' role to %s: %s", username, e.getMessage());
      }
   }

   private static String responseBody(Response response) {
      try {
         return response.hasEntity() ? response.readEntity(String.class) : "";
      } catch (RuntimeException e) {
         return "";
      }
   }

   private static String emailForUser(String username) {
      String localPart = username.replaceAll("[^A-Za-z0-9._%+-]", "_");
      if (localPart.isBlank()) {
         localPart = "benchmark";
      }
      return localPart + "@benchmark.local";
   }

   private static String form(String... pairs) {
      if (pairs.length % 2 != 0) {
         throw new IllegalArgumentException("form pairs must be key/value pairs");
      }
      List<String> fields = new ArrayList<>();
      for (int i = 0; i < pairs.length; i += 2) {
         fields.add(urlEncode(pairs[i]) + "=" + urlEncode(pairs[i + 1]));
      }
      return String.join("&", fields);
   }

   private static String urlEncode(String value) {
      return URLEncoder.encode(value, StandardCharsets.UTF_8);
   }
}

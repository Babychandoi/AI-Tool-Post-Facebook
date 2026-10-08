package com.marketing.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketing.shared.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** Only documented OpenAI public OAuth and Responses endpoints. Never browser cookies. */
@Component
public class ChatGptTransport {
  static final String ISSUER = "https://auth.openai.com";
  static final String RESOURCE = "https://api.openai.com/v1";
  private final Db db;
  private final HttpClient client;
  private final JwtDecoder decoder;

  @org.springframework.beans.factory.annotation.Autowired
  public ChatGptTransport(Db db) {
    this(
        db,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        NimbusJwtDecoder.withJwkSetUri(ISSUER + "/.well-known/jwks.json").build());
  }

  ChatGptTransport(Db db, HttpClient client, JwtDecoder decoder) {
    this.db = db;
    this.client = client;
    this.decoder = decoder;
  }

  public record Identity(String subject, String email) {}

  public Identity identity(String encoded, String clientId, String nonce) {
    try {
      Jwt jwt = decoder.decode(encoded);
      if (!ISSUER.equals(jwt.getClaimAsString("iss"))
          || !jwt.getAudience().contains(clientId)
          || (jwt.getAudience().size() > 1 && !clientId.equals(jwt.getClaimAsString("azp")))
          || jwt.getExpiresAt() == null
          || !jwt.getExpiresAt().isAfter(Instant.now())
          || jwt.getIssuedAt() == null
          || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(5))
          || jwt.getSubject() == null
          || jwt.getSubject().isBlank()
          || !equal(nonce, jwt.getClaimAsString("nonce"))) throw new IllegalArgumentException();
      String email = jwt.getClaimAsString("email");
      return new Identity(jwt.getSubject(), email == null ? "Tài khoản ChatGPT" : email);
    } catch (Exception e) {
      throw Problem.conflict("Không xác minh được danh tính ChatGPT. Hãy kết nối lại.");
    }
  }

  public Map<String, Object> token(Map<String, String> parameters) {
    return db.map(sendForm(ISSUER + "/api/accounts/oauth/token", parameters));
  }

  public boolean revoke(String clientId, String refresh) {
    try {
      var discovery = db.map(get(ISSUER + "/.well-known/openid-configuration", null));
      String endpoint = Objects.toString(discovery.get("revocation_endpoint"), "");
      URI uri = URI.create(endpoint);
      if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost()))
        return false;
      sendForm(
          endpoint,
          Map.of("client_id", clientId, "token", refresh, "token_type_hint", "refresh_token"));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public List<Map<String, Object>> models(String access) {
    try {
      JsonNode catalog = db.json.readTree(get(RESOURCE + "/models", access)).path("models");
      if (!catalog.isArray()) throw new IllegalArgumentException();
      var result = new ArrayList<Map<String, Object>>();
      for (var m : catalog)
        if ("list".equals(m.path("visibility").asText()) && !m.path("slug").asText().isBlank())
          result.add(
              Map.of(
                  "slug",
                  m.path("slug").asText(),
                  "display_name",
                  m.path("display_name").asText(m.path("slug").asText())));
      if (result.isEmpty()) throw Problem.conflict("Tài khoản chưa có model khả dụng.");
      return result;
    } catch (ProviderFailure e) {
      throw e;
    } catch (Exception e) {
      throw Problem.conflict("Không đọc được danh sách model ChatGPT.");
    }
  }

  public JsonNode respond(String access, Map<String, Object> payload) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(RESOURCE + "/responses"))
              .timeout(Duration.ofSeconds(90))
              .header("Authorization", "Bearer " + access)
              .header("Content-Type", "application/json")
              .header("Accept", "text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(db.json(payload)))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() != 200)
          throw failure(response.statusCode(), readLimited(body, 16384));
        // Some plan-usage responses omit Content-Type while still returning SSE.
        // The bounded parser below requires a completed response event in either case.
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.isBlank()
            && !contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
          throw Problem.conflict("ChatGPT không trả về stream hợp lệ.");
        // The JDK's ofInputStream handler completes at headers. Bound stalled body reads too.
        try (var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
          var timeout =
              timer.schedule(
                  () -> {
                    try {
                      body.close();
                    } catch (IOException ignored) {
                    }
                  },
                  90,
                  java.util.concurrent.TimeUnit.SECONDS);
          try {
            return completed(body);
          } finally {
            timeout.cancel(false);
            timer.shutdownNow();
          }
        }
      }
    } catch (ProviderFailure | Problem e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Problem.conflict("Tác vụ ChatGPT đã dừng.");
    } catch (Exception e) {
      throw Problem.conflict("Stream ChatGPT bị gián đoạn. Tác vụ chưa hoàn tất.");
    }
  }

  // A completed response is authoritative; partial text, [DONE], failure and EOF are never success.
  JsonNode completed(InputStream stream) throws IOException {
    var data = new StringBuilder();
    var completedItems = new TreeMap<Integer, JsonNode>();
    String responseId = null;
    int total = 0, value;
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    var line = new ByteArrayOutputStream();
    while ((value = stream.read()) != -1) {
      if (++total > 2_000_000 || System.nanoTime() > deadline || line.size() > 262144)
        throw Problem.conflict("Stream ChatGPT vượt giới hạn xử lý.");
      if (value != '\n') {
        line.write(value);
        continue;
      }
      String text = line.toString(StandardCharsets.UTF_8).replaceFirst("\\r$", "");
      line.reset();
      if (text.startsWith("data:")) data.append(text.substring(5).stripLeading()).append('\n');
      if (!text.isEmpty() || data.isEmpty()) continue;
      String eventData = data.toString().trim();
      data.setLength(0);
      if ("[DONE]".equals(eventData)) break;
      var event = db.json.readTree(eventData);
      String type = event.path("type").asText();
      if ("response.created".equals(type)) responseId = event.path("response").path("id").asText();
      if ("response.output_item.done".equals(type)
          && "message".equals(event.path("item").path("type").asText())) {
        int index = event.path("output_index").asInt(-1);
        JsonNode item = event.path("item");
        if (index < 0 || index >= 128 || !"completed".equals(item.path("status").asText()))
          throw Problem.conflict("Mục đầu ra ChatGPT chưa hoàn tất hoặc không hợp lệ.");
        completedItems.put(index, item);
      }
      if ("response.completed".equals(type)
          && "completed".equals(event.path("response").path("status").asText())) {
        JsonNode response = event.path("response");
        if (responseId != null && !responseId.equals(response.path("id").asText()))
          throw Problem.conflict("Không khớp phiên phản hồi ChatGPT.");
        // Plan-usage SSE can omit output from the final event. Use only complete
        // items from this stream, and only after the authoritative completed event.
        if (response.path("output").isArray()
            && response.path("output").isEmpty()
            && !completedItems.isEmpty()) {
          var output = db.json.createArrayNode();
          completedItems.values().forEach(output::add);
          ((com.fasterxml.jackson.databind.node.ObjectNode) response).set("output", output);
        }
        return response;
      }
      if (Set.of("response.failed", "response.incomplete", "error").contains(type))
        throw failure(400, eventData);
    }
    throw Problem.conflict(
        "Chưa nhận được response.completed từ ChatGPT; không dùng bản nháp chưa hoàn tất.");
  }

  private String get(String endpoint, String token) {
    var b = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(20));
    if (token != null) b.header("Authorization", "Bearer " + token);
    return send(b.GET().build());
  }

  private String sendForm(String endpoint, Map<String, String> values) {
    return send(
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(values)))
            .build());
  }

  private String send(HttpRequest request) {
    try {
      var r = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var body = r.body()) {
        String text = readLimited(body, 1_000_000);
        if (r.statusCode() != 200) throw failure(r.statusCode(), text);
        return text;
      }
    } catch (ProviderFailure e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Problem.conflict("Kết nối ChatGPT đã dừng.");
    } catch (Exception e) {
      throw Problem.conflict("Không kết nối được OpenAI. Thử lại sau.");
    }
  }

  private static String readLimited(InputStream body, int limit) throws IOException {
    byte[] bytes = body.readNBytes(limit + 1);
    if (bytes.length > limit) throw new IOException("Response exceeds limit");
    return new String(bytes, StandardCharsets.UTF_8);
  }

  static String form(Map<String, String> values) {
    return values.entrySet().stream()
        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  public static class ProviderFailure extends RuntimeException {
    public final int status;
    public final String code;

    public ProviderFailure(int status, String code) {
      super("OpenAI connection error: HTTP " + status);
      this.status = status;
      this.code = code;
    }
  }

  private ProviderFailure failure(int status, String body) {
    String code = "";
    try {
      var root = db.json.readTree(body);
      if (root.has("response")) root = root.path("response");
      code =
          root.path("error").isTextual()
              ? root.path("error").asText()
              : root.path("error").path("code").asText();
    } catch (Exception ignored) {
    }
    return new ProviderFailure(status, code);
  }
}

package com.marketing.publishing;

import com.marketing.media.AssetStore;
import com.marketing.shared.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MetaPublisher implements SocialPublisher {
  private final Db db;
  private final Environment env;
  private final TokenVault vault;
  private final AssetStore assets;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public MetaPublisher(Db db, Environment env, TokenVault vault, AssetStore assets) {
    this.db = db;
    this.env = env;
    this.vault = vault;
    this.assets = assets;
  }

  private String root() {
    String version = env.getProperty("app.meta-version", "");
    if (!version.matches("v\\d+\\.\\d+"))
      throw Problem.bad("Cần cấu hình META_GRAPH_VERSION đã được kiểm tra với app.");
    return "https://graph.facebook.com/" + version + "/";
  }

  private String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String proof(String token) {
    try {
      String secret = env.getProperty("app.meta-app-secret", "");
      if (secret.isBlank()) throw new IllegalStateException();
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(m.doFinal(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw Problem.bad("Cần cấu hình META_APP_SECRET.");
    }
  }

  private Map<String, Object> get(String path, String token) {
    try {
      var r =
          client.send(
              HttpRequest.newBuilder(URI.create(root() + path + "&appsecret_proof=" + proof(token)))
                  .timeout(Duration.ofSeconds(20))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200)
        throw Problem.conflict(
            "Meta không xác nhận quyền hoặc dữ liệu. Kết nối lại Page và kiểm tra quyền.");
      return db.map(r.body());
    } catch (Problem p) {
      throw p;
    } catch (Exception e) {
      throw Problem.conflict("Không đọc được dữ liệu Meta. Kiểm tra kết nối.");
    }
  }

  @Override
  public Map<String, Object> verify(String pageId, String token) {
    if (!pageId.matches("\\d+")) throw Problem.bad("Page ID phải là số.");
    String appId = env.getProperty("app.meta-app-id", "");
    String secret = env.getProperty("app.meta-app-secret", "");
    if (appId.isBlank() || secret.isBlank())
      throw Problem.bad("Cần META_APP_ID và META_APP_SECRET để xác minh token.");
    var debug = get("debug_token?input_token=" + enc(token), appId + "|" + secret);
    Object value = debug.get("data");
    if (!(value instanceof Map<?, ?> data)
        || !Boolean.TRUE.equals(data.get("is_valid"))
        || !appId.equals(String.valueOf(data.get("app_id"))))
      throw Problem.bad("Token không hợp lệ hoặc không thuộc app.");
    Object scopes = data.get("scopes");
    if (!(scopes instanceof List<?> permissions)
        || !permissions.containsAll(List.of("pages_manage_posts", "pages_read_engagement")))
      throw Problem.bad("Token thiếu pages_manage_posts hoặc pages_read_engagement.");
    var page = get("me?fields=id,name", token);
    if (!pageId.equals(page.get("id"))) throw Problem.bad("Token không thuộc Page ID này.");
    return Map.of("id", pageId, "name", page.get("name"), "permissions", permissions);
  }

  @Override
  public Result publish(Map<String, Object> account, Map<String, Object> snapshot, UUID operation) {
    String token = vault.decrypt(account.get("token_ciphertext").toString());
    String page = account.get("remote_id").toString();
    byte[] png = assets.get(snapshot.get("asset_key").toString());
    String boundary = "marketing-" + operation;
    String message = snapshot.get("message").toString();
    byte[] start =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"message\"\r\n\r\n"
                + message
                + "\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"appsecret_proof\"\r\n\r\n"
                + proof(token)
                + "\r\n--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"source\"; filename=\"post.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    try {
      var request =
          HttpRequest.newBuilder(URI.create(root() + page + "/photos"))
              .timeout(Duration.ofSeconds(45))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(start, png, end)))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      String requestId = response.headers().firstValue("x-fb-trace-id").orElse(null);
      if (response.statusCode() == 429)
        return new Result("RETRY_WAIT", null, null, "RATE_LIMIT", retryAfter(response), requestId);
      if (response.statusCode() >= 500)
        return new Result("UNKNOWN", null, null, "AMBIGUOUS_SERVER_ERROR", 0, requestId);
      var data = db.map(response.body());
      if (response.statusCode() >= 400) {
        Object error = data.get("error");
        int code =
            error instanceof Map<?, ?> m && m.get("code") instanceof Number n ? n.intValue() : 0;
        return new Result(
            Set.of(4, 32, 613).contains(code) ? "RETRY_WAIT" : "BLOCKED",
            null,
            null,
            "META_" + code,
            60,
            requestId);
      }
      Object id = data.get("post_id");
      if (id == null)
        return new Result("UNKNOWN", null, null, "NO_CONFIRMED_POST_ID", 0, requestId);
      return new Result(
          "PUBLISHED", id.toString(), "https://www.facebook.com/" + id, null, 0, requestId);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return new Result("UNKNOWN", null, null, "NETWORK_AMBIGUOUS", 0, null);
    }
  }

  private long retryAfter(HttpResponse<?> r) {
    try {
      return Math.max(
          1, Math.min(86400, Long.parseLong(r.headers().firstValue("Retry-After").orElse("60"))));
    } catch (Exception e) {
      return 60;
    }
  }

  @Override
  public Map<String, Object> fetchPost(Map<String, Object> account, String postId) {
    if (!postId.matches("\\d+_\\d+")) throw Problem.bad("Post ID phải có dạng PageID_PostID.");
    return get(
        postId + "?fields=id,message,from,permalink_url,created_time",
        vault.decrypt(account.get("token_ciphertext").toString()));
  }

  @Override
  public Map<String, Number> metrics(Map<String, Object> account, String postId) {
    var p =
        get(
            postId
                + "?fields=id,shares,comments.limit(0).summary(true),reactions.limit(0).summary(true)",
            vault.decrypt(account.get("token_ciphertext").toString()));
    var result = new LinkedHashMap<String, Number>();
    result.put("reach", null);
    for (String metric : List.of("comments", "reactions")) {
      Object o = p.get(metric);
      Number value = null;
      if (o instanceof Map<?, ?> m
          && m.get("summary") instanceof Map<?, ?> s
          && s.get("total_count") instanceof Number n) value = n;
      result.put(metric, value);
    }
    Number shares = null;
    if (p.get("shares") instanceof Map<?, ?> s && s.get("count") instanceof Number n) shares = n;
    result.put("shares", shares);
    return result;
  }
}

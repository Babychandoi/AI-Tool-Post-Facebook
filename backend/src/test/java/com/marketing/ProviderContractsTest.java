package com.marketing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketing.media.*;
import com.marketing.publishing.*;
import com.marketing.shared.Db;
import com.marketing.webhook.MetaWebhook;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class ProviderContractsTest {
  private MetaPublisher publisher;
  private HttpClient http;
  private Map<String, Object> account, snapshot;

  @BeforeEach
  void setup() {
    var env =
        new MockEnvironment()
            .withProperty("app.meta-version", "v42.0")
            .withProperty("app.meta-app-id", "999")
            .withProperty("app.meta-app-secret", "fixture-secret");
    var vault = mock(TokenVault.class);
    when(vault.decrypt(anyString())).thenReturn("fixture-page-token");
    var storage = mock(AssetStore.class);
    when(storage.get(anyString())).thenReturn(new byte[] {1, 2, 3});
    publisher =
        new MetaPublisher(
            new Db(mock(JdbcTemplate.class), new ObjectMapper()), env, vault, storage);
    http = mock(HttpClient.class);
    ReflectionTestUtils.setField(publisher, "client", http);
    account = Map.of("remote_id", "111", "token_ciphertext", "cipher");
    snapshot = Map.of("asset_key", "private/image.png", "message", "Nội dung tiếng Việt có nguồn");
  }

  @SuppressWarnings("unchecked")
  private void response(int code, String body, Map<String, List<String>> headers) throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(code);
    when(response.body()).thenReturn(body);
    when(response.headers()).thenReturn(HttpHeaders.of(headers, (k, v) -> true));
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
  }

  @Test
  void confirmedRemoteIdIsRequired() throws Exception {
    response(200, "{\"id\":\"photo-only\"}", Map.of());
    assertEquals("UNKNOWN", publisher.publish(account, snapshot, UUID.randomUUID()).status());
    response(200, "{\"id\":\"photo-id\",\"post_id\":\"111_222\"}", Map.of());
    var result = publisher.publish(account, snapshot, UUID.randomUUID());
    assertEquals("PUBLISHED", result.status());
    assertEquals("111_222", result.remoteId());
  }

  @Test
  void timeoutAndServerFailureNeverBecomeRetry() throws Exception {
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("network lost"));
    assertEquals("UNKNOWN", publisher.publish(account, snapshot, UUID.randomUUID()).status());
    reset(http);
    response(503, "upstream error", Map.of());
    assertEquals("UNKNOWN", publisher.publish(account, snapshot, UUID.randomUUID()).status());
  }

  @Test
  void rateLimitRespectsRetryAfter() throws Exception {
    response(429, "{}", Map.of("Retry-After", List.of("120")));
    var result = publisher.publish(account, snapshot, UUID.randomUUID());
    assertEquals("RETRY_WAIT", result.status());
    assertEquals(120, result.retrySeconds());
  }

  @Test
  void revokedTokenBlocksPublishing() throws Exception {
    response(400, "{\"error\":{\"code\":190}}", Map.of());
    assertEquals("BLOCKED", publisher.publish(account, snapshot, UUID.randomUUID()).status());
  }

  @Test
  void signatureIsComputedOnRawBody() throws Exception {
    byte[] body = "{\"object\":\"page\"}".getBytes(StandardCharsets.UTF_8);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    assertTrue(MetaWebhook.valid("secret", signature, body));
    assertFalse(MetaWebhook.valid("secret", signature, "{}".getBytes(StandardCharsets.UTF_8)));
    assertFalse(MetaWebhook.valid("secret", "sha256=malformed", body));
  }

  @Test
  void tokenEncryptionUsesRandomNonceAndRejectsWrongKey() {
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    var vault = new TokenVault(new MockEnvironment().withProperty("app.encryption-key", key));
    String a = vault.encrypt("page-token"), b = vault.encrypt("page-token");
    assertNotEquals(a, b);
    assertEquals("page-token", vault.decrypt(a));
    byte[] wrong = new byte[32];
    Arrays.fill(wrong, (byte) 3);
    var other =
        new TokenVault(
            new MockEnvironment()
                .withProperty("app.encryption-key", Base64.getEncoder().encodeToString(wrong)));
    assertThrows(IllegalStateException.class, () -> other.decrypt(a));
  }

  @Test
  void templateProducesDecodableSquarePng() throws Exception {
    byte[] png =
        new TemplateRenderer()
            .render(
                "Thương hiệu thử nghiệm", "Chuẩn bị gì trước buổi trao đổi đầu tiên?", "#8b76ef");
    var image = ImageIO.read(new ByteArrayInputStream(png));
    assertNotNull(image);
    assertEquals(1080, image.getWidth());
    assertEquals(1080, image.getHeight());
    assertTrue(png.length > 10000);
  }
}

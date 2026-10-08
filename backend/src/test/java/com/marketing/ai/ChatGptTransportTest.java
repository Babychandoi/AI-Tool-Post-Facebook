package com.marketing.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketing.shared.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class ChatGptTransportTest {
  Db db = new Db(new JdbcTemplate(), new ObjectMapper());
  HttpClient client = mock(HttpClient.class);
  KeyPair pair;
  ChatGptTransport provider;

  @BeforeEach
  void setup() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    pair = generator.generateKeyPair();
    provider =
        new ChatGptTransport(
            db, client, NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic()).build());
  }

  String jwt(String issuer, String audience, String nonce, Instant expires) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("subject")
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .expirationTime(Date.from(expires))
            .claim("nonce", nonce)
            .claim("email", "fixture@example.com")
            .build();
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
    return jwt.serialize();
  }

  @Test
  void identityVerifiesSignatureIssuerAudienceNonceAndExpiry() throws Exception {
    String token =
        jwt(ChatGptTransport.ISSUER, "oaiapp_fixture", "nonce", Instant.now().plusSeconds(60));
    assertEquals("subject", provider.identity(token, "oaiapp_fixture", "nonce").subject());
    assertThrows(Problem.class, () -> provider.identity(token, "oaiapp_other", "nonce"));
    assertThrows(Problem.class, () -> provider.identity(token, "oaiapp_fixture", "other-nonce"));
    assertThrows(
        Problem.class,
        () ->
            provider.identity(
                jwt(
                    "https://attacker.example",
                    "oaiapp_fixture",
                    "nonce",
                    Instant.now().plusSeconds(60)),
                "oaiapp_fixture",
                "nonce"));
    assertThrows(
        Problem.class,
        () ->
            provider.identity(
                jwt(
                    ChatGptTransport.ISSUER,
                    "oaiapp_fixture",
                    "nonce",
                    Instant.now().minusSeconds(300)),
                "oaiapp_fixture",
                "nonce"));
    var impostor = KeyPairGenerator.getInstance("RSA");
    impostor.initialize(2048);
    var wrong = impostor.generateKeyPair();
    var signed = SignedJWT.parse(token);
    signed = new SignedJWT(signed.getHeader(), signed.getJWTClaimsSet());
    signed.sign(new RSASSASigner((RSAPrivateKey) wrong.getPrivate()));
    String tampered = signed.serialize();
    assertThrows(Problem.class, () -> provider.identity(tampered, "oaiapp_fixture", "nonce"));
  }

  InputStream stream(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void sseRequiresAuthoritativeCompletedResponseAndSupportsCrLf() throws Exception {
    String text =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\r\n\r\n"
            + "data:"
            + " {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":123}}}\r\n\r\n";
    assertEquals(123, provider.completed(stream(text)).path("usage").path("input_tokens").asInt());
    assertThrows(
        Problem.class,
        () ->
            provider.completed(
                stream(
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                        + "data: [DONE]\n\n")));
    assertThrows(
        Problem.class,
        () ->
            provider.completed(
                stream(
                    "data:"
                        + " {\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"}}\n\n")));
  }

  @Test
  void streamUsageLimitIsNotTreatedAsSuccessOrRetried() {
    var failure =
        assertThrows(
            ChatGptTransport.ProviderFailure.class,
            () ->
                provider.completed(
                    stream(
                        "data:"
                            + " {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n")));
    assertEquals("subscription_sharing_usage_limit_exceeded", failure.code);
    assertTrue(ChatGptConnections.providerMessage(failure).getMessage().contains("hạn mức"));
  }

  @Test
  void sseInputIsBounded() {
    assertThrows(Problem.class, () -> provider.completed(stream("data: " + "x".repeat(270000))));
  }

  @SuppressWarnings("unchecked")
  void response(String body, int code, String type) throws Exception {
    HttpResponse<InputStream> r = mock(HttpResponse.class);
    when(r.statusCode()).thenReturn(code);
    when(r.body()).thenReturn(stream(body));
    when(r.headers())
        .thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(type)), (a, b) -> true));
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(r);
  }

  @Test
  void completedItemsAreUsedOnlyAfterMatchingFinalCompletion() throws Exception {
    String prefix =
        "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_fixture\"}}\n\n"
            + "data:"
            + " {\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"id\":\"reasoning_fixture\",\"type\":\"reasoning\",\"summary\":[]}}\n\n"
            + "data:"
            + " {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"msg_fixture\",\"status\":\"completed\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}}\n\n";
    String completed =
        "data:"
            + " {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_fixture\",\"status\":\"completed\",\"output\":[]}}\n\n";
    assertEquals(
        "hello",
        provider
            .completed(stream(prefix + completed))
            .path("output")
            .path(0)
            .path("content")
            .path(0)
            .path("text")
            .asText());
    assertThrows(Problem.class, () -> provider.completed(stream(prefix + "data: [DONE]\n\n")));
    assertThrows(
        Problem.class,
        () -> provider.completed(stream(prefix + completed.replace("resp_fixture", "resp_other"))));
  }

  @Test
  void missingContentTypeStillRequiresCompletedSse() throws Exception {
    response(
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
        200,
        "");
    assertEquals(
        "completed",
        provider.respond("fixture-access", Map.of("stream", true)).path("status").asText());
    response("<html>unexpected page</html>", 200, "");
    assertThrows(Problem.class, () -> provider.respond("fixture-access", Map.of("stream", true)));
    response("data: [DONE]\n\n", 200, "");
    assertThrows(Problem.class, () -> provider.respond("fixture-access", Map.of("stream", true)));
  }

  @Test
  void accountModelCatalogFiltersVisibilityWithoutInventingModels() throws Exception {
    response(
        "{\"models\":[{\"slug\":\"allowed\",\"display_name\":\"Allowed"
            + " model\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hidden\"}]}",
        200,
        "application/json");
    var catalog = provider.models("fixture-access");
    assertEquals(1, catalog.size());
    assertEquals("allowed", catalog.getFirst().get("slug"));
    verify(client)
        .send(
            argThat(
                (HttpRequest r) ->
                    r.uri().toString().equals("https://api.openai.com/v1/models")
                        && r.headers()
                            .firstValue("Authorization")
                            .orElse("")
                            .equals("Bearer fixture-access")),
            any(HttpResponse.BodyHandler.class));
  }

  @Test
  void errorShapeWithDetailIsSanitizedAndDoesNotTriggerRetry() throws Exception {
    response("{\"detail\":\"secret-untrusted-diagnostic\"}", 403, "application/json");
    var failure =
        assertThrows(
            ChatGptTransport.ProviderFailure.class,
            () -> provider.respond("fixture-access", Map.of("stream", true, "store", false)));
    assertEquals(403, failure.status);
    assertFalse(failure.getMessage().contains("secret"));
    verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  void scopeAndPkceContracts() {
    assertEquals(
        List.of("openid", "resource.invoke"),
        ChatGptConnections.scopes("openid resource.invoke openid"));
    assertEquals(
        "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        ChatGptConnections.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    assertFalse(ChatGptTransport.equal("abc", null));
    assertTrue(ChatGptTransport.equal("abc", "abc"));
  }
}

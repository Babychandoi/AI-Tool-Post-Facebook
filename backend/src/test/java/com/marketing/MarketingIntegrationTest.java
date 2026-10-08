package com.marketing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketing.content.ContentService;
import com.marketing.media.AssetStore;
import com.marketing.operations.*;
import com.marketing.publishing.*;
import com.marketing.shared.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "app.mode=test",
      "app.demo=true",
      "app.live-publishing=true",
      "app.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "app.meta-app-secret=fixture-secret",
      "app.chatgpt-enabled=true"
    })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class MarketingIntegrationTest {
  private static io.zonky.test.db.postgres.embedded.EmbeddedPostgres nativePg;

  @org.springframework.test.context.DynamicPropertySource
  static void nativeDatabase(org.springframework.test.context.DynamicPropertyRegistry registry) {
    if ("true".equals(System.getenv("EMBEDDED_DB"))) {
      try {
        nativePg =
            io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1")
                .start();
        registry.add(
            "spring.datasource.url",
            () -> "jdbc:postgresql://127.0.0.1:" + nativePg.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  @AfterAll
  static void closeDatabase() throws Exception {
    if (nativePg != null) nativePg.close();
  }

  @Autowired Db db;
  @Autowired org.springframework.core.env.Environment applicationEnvironment;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @Autowired ContentService content;
  @Autowired JobRepository jobs;
  @Autowired Budget budget;
  @Autowired PublicationProcessor publisher;
  @Autowired TokenVault vault;
  @Autowired com.marketing.ai.AiWriter writer;
  @Autowired com.marketing.ai.ChatGptConnections chatgpt;
  @Autowired com.marketing.workflow.WorkflowProcessor workflow;
  @Autowired com.marketing.workflow.AutopostService autopost;
  @MockitoBean com.marketing.media.AiImageGenerator imageGenerator;
  @MockitoBean com.marketing.media.ChatGptWebImages webImages;
  @MockitoBean com.marketing.ai.ChatGptTransport chatgptTransport;
  @MockitoBean org.springframework.data.redis.core.StringRedisTemplate redis;
  @MockitoBean AssetStore assets;
  @MockitoBean MetaPublisher meta;
  UUID workspace, otherWorkspace, brand, otherBrand, source, account;
  final Map<String, byte[]> files = new ConcurrentHashMap<>();

  @BeforeEach
  void seed() {
    when(webImages.status()).thenReturn(Map.of("ready", true, "message", "Fixture bridge ready"));
    db.jdbc.execute("TRUNCATE workspace,app_user CASCADE");
    files.clear();
    doAnswer(
            i -> {
              files.put(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(assets)
        .put(anyString(), any(byte[].class));
    when(assets.get(anyString())).thenAnswer(i -> files.get(i.getArgument(0)));
    workspace = UUID.randomUUID();
    otherWorkspace = UUID.randomUUID();
    brand = UUID.randomUUID();
    otherBrand = UUID.randomUUID();
    source = UUID.randomUUID();
    account = UUID.randomUUID();
    for (UUID w : List.of(workspace, otherWorkspace))
      db.jdbc.update("INSERT INTO workspace(id,name) VALUES(?,'fixture')", w);
    db.jdbc.update(
        "INSERT INTO brand(id,workspace_id,name) VALUES(?,?,'Brand A'),(?,?,'Brand B')",
        brand,
        workspace,
        otherBrand,
        otherWorkspace);
    for (String role : List.of("OWNER", "MARKETER", "REVIEWER", "PUBLISHER", "SALES", "ANALYST")) {
      UUID user = UUID.randomUUID();
      db.jdbc.update(
          "INSERT INTO app_user(id,email,name) VALUES(?,?,?)",
          user,
          role.toLowerCase() + "@fixture",
          role);
      db.jdbc.update(
          "INSERT INTO membership(user_id,workspace_id,role) VALUES(?,?,?)", user, workspace, role);
    }
    db.jdbc.update(
        "INSERT INTO knowledge_document(id,workspace_id,brand_id,title,body,status)"
            + " VALUES(?,?,?,'Source','Chuẩn bị nhu cầu và câu hỏi trước buổi trao"
            + " đổi.','APPROVED')",
        source,
        workspace,
        brand);
    db.jdbc.update(
        "INSERT INTO"
            + " social_account(id,workspace_id,brand_id,provider,remote_id,name,token_ciphertext)"
            + " VALUES(?,?,?,'META','111','Fixture Page',?)",
        account,
        workspace,
        brand,
        vault.encrypt("fixture-token"));
    when(meta.verify(anyString(), anyString())).thenReturn(Map.of("id", "111"));
  }

  private MockHttpServletRequestBuilder owner(MockHttpServletRequestBuilder request) {
    return request.with(user("owner@fixture").roles("USER")).with(csrf().asHeader());
  }

  private Map<String, Object> draft() {
    return content.create(
        workspace,
        brand,
        null,
        null,
        "owner@fixture",
        new ContentService.Draft(
            "Chuẩn bị trước buổi trao đổi",
            "Chuẩn bị nhu cầu và câu hỏi trước buổi trao đổi.",
            "Lưu lại để tham khảo.",
            List.of(source)));
  }

  private void approve(Map<String, Object> r) throws Exception {
    mvc.perform(owner(post("/v1/revisions/" + r.get("id") + "/submit"))).andExpect(status().isOk());
    mvc.perform(
            owner(post("/v1/revisions/" + r.get("id") + "/approvals"))
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "decision",
                            "APPROVED",
                            "note",
                            "Verified fixture",
                            "expected_hash",
                            r.get("snapshot_hash")))))
        .andExpect(status().isOk());
  }

  private Map<String, Object> schedule(Map<String, Object> r, String key) throws Exception {
    String response =
        mvc.perform(
                owner(post("/v1/publications"))
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "revision_id",
                                r.get("id"),
                                "account_id",
                                account,
                                "intent",
                                key,
                                "due_at",
                                java.time.Instant.now().plusSeconds(10).toString()))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var p = db.map(response);
    db.jdbc.update("UPDATE job SET due_at=now() WHERE aggregate_id=?", Db.id(p.get("id")));
    return p;
  }

  @Test
  void tenantIsolationAndRoleEnforcement() throws Exception {
    mvc.perform(owner(get("/v1/contents").param("brand_id", otherBrand.toString())))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/v1/contents")
                .with(user("analyst@fixture"))
                .with(csrf().asHeader())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "brand_id",
                            brand,
                            "draft",
                            Map.of(
                                "hook",
                                "Title",
                                "body",
                                "Body",
                                "cta",
                                "CTA",
                                "source_refs",
                                List.of(source))))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/v1/brands")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/v1/brands/" + brand + "/pause")
                .with(user("owner@fixture"))
                .contentType("application/json")
                .content("{\"enabled\":false}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void editedRevisionDoesNotInheritApproval() throws Exception {
    var r = draft();
    approve(r);
    var edited =
        content.create(
            workspace,
            brand,
            Db.id(r.get("content_id")),
            null,
            "owner@fixture",
            new ContentService.Draft("Edited hook", "Edited body", "CTA", List.of(source)));
    mvc.perform(
            owner(post("/v1/publications"))
                .header("Idempotency-Key", "new-revision")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "revision_id",
                            edited.get("id"),
                            "account_id",
                            account,
                            "intent",
                            "edited",
                            "due_at",
                            java.time.Instant.now().plusSeconds(20).toString()))))
        .andExpect(status().isConflict());
    assertEquals(
        "APPROVED",
        db.one("SELECT status FROM content_revision WHERE id=?", r.get("id")).get("status"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.jdbc.update("UPDATE content_revision SET body='changed' WHERE id=?", r.get("id")));
  }

  @Test
  void policyChangeInvalidatesApproval() throws Exception {
    var r = draft();
    approve(r);
    db.jdbc.update("UPDATE brand SET policy_version=policy_version+1 WHERE id=?", brand);
    mvc.perform(
            owner(post("/v1/publications"))
                .header("Idempotency-Key", "policy-test")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "revision_id",
                            r.get("id"),
                            "account_id",
                            account,
                            "intent",
                            "policy",
                            "due_at",
                            java.time.Instant.now().plusSeconds(20).toString()))))
        .andExpect(status().isConflict());
  }

  @Test
  void sameIdempotencyKeyOnlyCreatesOneRun() throws Exception {
    String request = json.writeValueAsString(Map.of("brand_id", brand, "topic", "Topic"));
    for (int i = 0; i < 2; i++)
      mvc.perform(
              owner(post("/v1/runs"))
                  .header("Idempotency-Key", "stable")
                  .contentType("application/json")
                  .content(request))
          .andExpect(status().isAccepted());
    assertEquals(1, db.jdbc.queryForObject("SELECT count(*) FROM workflow_run", Integer.class));
    assertEquals(1, db.jdbc.queryForObject("SELECT count(*) FROM job", Integer.class));
    mvc.perform(
            owner(post("/v1/runs"))
                .header("Idempotency-Key", "stable")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("brand_id", brand, "topic", "Different"))))
        .andExpect(status().isConflict());
  }

  @Test
  void ambiguousProviderResultStopsAutomaticRetries() throws Exception {
    var r = draft();
    approve(r);
    var p = schedule(r, "timeout");
    when(meta.publish(anyMap(), anyMap(), any(UUID.class)))
        .thenReturn(
            new SocialPublisher.Result("UNKNOWN", null, null, "NETWORK_AMBIGUOUS", 0, null));
    var j = jobs.claim();
    publisher.process(j);
    assertEquals(
        "UNKNOWN",
        db.one("SELECT status FROM publication WHERE id=?", Db.id(p.get("id"))).get("status"));
    assertNull(jobs.claim());
    verify(meta, times(1)).publish(anyMap(), anyMap(), any(UUID.class));
  }

  @Test
  void pauseAndRevokedActorBlockPreflight() throws Exception {
    var r = draft();
    approve(r);
    schedule(r, "paused");
    db.jdbc.update("UPDATE brand SET enabled=false WHERE id=?", brand);
    var j = jobs.claim();
    assertThrows(Problem.class, () -> publisher.process(j));
    verify(meta, never()).publish(anyMap(), anyMap(), any(UUID.class));
  }

  @Test
  void leaseExpiryFencesOldWorkerAndMarksUnknown() throws Exception {
    var r = draft();
    approve(r);
    var p = schedule(r, "lease");
    var j = jobs.claim();
    db.jdbc.update("UPDATE publication SET status='DISPATCHING' WHERE id=?", Db.id(p.get("id")));
    db.jdbc.update("UPDATE job SET lease_until=now()-interval '1 second' WHERE id=?", j.get("id"));
    jobs.recover(budget);
    assertThrows(JobRepository.LeaseLost.class, () -> jobs.fenced(j, () -> null));
    assertEquals(
        "UNKNOWN",
        db.one("SELECT status FROM publication WHERE id=?", Db.id(p.get("id"))).get("status"));
    assertNull(jobs.claim());
  }

  @Test
  void concurrentWorkersDoNotClaimSameJob() throws Exception {
    db.jdbc.update(
        "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id) VALUES(?,?,'GENERATE',?)",
        workspace,
        brand,
        UUID.randomUUID());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Map<String, Object>> task =
          () -> {
            start.await();
            return jobs.claim();
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      var claimed = new ArrayList<Map<String, Object>>();
      var aa = a.get();
      var bb = b.get();
      if (aa != null) claimed.add(aa);
      if (bb != null) claimed.add(bb);
      assertEquals(1, claimed.size());
    }
  }

  @Test
  void webhookSignatureDedupAndTenantMapping() throws Exception {
    String payload =
        "{\"object\":\"page\",\"entry\":[{\"id\":\"111\",\"workspace_id\":\"attacker\",\"changes\":[{\"field\":\"feed\",\"value\":{\"post_id\":\"111_222\"}}]}]}";
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("fixture-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature =
        "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/v1/webhooks/meta")
                  .header("X-Hub-Signature-256", signature)
                  .contentType("application/json")
                  .content(payload))
          .andExpect(status().isOk());
    assertEquals(1, db.jdbc.queryForObject("SELECT count(*) FROM inbox_event", Integer.class));
    assertEquals(workspace, db.one("SELECT workspace_id FROM inbox_event").get("workspace_id"));
    mvc.perform(
            post("/v1/webhooks/meta")
                .header("X-Hub-Signature-256", "sha256=bad")
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isForbidden());
  }

  @Test
  void budgetReservationIsAtomic() throws Exception {
    db.jdbc.update("UPDATE brand SET monthly_budget_micros=100000 WHERE id=?", brand);
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    for (UUID id : List.of(a, b))
      db.jdbc.update(
          "INSERT INTO"
              + " workflow_run(id,workspace_id,brand_id,topic,brand_version,policy_version,created_by)"
              + " VALUES(?,?,?,'Topic',1,1,'owner@fixture')",
          id,
          workspace,
          brand);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var results = new ArrayList<Future<Boolean>>();
      for (UUID run : List.of(a, b))
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    budget.reserve(workspace, brand, run, 70000);
                    return true;
                  } catch (Problem e) {
                    return false;
                  }
                }));
      start.countDown();
      int successes = 0;
      for (var result : results) if (result.get()) successes++;
      assertEquals(1, successes);
      assertEquals(
          70000L, db.jdbc.queryForObject("SELECT reserved_micros FROM budget_month", Long.class));
    }
  }

  @Test
  void uncertainPublicationCannotBeResentWithNewIntent() throws Exception {
    var r = draft();
    approve(r);
    var p = schedule(r, "uncertain");
    db.jdbc.update("UPDATE publication SET status='UNKNOWN' WHERE id=?", Db.id(p.get("id")));
    mvc.perform(
            owner(post("/v1/publications"))
                .header("Idempotency-Key", "different-key")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "revision_id",
                            r.get("id"),
                            "account_id",
                            account,
                            "intent",
                            "new-intent",
                            "due_at",
                            java.time.Instant.now().plusSeconds(10).toString()))))
        .andExpect(status().isConflict());
  }

  @Test
  void separateReviewerPolicyIsEnforced() throws Exception {
    var r = draft();
    db.jdbc.update("UPDATE brand SET require_separate_reviewer=true WHERE id=?", brand);
    mvc.perform(owner(post("/v1/revisions/" + r.get("id") + "/submit"))).andExpect(status().isOk());
    String body =
        json.writeValueAsString(
            Map.of("decision", "APPROVED", "note", "", "expected_hash", r.get("snapshot_hash")));
    mvc.perform(
            owner(post("/v1/revisions/" + r.get("id") + "/approvals"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/v1/revisions/" + r.get("id") + "/approvals")
                .with(user("reviewer@fixture"))
                .with(csrf().asHeader())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void dailyCampaignTriggerIsIdempotentAndHonorsPause() {
    UUID c = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " campaign(id,workspace_id,brand_id,name,objective,generation_enabled,daily_generation_time,created_by,pillars)"
            + " VALUES(?,?,?,'Daily','Fixture',true,'00:00','owner@fixture','[\"Daily"
            + " topic\"]'::jsonb)",
        c,
        workspace,
        brand);
    var scheduler =
        new com.marketing.workflow.CampaignScheduler(
            db,
            budget,
            new com.marketing.identity.ActorPolicy(db),
            new org.springframework.mock.env.MockEnvironment(),
            writer);
    db.jdbc.update("UPDATE brand SET enabled=false WHERE id=?", brand);
    scheduler.plan();
    assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM workflow_run", Integer.class));
    db.jdbc.update("UPDATE brand SET enabled=true WHERE id=?", brand);
    scheduler.plan();
    scheduler.plan();
    assertEquals(1, db.jdbc.queryForObject("SELECT count(*) FROM workflow_run", Integer.class));
    assertEquals(1, db.jdbc.queryForObject("SELECT count(*) FROM budget_ledger", Integer.class));
  }

  @Test
  void expiredEvidenceIsRejected() {
    db.jdbc.update(
        "UPDATE knowledge_document SET effective_from=now()-interval '2"
            + " days',effective_until=now()-interval '1 day' WHERE id=?",
        source);
    assertThrows(Problem.class, () -> draft());
  }

  private com.marketing.identity.Scope.Member aiOwner() {
    return new com.marketing.identity.Scope.Member(
        workspace, "owner@fixture", "Owner", "OWNER", null);
  }

  private Map<String, Object> aiTokens(String access, String refresh, String scope) {
    return new LinkedHashMap<>(
        Map.of(
            "access_token",
            access,
            "refresh_token",
            refresh,
            "id_token",
            "fixture-id-token",
            "token_type",
            "Bearer",
            "expires_in",
            3600,
            "scope",
            scope));
  }

  private Map<String, String> aiQuery(String url) {
    var result = new HashMap<String, String>();
    for (String pair : java.net.URI.create(url).getRawQuery().split("&")) {
      var parts = pair.split("=", 2);
      result.put(
          java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8));
    }
    return result;
  }

  private UUID connectAi(String granted) {
    var session = new org.springframework.mock.web.MockHttpSession();
    var q = aiQuery(chatgpt.start(aiOwner(), session, null, false));
    when(chatgptTransport.token(anyMap()))
        .thenReturn(aiTokens("fixture-access", "fixture-refresh", granted));
    when(chatgptTransport.identity(anyString(), anyString(), anyString()))
        .thenReturn(
            new com.marketing.ai.ChatGptTransport.Identity(
                "fixture-subject", "person@example.com"));
    chatgpt.callback(aiOwner(), session, q.get("state"), "fixture-code", "oaiapp_fixture", null);
    return Db.id(chatgpt.active(workspace, "owner@fixture").get("id"));
  }

  @Test
  void chatgptUsesPkceAndIssuedClientAndNeverExposesTokens() throws Exception {
    var result =
        mvc.perform(
                owner(post("/v1/ai/chatgpt/connect")).contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andReturn();
    var session = (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession();
    var q =
        aiQuery(
            json.readTree(result.getResponse().getContentAsString())
                .path("authorization_url")
                .asText());
    assertEquals("dynamic_agent_client", q.get("client_id"));
    assertEquals("S256", q.get("code_challenge_method"));
    assertEquals("AI Marketing", q.get("agent_name_hint"));
    assertTrue(q.get("scope").contains("chatgpt.tokens.use.direct"));
    var attempt =
        (com.marketing.ai.ChatGptConnections.Attempt) session.getAttribute("chatgpt.oauth.attempt");
    assertNotEquals(attempt.verifier(), q.get("code_challenge"));
    when(chatgptTransport.token(anyMap()))
        .thenReturn(
            aiTokens(
                "secret-fixture-access",
                "secret-fixture-refresh",
                "openid resource.invoke chatgpt.tokens.use.direct"));
    when(chatgptTransport.identity(anyString(), eq("oaiapp_fixture"), eq(q.get("nonce"))))
        .thenReturn(
            new com.marketing.ai.ChatGptTransport.Identity(
                "fixture-subject", "person@example.com"));
    mvc.perform(
            owner(get("/v1/ai/chatgpt/callback"))
                .session(session)
                .param("state", q.get("state"))
                .param("code", "fixture-code")
                .param("client_id", "oaiapp_fixture"))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", "/?chatgpt=connected#ai"));
    verify(chatgptTransport)
        .token(
            argThat(
                v ->
                    "oaiapp_fixture".equals(v.get("client_id"))
                        && attempt.verifier().equals(v.get("code_verifier"))
                        && attempt.redirect().equals(v.get("redirect_uri"))));
    var row = chatgpt.active(workspace, "owner@fixture");
    assertFalse(row.get("credentials_cipher").toString().contains("secret-fixture"));
    var response =
        mvc.perform(owner(get("/v1/ai/chatgpt"))).andReturn().getResponse().getContentAsString();
    assertFalse(response.contains("credentials_cipher"));
    assertFalse(response.contains("secret-fixture"));
    assertFalse(response.contains("access_token"));
    mvc.perform(
            owner(get("/v1/ai/chatgpt/callback"))
                .session(session)
                .param("state", q.get("state"))
                .param("code", "fixture-code")
                .param("client_id", "oaiapp_fixture"))
        .andExpect(header().string("Location", "/?chatgpt=error#ai"));
    verify(chatgptTransport, times(1)).token(anyMap());
  }

  @Test
  void chatgptRejectsStateAndChangedClientAndPreservesExistingConnection() {
    UUID id = connectAi("resource.invoke chatgpt.tokens.use.direct");
    clearInvocations(chatgptTransport);
    var session = new org.springframework.mock.web.MockHttpSession();
    var q = aiQuery(chatgpt.start(aiOwner(), session, id, false));
    assertEquals("oaiapp_fixture", q.get("client_id"));
    assertFalse(q.containsKey("agent_name_hint"));
    assertEquals("fixture-id-token", q.get("id_token_hint"));
    assertThrows(
        Problem.class,
        () -> chatgpt.callback(aiOwner(), session, "incorrect-state", "code", null, null));
    assertThrows(
        Problem.class,
        () -> chatgpt.callback(aiOwner(), session, q.get("state"), "code", "oaiapp_other", null));
    verifyNoInteractions(chatgptTransport);
    assertEquals(id, Db.id(chatgpt.active(workspace, "owner@fixture").get("id")));
    var canceled = aiQuery(chatgpt.start(aiOwner(), session, id, false));
    assertEquals(
        "declined",
        chatgpt.callback(aiOwner(), session, canceled.get("state"), null, null, "access_denied"));
    assertEquals("CONNECTED", chatgpt.active(workspace, "owner@fixture").get("status"));
  }

  @Test
  void chatgptConnectionRequiresCsrfRoleAndOwnerScope() throws Exception {
    UUID id = connectAi("resource.invoke chatgpt.tokens.use.direct");
    mvc.perform(
            post("/v1/ai/chatgpt/connect")
                .with(user("owner@fixture"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/v1/ai/chatgpt/connect")
                .with(user("analyst@fixture"))
                .with(csrf().asHeader())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    var marketer = mvc.perform(get("/v1/ai/chatgpt").with(user("marketer@fixture"))).andReturn();
    assertEquals(
        0, json.readTree(marketer.getResponse().getContentAsString()).path("accounts").size());
    mvc.perform(get("/v1/ai/chatgpt/" + id + "/models").with(user("marketer@fixture")))
        .andExpect(status().isNotFound());
  }

  @Test
  void chatgptMissingPlanScopeBlocksGenerationWithoutApiFallback() throws Exception {
    connectAi("openid email profile");
    assertEquals("PLAN_DISABLED", chatgpt.active(workspace, "owner@fixture").get("status"));
    mvc.perform(
            owner(post("/v1/runs"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(db.json(Map.of("brand_id", brand, "topic", "Fixture"))))
        .andExpect(status().isConflict());
    assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM workflow_run", Integer.class));
  }

  @Test
  void chatgptRefreshSerializesAcrossWorkersAndPersistsRotatingToken() throws Exception {
    UUID id = connectAi("resource.invoke chatgpt.tokens.use.direct");
    clearInvocations(chatgptTransport);
    db.jdbc.update(
        "UPDATE chatgpt_connection SET expires_at=now()-interval '1"
            + " minute',earliest_refresh_at=now()-interval '2 minutes' WHERE id=?",
        id);
    when(chatgptTransport.token(anyMap()))
        .thenAnswer(
            i -> {
              Thread.sleep(100);
              return aiTokens(
                  "rotated-access", "rotated-refresh", "resource.invoke chatgpt.tokens.use.direct");
            });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> chatgpt.access(workspace, "owner@fixture", id, true));
      var b = pool.submit(() -> chatgpt.access(workspace, "owner@fixture", id, true));
      assertEquals("rotated-access", a.get(5, TimeUnit.SECONDS).token());
      assertEquals("rotated-access", b.get(5, TimeUnit.SECONDS).token());
    } finally {
      pool.shutdownNow();
    }
    verify(chatgptTransport, times(1))
        .token(
            argThat(
                p ->
                    "refresh_token".equals(p.get("grant_type"))
                        && "fixture-refresh".equals(p.get("refresh_token"))
                        && !p.containsKey("scope")));
    assertTrue(
        vault
            .decrypt(
                chatgpt.active(workspace, "owner@fixture").get("credentials_cipher").toString())
            .contains("rotated-refresh"));
  }

  @Test
  void chatgptPinnedWorkflowUsesStreamPlanAndNoApiDollarLedger() throws Exception {
    UUID id = connectAi("resource.invoke chatgpt.tokens.use.direct");
    when(chatgptTransport.models(anyString()))
        .thenReturn(List.of(Map.of("slug", "account-model", "display_name", "Account model")));
    chatgpt.selectModel(aiOwner(), id, "account-model");
    assertThrows(Problem.class, () -> chatgpt.selectModel(aiOwner(), id, "invented-model"));
    when(chatgptTransport.respond(anyString(), anyMap()))
        .thenReturn(
            json.readTree(
                db.json(
                    Map.of(
                        "status",
                        "completed",
                        "output",
                        List.of(
                            Map.of(
                                "content",
                                List.of(
                                    Map.of(
                                        "type",
                                        "output_text",
                                        "text",
                                        db.json(
                                            new ContentService.Draft(
                                                "Fixture hook",
                                                "Fixture body",
                                                "Fixture CTA",
                                                List.of(source))))))),
                        "usage",
                        Map.of("input_tokens", 100, "output_tokens", 50)))));
    var result =
        mvc.perform(
                owner(post("/v1/runs"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content(db.json(Map.of("brand_id", brand, "topic", "ChatGPT fixture"))))
            .andExpect(status().isAccepted())
            .andReturn();
    UUID run = Db.id(db.map(result.getResponse().getContentAsString()).get("id"));
    assertEquals(
        id, Db.id(db.one("SELECT * FROM workflow_run WHERE id=?", run).get("ai_connection_id")));
    assertEquals(
        0,
        db.jdbc.queryForObject(
            "SELECT count(*) FROM budget_ledger WHERE run_id=?", Integer.class, run));
    workflow.process(jobs.claim());
    assertEquals(
        "CHATGPT_PLAN",
        db.one("SELECT usage_status FROM ai_run WHERE run_id=?", run).get("usage_status"));
    verify(chatgptTransport)
        .respond(
            eq("fixture-access"),
            argThat(
                p ->
                    Boolean.TRUE.equals(p.get("stream"))
                        && Boolean.FALSE.equals(p.get("store"))
                        && p.containsKey("instructions")
                        && !p.containsKey("max_output_tokens")
                        && "account-model".equals(p.get("model"))
                        && !db.json(p.get("input")).contains("\"system\"")));
    db.jdbc.update("UPDATE chatgpt_connection SET active=false WHERE id=?", id);
    assertThrows(Problem.class, () -> chatgpt.access(workspace, "owner@fixture", id, true));
  }

  @Test
  void chatgptDisconnectClearsCredentialsButKeepsRegistrationAndReportsRevokeFailure()
      throws Exception {
    UUID id = connectAi("resource.invoke chatgpt.tokens.use.direct");
    when(chatgptTransport.revoke(anyString(), anyString())).thenReturn(false);
    mvc.perform(owner(delete("/v1/ai/chatgpt/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remote_revocation_confirmed").value(false));
    var row = chatgpt.owned(workspace, "owner@fixture", id, false);
    assertEquals("oaiapp_fixture", row.get("client_id"));
    assertEquals("DISCONNECTED", row.get("status"));
    assertNull(row.get("credentials_cipher"));
    assertEquals(false, row.get("active"));
  }

  @Test
  void chatgptTerminalRefreshErrorCommitsInvalidationWithoutDeletingClient() {
    UUID id = connectAi("resource.invoke chatgpt.tokens.use.direct");
    db.jdbc.update(
        "UPDATE chatgpt_connection SET expires_at=now()-interval '1"
            + " minute',earliest_refresh_at=now()-interval '2 minutes' WHERE id=?",
        id);
    when(chatgptTransport.token(anyMap()))
        .thenThrow(new com.marketing.ai.ChatGptTransport.ProviderFailure(400, "invalid_grant"));
    assertThrows(Problem.class, () -> chatgpt.access(workspace, "owner@fixture", id, true));
    var account = chatgpt.active(workspace, "owner@fixture");
    assertEquals("REAUTH_REQUIRED", account.get("status"));
    assertNull(account.get("credentials_cipher"));
    assertEquals("oaiapp_fixture", account.get("client_id"));
    assertThrows(Problem.class, () -> writer.plan(workspace, "owner@fixture"));
  }

  @Test
  void chatgptReauthorizationCannotReplaceVerifiedAccountIdentity() {
    UUID id = connectAi("resource.invoke chatgpt.tokens.use.direct");
    var session = new org.springframework.mock.web.MockHttpSession();
    var q = aiQuery(chatgpt.start(aiOwner(), session, id, false));
    when(chatgptTransport.identity(anyString(), anyString(), anyString()))
        .thenReturn(
            new com.marketing.ai.ChatGptTransport.Identity("wrong-subject", "wrong@example.com"));
    assertThrows(
        Problem.class,
        () -> chatgpt.callback(aiOwner(), session, q.get("state"), "code", null, null));
    assertEquals("fixture-subject", chatgpt.active(workspace, "owner@fixture").get("subject"));
    assertEquals(
        "person@example.com", chatgpt.active(workspace, "owner@fixture").get("account_email"));
  }

  private void autopostAi(String body) throws Exception {
    UUID id = connectAi("resource.invoke chatgpt.tokens.use.direct");
    when(chatgptTransport.models(anyString()))
        .thenReturn(List.of(Map.of("slug", "account-model", "display_name", "Account model")));
    chatgpt.selectModel(aiOwner(), id, "account-model");
    when(chatgptTransport.respond(anyString(), anyMap()))
        .thenReturn(
            json.readTree(
                db.json(
                    Map.of(
                        "status",
                        "completed",
                        "output",
                        List.of(
                            Map.of(
                                "content",
                                List.of(
                                    Map.of(
                                        "type",
                                        "output_text",
                                        "text",
                                        db.json(
                                            new ContentService.Draft(
                                                "Daily post",
                                                body,
                                                "Lưu lại để tham khảo.",
                                                List.of(source))))))),
                        "usage",
                        Map.of("input_tokens", 100, "output_tokens", 50)))));
  }

  private Map<String, Object> autopostSettings(String mode, boolean enabled) {
    UUID page = account;
    if ("DEMO".equals(mode)) {
      var existing =
          db.list("SELECT id FROM social_account WHERE brand_id=? AND provider='DEMO'", brand);
      if (existing.isEmpty()) {
        page = UUID.randomUUID();
        db.jdbc.update(
            "INSERT INTO social_account(id,workspace_id,brand_id,provider,remote_id,name)"
                + " VALUES(?,?,?,'DEMO',?,'Demo Page')",
            page,
            workspace,
            brand,
            page.toString());
      } else page = Db.id(existing.getFirst().get("id"));
    }
    var value = new LinkedHashMap<String, Object>();
    value.put("account_id", page);
    value.put("objective", "Chia sẻ thông tin hữu ích");
    value.put("pillars", List.of("Mẹo chuẩn bị"));
    value.put("mode", mode);
    value.put("enabled", enabled);
    value.put(
        "daily_post_time",
        java.time.Instant.now()
            .plusSeconds(1200)
            .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .toLocalTime()
            .withSecond(0)
            .withNano(0)
            .toString());
    value.put("image_mode", "TEMPLATE");
    value.put("image_prompt", "");
    value.put("image_model", "gpt-image-2.5-sunburst");
    value.put("image_reserve_micros", 100000L);
    value.put("live_consent", !"DEMO".equals(mode));
    value.put("expected_version", 0);
    return value;
  }

  private Map<String, Object> autopostSave(Map<String, Object> settings) throws Exception {
    return db.map(
        mvc.perform(
                owner(put("/v1/brands/" + brand + "/autopost"))
                    .contentType("application/json")
                    .content(db.json(settings)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private UUID autopostDaily() {
    var c = autopost.config(workspace, brand);
    var b = db.one("SELECT * FROM brand WHERE id=?", brand);
    var slot = com.marketing.workflow.AutopostService.nextSlot(c, b, java.time.Instant.now());
    return autopost.daily(Db.id(c.get("id")), slot.minusSeconds(300));
  }

  private Map<String, Object> autopostGenerated() {
    UUID id = autopostDaily();
    assertNotNull(id);
    workflow.process(jobs.claim());
    return db.one("SELECT * FROM workflow_run WHERE id=?", id);
  }

  @Test
  void autopostRequiresOwnerCsrfScopeAndNeverReturnsImageKey() throws Exception {
    var settings = autopostSettings("DEMO", false);
    settings.put("image_api_key", "fixture-image-secret");
    String path = "/v1/brands/" + brand + "/autopost";
    mvc.perform(
            put(path)
                .with(user("owner@fixture"))
                .contentType("application/json")
                .content(db.json(settings)))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(path)
                .with(user("marketer@fixture"))
                .with(csrf().asHeader())
                .contentType("application/json")
                .content(db.json(settings)))
        .andExpect(status().isForbidden());
    mvc.perform(owner(get("/v1/brands/" + otherBrand + "/autopost")))
        .andExpect(status().isNotFound());
    var result = autopostSave(settings);
    assertFalse(db.json(result).contains("fixture-image-secret"));
    assertFalse(db.json(result).contains("image_key_ciphertext"));
    var c = autopost.config(workspace, brand);
    assertEquals("fixture-image-secret", vault.decrypt(c.get("image_key_ciphertext").toString()));
    mvc.perform(owner(put(path)).contentType("application/json").content(db.json(settings)))
        .andExpect(status().isConflict());
    settings.put("expected_version", 1);
    settings.remove("image_api_key");
    autopostSave(settings);
    assertEquals(
        "fixture-image-secret",
        vault.decrypt(autopost.config(workspace, brand).get("image_key_ciphertext").toString()));
    settings.put("expected_version", 2);
    settings.put("clear_image_key", true);
    autopostSave(settings);
    assertNull(autopost.config(workspace, brand).get("image_key_ciphertext"));
  }

  @Test
  void autopostCannotEnableRealPostingWithoutAiConsentOrRealPage() throws Exception {
    var settings = autopostSettings("AUTO", true);
    String path = "/v1/brands/" + brand + "/autopost";
    mvc.perform(owner(put(path)).contentType("application/json").content(db.json(settings)))
        .andExpect(status().isConflict());
    autopostAi("Chuẩn bị nhu cầu trước buổi trao đổi.");
    settings.put("live_consent", false);
    mvc.perform(owner(put(path)).contentType("application/json").content(db.json(settings)))
        .andExpect(status().isConflict());
    settings.put("live_consent", true);
    settings.put("account_id", autopostSettings("DEMO", false).get("account_id"));
    mvc.perform(owner(put(path)).contentType("application/json").content(db.json(settings)))
        .andExpect(status().isConflict());
    assertNull(autopost.config(workspace, brand));
  }

  @Test
  void autopostDailyCreatesOneRunAndPublishesOnlyToDemoWithoutMetaCalls() throws Exception {
    autopostSave(autopostSettings("DEMO", true));
    clearInvocations(meta);
    var c = autopost.config(workspace, brand);
    var b = db.one("SELECT * FROM brand WHERE id=?", brand);
    var slot = com.marketing.workflow.AutopostService.nextSlot(c, b, java.time.Instant.now());
    assertNull(autopost.daily(Db.id(c.get("id")), slot.minusSeconds(1900)));
    UUID run = autopost.daily(Db.id(c.get("id")), slot.minusSeconds(300));
    assertNotNull(run);
    assertNull(autopost.daily(Db.id(c.get("id")), slot.minusSeconds(200)));
    workflow.process(jobs.claim());
    var p = db.one("SELECT * FROM publication WHERE autopost_config_id=?", c.get("id"));
    assertEquals("QUEUED", p.get("status"));
    db.jdbc.update("UPDATE job SET due_at=now() WHERE aggregate_id=?", p.get("id"));
    publisher.process(jobs.claim());
    assertEquals(
        "PUBLISHED", db.one("SELECT * FROM publication WHERE id=?", p.get("id")).get("status"));
    assertEquals(
        1,
        db.jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE id=?", Integer.class, run));
    verifyNoInteractions(meta);
  }

  @Test
  void autopostChatgptAutoQueuesVerifiedSnapshotAndDispatchesWithConsent() throws Exception {
    autopostAi("Chuẩn bị nhu cầu trước buổi trao đổi.");
    autopostSave(autopostSettings("AUTO", true));
    var run = autopostGenerated();
    assertEquals("COMPLETED", run.get("status"));
    var p = db.one("SELECT * FROM publication WHERE revision_id=?", run.get("output_revision_id"));
    var snap = (Map<?, ?>) p.get("snapshot");
    assertTrue(snap.get("message").toString().contains("Daily post"));
    when(meta.publish(anyMap(), anyMap(), any()))
        .thenReturn(
            new SocialPublisher.Result(
                "PUBLISHED",
                "111_222",
                "https://www.facebook.com/111_222",
                null,
                0,
                "fixture-request"));
    db.jdbc.update("UPDATE job SET due_at=now() WHERE aggregate_id=?", p.get("id"));
    publisher.process(jobs.claim());
    assertEquals(
        "PUBLISHED", db.one("SELECT * FROM publication WHERE id=?", p.get("id")).get("status"));
    verify(meta, times(1)).publish(anyMap(), anyMap(), any());
  }

  @Test
  void autopostRiskFlagWaitsForHumanAndApprovalAutomaticallySchedules() throws Exception {
    autopostAi("Tham khảo thông tin hợp đồng trước khi sử dụng.");
    autopostSave(autopostSettings("AUTO", true));
    var run = autopostGenerated();
    assertEquals("WAITING_REVIEW", run.get("status"));
    assertTrue(db.list("SELECT id FROM publication").isEmpty());
    var r = db.one("SELECT * FROM content_revision WHERE id=?", run.get("output_revision_id"));
    mvc.perform(
            owner(post("/v1/revisions/" + r.get("id") + "/approvals"))
                .contentType("application/json")
                .content(
                    db.json(
                        Map.of(
                            "decision",
                            "APPROVED",
                            "note",
                            "Đã kiểm tra",
                            "expected_hash",
                            r.get("snapshot_hash")))))
        .andExpect(status().isOk());
    assertEquals(
        "QUEUED",
        db.one("SELECT status FROM publication WHERE revision_id=?", r.get("id")).get("status"));
  }

  @Test
  void autopostReviewAlwaysWaitsAndQueuesAfterHumanApproval() throws Exception {
    autopostAi("Thông tin tham khảo hữu ích.");
    autopostSave(autopostSettings("REVIEW", true));
    var run = autopostGenerated();
    assertEquals("WAITING_REVIEW", run.get("status"));
    assertTrue(db.list("SELECT id FROM publication").isEmpty());
    var r = db.one("SELECT * FROM content_revision WHERE id=?", run.get("output_revision_id"));
    mvc.perform(
            owner(post("/v1/revisions/" + r.get("id") + "/approvals"))
                .contentType("application/json")
                .content(
                    db.json(
                        Map.of(
                            "decision",
                            "APPROVED",
                            "note",
                            "Verified",
                            "expected_hash",
                            r.get("snapshot_hash")))))
        .andExpect(status().isOk());
    assertEquals(
        1,
        db.jdbc.queryForObject(
            "SELECT count(*) FROM publication WHERE revision_id=?", Integer.class, r.get("id")));
  }

  @Test
  void autopostPreviewNeverPublishesEvenAfterApproval() throws Exception {
    autopostSave(autopostSettings("DEMO", false));
    mvc.perform(owner(post("/v1/brands/" + brand + "/autopost/preview")))
        .andExpect(status().isAccepted());
    workflow.process(jobs.claim());
    var run = db.one("SELECT * FROM workflow_run WHERE autopost_preview=true");
    assertEquals("WAITING_REVIEW", run.get("status"));
    var r = db.one("SELECT * FROM content_revision WHERE id=?", run.get("output_revision_id"));
    mvc.perform(
            owner(post("/v1/revisions/" + r.get("id") + "/approvals"))
                .contentType("application/json")
                .content(
                    db.json(
                        Map.of(
                            "decision",
                            "APPROVED",
                            "note",
                            "Preview",
                            "expected_hash",
                            r.get("snapshot_hash")))))
        .andExpect(status().isOk());
    assertTrue(db.list("SELECT id FROM publication").isEmpty());
  }

  @Test
  void autopostStopCancelsPendingAndRevokesAutomaticApproval() throws Exception {
    autopostSave(autopostSettings("DEMO", true));
    var run = autopostGenerated();
    var c = autopost.config(workspace, brand);
    var p = db.one("SELECT * FROM publication WHERE revision_id=?", run.get("output_revision_id"));
    mvc.perform(
            owner(post("/v1/brands/" + brand + "/autopost/stop"))
                .contentType("application/json")
                .content(db.json(Map.of("expected_version", c.get("version")))))
        .andExpect(status().isOk());
    assertEquals(
        "CANCELLED", db.one("SELECT * FROM publication WHERE id=?", p.get("id")).get("status"));
    assertFalse(Boolean.TRUE.equals(autopost.config(workspace, brand).get("enabled")));
    assertThrows(Problem.class, () -> autopost.forRun(run, false));
    assertNull(jobs.claim());
    var r = db.one("SELECT * FROM content_revision WHERE id=?", run.get("output_revision_id"));
    assertFalse(
        com.marketing.workflow.AutopostService.ruleApprovalValid(
            db,
            new com.marketing.identity.ActorPolicy(db),
            r,
            db.one("SELECT * FROM brand WHERE id=?", brand),
            "autopost:" + c.get("id") + ":" + c.get("version")));
  }

  @Test
  void autopostSettingsChangedDuringAiGenerationDoNotCreateOrScheduleDraft() throws Exception {
    autopostAi("Useful text");
    autopostSave(autopostSettings("AUTO", true));
    assertNotNull(autopostDaily());
    var response = chatgptTransport.respond("fixture-access", Map.of());
    when(chatgptTransport.respond(anyString(), anyMap()))
        .thenAnswer(
            i -> {
              db.jdbc.update(
                  "UPDATE autopost_config SET version=version+1 WHERE brand_id=?", brand);
              return response;
            });
    assertThrows(Problem.class, () -> workflow.process(jobs.claim()));
    assertTrue(db.list("SELECT id FROM content_revision").isEmpty());
    assertTrue(db.list("SELECT id FROM publication").isEmpty());
  }

  @Test
  void autopostImageUsesSeparateReserveAndImmutableGeneratedPng() throws Exception {
    autopostAi("Useful text");
    var settings = autopostSettings("AUTO", true);
    settings.put("image_mode", "OPENAI");
    settings.put("image_api_key", "fixture-image-key");
    autopostSave(settings);
    var png = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(1024, 1024, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        png);
    when(imageGenerator.generate(anyMap(), anyMap(), any()))
        .thenReturn(
            com.marketing.media.AiImageGenerator.validate(
                png.toByteArray(),
                Map.of("provider", "OPENAI_IMAGES", "usage_status", "ESTIMATED_RESERVED")));
    var run = autopostGenerated();
    var ledger = db.one("SELECT * FROM budget_ledger WHERE run_id=?", run.get("id"));
    assertEquals("SETTLED", ledger.get("status"));
    assertEquals(100000L, ((Number) ledger.get("charged_micros")).longValue());
    var asset =
        db.one("SELECT * FROM asset_version WHERE revision_id=?", run.get("output_revision_id"));
    assertEquals(1024, asset.get("width"));
    assertEquals("AI_GENERATED", asset.get("rights"));
    assertArrayEquals(png.toByteArray(), files.get(asset.get("storage_key")));
    var r = db.one("SELECT * FROM content_revision WHERE id=?", run.get("output_revision_id"));
    assertTrue(db.json(r.get("creative_brief")).contains("OPENAI_IMAGES"));
    verify(imageGenerator, times(1)).generate(anyMap(), anyMap(), any());
  }

  @Test
  void autopostWebImageUsesPlanQuotaAndWaitsInReviewMode() throws Exception {
    autopostAi("Useful text");
    var settings = autopostSettings("REVIEW", true);
    settings.put("image_mode", "CHATGPT_WEB");
    settings.put("image_model", "chatgpt-web-image");
    autopostSave(settings);
    var png =
        new com.marketing.media.TemplateRenderer().render("Fixture", "Image fixture", "#345678");
    when(imageGenerator.generate(anyMap(), anyMap(), any()))
        .thenReturn(
            com.marketing.media.ChatGptWebImages.normalize(
                png, Map.of("provider", "CHATGPT_WEB", "billing", "CHATGPT_PLAN")));
    var run = autopostGenerated();
    assertEquals("WAITING_REVIEW", run.get("status"));
    assertTrue(db.list("SELECT id FROM publication").isEmpty());
    assertTrue(db.list("SELECT run_id FROM budget_ledger WHERE run_id=?", run.get("id")).isEmpty());
    var revision =
        db.one("SELECT * FROM content_revision WHERE id=?", run.get("output_revision_id"));
    assertTrue(db.json(revision.get("creative_brief")).contains("CHATGPT_PLAN"));
    verify(imageGenerator, times(1)).generate(anyMap(), anyMap(), any());
  }

  @Test
  void autopostWebImageAllowsAutomaticSettingsButRejectsUnavailableBridge() throws Exception {
    autopostAi("Useful text");
    var settings = autopostSettings("AUTO", false);
    settings.put("image_mode", "CHATGPT_WEB");
    settings.put("image_model", "chatgpt-web-image");
    String path = "/v1/brands/" + brand + "/autopost";
    mvc.perform(owner(put(path)).contentType("application/json").content(db.json(settings)))
        .andExpect(status().isOk());
    var saved = autopost.config(workspace, brand);
    assertEquals("AUTO", saved.get("mode"));
    assertEquals(false, saved.get("enabled"));
    settings.put("expected_version", saved.get("version"));
    settings.put("mode", "REVIEW");
    settings.put("enabled", true);
    when(webImages.status()).thenReturn(Map.of("ready", false, "message", "Bridge offline"));
    mvc.perform(owner(put(path)).contentType("application/json").content(db.json(settings)))
        .andExpect(status().isConflict());
    assertEquals(saved.get("version"), autopost.config(workspace, brand).get("version"));
    verifyNoInteractions(imageGenerator);
  }

  @Test
  void autopostMissedSlotsNeverCatchUpAndSeparateReviewerBlocksAutopilot() throws Exception {
    autopostSave(autopostSettings("DEMO", true));
    var c = autopost.config(workspace, brand);
    var b = db.one("SELECT * FROM brand WHERE id=?", brand);
    var slot = com.marketing.workflow.AutopostService.nextSlot(c, b, java.time.Instant.now());
    assertNull(autopost.daily(Db.id(c.get("id")), slot.plusSeconds(61)));
    db.jdbc.update("UPDATE brand SET require_separate_reviewer=true WHERE id=?", brand);
    assertNull(autopost.daily(Db.id(c.get("id")), slot.minusSeconds(300)));
    assertTrue(db.list("SELECT id FROM workflow_run").isEmpty());
  }

  @Test
  void autopostConcurrentDailyWorkersOnlyCreateOneRun() throws Exception {
    autopostSave(autopostSettings("DEMO", true));
    var c = autopost.config(workspace, brand);
    var b = db.one("SELECT * FROM brand WHERE id=?", brand);
    var at =
        com.marketing.workflow.AutopostService.nextSlot(c, b, java.time.Instant.now())
            .minusSeconds(300);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                start.await();
                return autopost.daily(Db.id(c.get("id")), at);
              });
      var d =
          pool.submit(
              () -> {
                start.await();
                return autopost.daily(Db.id(c.get("id")), at);
              });
      start.countDown();
      var results = Arrays.asList(a.get(10, TimeUnit.SECONDS), d.get(10, TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter(Objects::nonNull).count());
    }
    assertEquals(
        1,
        db.jdbc.queryForObject(
            "SELECT count(*) FROM workflow_run WHERE autopost_config_id=?",
            Integer.class,
            c.get("id")));
  }

  @Test
  void autopostLiveServerGateCannotBeBypassedByConsent() throws Exception {
    autopostAi("Useful text");
    var env = (org.springframework.core.env.ConfigurableEnvironment) applicationEnvironment;
    env.getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "autopost-live-off", Map.of("app.live-publishing", false)));
    try {
      mvc.perform(
              owner(put("/v1/brands/" + brand + "/autopost"))
                  .contentType("application/json")
                  .content(db.json(autopostSettings("AUTO", true))))
          .andExpect(status().isConflict());
      assertNull(autopost.config(workspace, brand));
    } finally {
      env.getPropertySources().remove("autopost-live-off");
    }
  }

  @Test
  void autopostInsufficientImageBudgetCannotEnableOrStartPaidWork() throws Exception {
    autopostAi("Useful text");
    db.jdbc.update("UPDATE brand SET monthly_budget_micros=10000 WHERE id=?", brand);
    var settings = autopostSettings("AUTO", true);
    settings.put("image_mode", "OPENAI");
    settings.put("image_api_key", "fixture-key");
    mvc.perform(
            owner(put("/v1/brands/" + brand + "/autopost"))
                .contentType("application/json")
                .content(db.json(settings)))
        .andExpect(status().isConflict());
    verifyNoInteractions(imageGenerator);
    assertTrue(db.list("SELECT id FROM workflow_run").isEmpty());
  }
}

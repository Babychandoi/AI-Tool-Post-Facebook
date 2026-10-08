package com.marketing.ai;

import com.marketing.identity.Scope;
import com.marketing.publishing.TokenVault;
import com.marketing.shared.*;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatGptConnections {
  private final Db db;
  private final Environment env;
  private final TokenVault vault;
  private final ChatGptTransport transport;
  private final TransactionTemplate tx;
  private static final String ATTEMPT = "chatgpt.oauth.attempt";
  private static final String SCOPES =
      "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
  private static final Set<String> TERMINAL_REFRESH =
      Set.of(
          "invalid_grant",
          "invalid_refresh_token",
          "token_expired",
          "refresh_token_expired",
          "refresh_token_invalidated",
          "refresh_token_reused");

  public ChatGptConnections(
      Db db,
      Environment env,
      TokenVault vault,
      ChatGptTransport transport,
      PlatformTransactionManager manager) {
    this.db = db;
    this.env = env;
    this.vault = vault;
    this.transport = transport;
    this.tx = new TransactionTemplate(manager);
  }

  public record Attempt(
      UUID workspace,
      String owner,
      String state,
      String nonce,
      String verifier,
      String redirect,
      String clientId,
      String subject,
      UUID connection,
      Instant expires) {}

  public record Access(String token, UUID connection, String model) {}

  private record Renewal(Map<String, Object> row, String error) {}

  public boolean enabled() {
    return env.getProperty("app.chatgpt-enabled", Boolean.class, false);
  }

  private void requireEnabled() {
    if (!enabled()) throw Problem.conflict("Kết nối ChatGPT chưa được bật cho cài đặt này.");
    try {
      vault.encrypt("configuration-check");
    } catch (Exception e) {
      throw Problem.conflict(
          "Cần cấu hình khóa mã hóa TOKEN_ENCRYPTION_KEY ở máy chủ trước khi kết nối ChatGPT.");
    }
  }

  public String redirectUri() {
    String value =
        env.getProperty("app.chatgpt-redirect-uri", "http://127.0.0.1:5173/v1/ai/chatgpt/callback");
    URI uri = URI.create(value);
    if (!"http".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || !"/v1/ai/chatgpt/callback".equals(uri.getPath())
        || uri.getRawQuery() != null
        || uri.getUserInfo() != null
        || uri.getFragment() != null)
      throw Problem.conflict("Kết nối ChatGPT local cần callback HTTP trên 127.0.0.1.");
    return value;
  }

  public Object status(Scope.Member m) {
    var rows =
        db.list(
            "SELECT id,account_email,client_id,status,active,model,expires_at,last_error FROM"
                + " chatgpt_connection WHERE workspace_id=? AND owner_email=? ORDER BY created_at",
            m.workspace(),
            m.email());
    var result = new LinkedHashMap<String, Object>();
    result.put("enabled", enabled());
    result.put("accounts", rows);
    result.put(
        "active_id",
        rows.stream()
            .filter(r -> Boolean.TRUE.equals(r.get("active")))
            .map(r -> r.get("id"))
            .findFirst()
            .orElse(null));
    result.put("api_configured", !env.getProperty("app.openai-key", "").isBlank());
    return result;
  }

  public String start(Scope.Member m, HttpSession session, UUID connectionId, boolean consent) {
    requireEnabled();
    m.require("MARKETER");
    Map<String, Object> row =
        connectionId == null ? null : owned(m.workspace(), m.email(), connectionId, false);
    String state = random(), nonce = random(), verifier = random(), redirect = redirectUri();
    String clientId = row == null ? "dynamic_agent_client" : row.get("client_id").toString();
    var attempt =
        new Attempt(
            m.workspace(),
            m.email(),
            state,
            nonce,
            verifier,
            redirect,
            clientId,
            row == null ? null : row.get("subject").toString(),
            connectionId,
            Instant.now().plusSeconds(600));
    session.setAttribute(ATTEMPT, attempt);
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("client_id", clientId);
    parameters.put(
        "ext_agent_host_id",
        db.one("SELECT host_id FROM chatgpt_host WHERE singleton=true").get("host_id").toString());
    if (row == null) parameters.put("agent_name_hint", "AI Marketing");
    else {
      parameters.put("login_hint", row.get("account_email").toString());
      if (row.get("credentials_cipher") != null) {
        var old = db.map(vault.decrypt(row.get("credentials_cipher").toString()));
        if (old.get("id_token") instanceof String hint && !hint.isBlank())
          parameters.put("id_token_hint", hint);
      }
    }
    parameters.put("response_type", "code");
    parameters.put("redirect_uri", redirect);
    parameters.put("scope", SCOPES);
    parameters.put("resource", ChatGptTransport.RESOURCE);
    parameters.put("state", state);
    parameters.put("nonce", nonce);
    parameters.put("code_challenge_method", "S256");
    parameters.put("code_challenge", challenge(verifier));
    if (consent) parameters.put("prompt", "consent");
    return ChatGptTransport.ISSUER + "/api/accounts/authorize?" + ChatGptTransport.form(parameters);
  }

  public String callback(
      Scope.Member m,
      HttpSession session,
      String state,
      String code,
      String issuedClient,
      String error) {
    requireEnabled();
    m.require("MARKETER");
    Attempt a;
    synchronized (session) {
      Object value = session.getAttribute(ATTEMPT);
      if (!(value instanceof Attempt attempt)
          || !ChatGptTransport.equal(attempt.state(), state)
          || !attempt.workspace().equals(m.workspace())
          || !attempt.owner().equals(m.email())
          || !attempt.expires().isAfter(Instant.now()))
        throw Problem.conflict("Phiên kết nối ChatGPT không hợp lệ hoặc đã hết hạn.");
      a = attempt;
      session.removeAttribute(ATTEMPT);
    }
    if (error != null) return "declined";
    if (code == null || code.isBlank() || code.length() > 8192)
      throw Problem.bad("Thiếu mã đăng nhập ChatGPT.");
    String clientId = "dynamic_agent_client".equals(a.clientId()) ? issuedClient : a.clientId();
    if (clientId == null
        || !clientId.matches("[A-Za-z0-9_-]{8,200}")
        || "dynamic_agent_client".equals(clientId)
        || (!"dynamic_agent_client".equals(a.clientId())
            && issuedClient != null
            && !clientId.equals(issuedClient)))
      throw Problem.conflict("OpenAI chưa cấp đúng client ID cho kết nối này.");
    Map<String, Object> tokens;
    try {
      tokens =
          transport.token(
              Map.of(
                  "grant_type",
                  "authorization_code",
                  "client_id",
                  clientId,
                  "code",
                  code,
                  "code_verifier",
                  a.verifier(),
                  "redirect_uri",
                  a.redirect(),
                  "resource",
                  ChatGptTransport.RESOURCE));
    } catch (ChatGptTransport.ProviderFailure e) {
      throw providerMessage(e);
    }
    var identity = transport.identity(required(tokens, "id_token"), clientId, a.nonce());
    if (a.subject() != null && !a.subject().equals(identity.subject()))
      throw Problem.conflict("Danh tính không khớp tài khoản ChatGPT đã chọn.");
    required(tokens, "access_token");
    if (!"Bearer".equalsIgnoreCase(Objects.toString(tokens.get("token_type"), "")))
      throw Problem.conflict("OpenAI trả về loại token chưa hỗ trợ.");
    var granted = scopes(tokens.get("scope"));
    String status =
        granted.contains("chatgpt.tokens.use.direct") && granted.contains("resource.invoke")
            ? "CONNECTED"
            : "PLAN_DISABLED";
    Instant expires = expiry(tokens), earliest = earliest(tokens, expires);
    String cipher = vault.encrypt(db.json(tokens));
    tx.executeWithoutResult(
        t -> {
          // One local owner has one active registration. Other registrations stay separate and
          // saved.
          lockOwner(m.workspace(), m.email());
          db.jdbc.update(
              "UPDATE chatgpt_connection SET active=false WHERE workspace_id=? AND owner_email=?",
              m.workspace(),
              m.email());
          UUID id = a.connection() == null ? UUID.randomUUID() : a.connection();
          db.jdbc.update(
              "INSERT INTO"
                  + " chatgpt_connection(id,workspace_id,owner_email,subject,client_id,account_email,credentials_cipher,scopes,expires_at,earliest_refresh_at,status,active)"
                  + " VALUES(?,?,?,?,?,?,?,?::jsonb,?::timestamptz,?::timestamptz,?,true) ON"
                  + " CONFLICT(workspace_id,owner_email,client_id,subject) DO UPDATE SET"
                  + " account_email=EXCLUDED.account_email,credentials_cipher=EXCLUDED.credentials_cipher,scopes=EXCLUDED.scopes,expires_at=EXCLUDED.expires_at,earliest_refresh_at=EXCLUDED.earliest_refresh_at,status=EXCLUDED.status,active=true,last_error=NULL,updated_at=now()",
              id,
              m.workspace(),
              m.email(),
              identity.subject(),
              clientId,
              identity.email(),
              cipher,
              db.json(granted),
              expires.toString(),
              earliest.toString(),
              status);
          db.audit(m.workspace(), null, m.email(), "CHATGPT_CONNECTED", id);
        });
    return "CONNECTED".equals(status) ? "connected" : "plan_disabled";
  }

  public Map<String, Object> active(UUID workspace, String owner) {
    var rows =
        db.list(
            "SELECT * FROM chatgpt_connection WHERE workspace_id=? AND owner_email=? AND"
                + " active=true",
            workspace,
            owner);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Map<String, Object> owned(UUID workspace, String owner, UUID id, boolean lock) {
    return db.one(
        "SELECT * FROM chatgpt_connection WHERE id=? AND workspace_id=? AND owner_email=?"
            + (lock ? " FOR UPDATE" : ""),
        id,
        workspace,
        owner);
  }

  public void activate(Scope.Member m, UUID id) {
    requireEnabled();
    m.require("MARKETER");
    tx.executeWithoutResult(
        t -> {
          lockOwner(m.workspace(), m.email());
          owned(m.workspace(), m.email(), id, true);
          db.jdbc.update(
              "UPDATE chatgpt_connection SET active=false WHERE workspace_id=? AND owner_email=?",
              m.workspace(),
              m.email());
          db.jdbc.update(
              "UPDATE chatgpt_connection SET active=true,updated_at=now() WHERE id=?", id);
        });
  }

  public List<Map<String, Object>> models(Scope.Member m, UUID id) {
    m.require("MARKETER");
    var access = access(m.workspace(), m.email(), id, false);
    try {
      return transport.models(access.token());
    } catch (ChatGptTransport.ProviderFailure e) {
      recordFailure(m.workspace(), m.email(), id, e);
      throw providerMessage(e);
    }
  }

  public void selectModel(Scope.Member m, UUID id, String model) {
    if (models(m, id).stream().noneMatch(v -> model.equals(v.get("slug"))))
      throw Problem.bad("Model không có trong tài khoản ChatGPT này.");
    db.jdbc.update(
        "UPDATE chatgpt_connection SET model=?,updated_at=now() WHERE id=? AND workspace_id=? AND"
            + " owner_email=?",
        model,
        id,
        m.workspace(),
        m.email());
  }

  public Access access(UUID workspace, String owner, UUID id, boolean activeRequired) {
    requireEnabled();
    Renewal renewed =
        tx.execute(
            t -> {
              var row = owned(workspace, owner, id, true);
              if (activeRequired && !Boolean.TRUE.equals(row.get("active")))
                throw Problem.conflict("Tài khoản ChatGPT của tác vụ đã bị đổi hoặc ngắt kết nối.");
              if (!"CONNECTED".equals(row.get("status")) || row.get("credentials_cipher") == null)
                throw Problem.conflict(
                    "Cần kết nối ChatGPT và cho phép dùng hạn mức trước khi tạo nội dung.");
              var tokens = db.map(vault.decrypt(row.get("credentials_cipher").toString()));
              if (Instant.parse(row.get("expires_at").toString())
                  .isAfter(Instant.now().plusSeconds(30))) return new Renewal(tokens, null);
              if (row.get("earliest_refresh_at") != null
                  && Instant.parse(row.get("earliest_refresh_at").toString())
                      .isAfter(Instant.now()))
                throw Problem.conflict(
                    "Chưa đến thời điểm OpenAI cho phép gia hạn token. Thử lại sau.");
              if (!(tokens.get("refresh_token") instanceof String refresh) || refresh.isBlank()) {
                invalidate(id);
                return new Renewal(null, "Phiên ChatGPT đã hết hạn. Hãy kết nối lại.");
              }
              try {
                var next =
                    new LinkedHashMap<String, Object>(
                        transport.token(
                            Map.of(
                                "grant_type",
                                "refresh_token",
                                "client_id",
                                row.get("client_id").toString(),
                                "refresh_token",
                                refresh,
                                "resource",
                                ChatGptTransport.RESOURCE)));
                required(next, "access_token");
                required(next, "refresh_token");
                if (!"Bearer".equalsIgnoreCase(Objects.toString(next.get("token_type"), "")))
                  throw Problem.conflict("Token gia hạn không hợp lệ.");
                if (!next.containsKey("id_token") && tokens.containsKey("id_token"))
                  next.put("id_token", tokens.get("id_token"));
                var granted =
                    next.containsKey("scope")
                        ? scopes(next.get("scope"))
                        : (List<?>) row.get("scopes");
                if (!next.containsKey("scope"))
                  next.put(
                      "scope", String.join(" ", granted.stream().map(Object::toString).toList()));
                var exp = expiry(next);
                String status =
                    granted.contains("chatgpt.tokens.use.direct")
                            && granted.contains("resource.invoke")
                        ? "CONNECTED"
                        : "PLAN_DISABLED";
                db.jdbc.update(
                    "UPDATE chatgpt_connection SET"
                        + " credentials_cipher=?,scopes=?::jsonb,expires_at=?::timestamptz,earliest_refresh_at=?::timestamptz,status=?,last_error=NULL,updated_at=now()"
                        + " WHERE id=?",
                    vault.encrypt(db.json(next)),
                    db.json(granted),
                    exp.toString(),
                    earliest(next, exp).toString(),
                    status,
                    id);
                return new Renewal(
                    next,
                    "CONNECTED".equals(status)
                        ? null
                        : "OpenAI chưa cho phép dùng hạn mức ChatGPT.");
              } catch (ChatGptTransport.ProviderFailure e) {
                if (TERMINAL_REFRESH.contains(e.code)) {
                  invalidate(id);
                  return new Renewal(null, "Phiên ChatGPT không còn hợp lệ. Hãy kết nối lại.");
                }
                throw providerMessage(e);
              }
            });
    if (renewed.error() != null) throw Problem.conflict(renewed.error());
    return new Access(
        required(renewed.row(), "access_token"),
        id,
        Objects.toString(owned(workspace, owner, id, false).get("model"), ""));
  }

  public boolean disconnect(Scope.Member m, UUID id) {
    m.require("MARKETER");
    return Boolean.TRUE.equals(
        tx.execute(
            t -> {
              lockOwner(m.workspace(), m.email());
              var row = owned(m.workspace(), m.email(), id, true);
              boolean confirmed = true;
              if (row.get("credentials_cipher") != null) {
                var tokens = db.map(vault.decrypt(row.get("credentials_cipher").toString()));
                if (tokens.get("refresh_token") instanceof String refresh)
                  confirmed = transport.revoke(row.get("client_id").toString(), refresh);
              }
              db.jdbc.update(
                  "UPDATE chatgpt_connection SET"
                      + " credentials_cipher=NULL,status='DISCONNECTED',active=false,last_error=NULL,updated_at=now()"
                      + " WHERE id=?",
                  id);
              db.audit(m.workspace(), null, m.email(), "CHATGPT_DISCONNECTED", id);
              return confirmed;
            }));
  }

  public void recordFailure(
      UUID workspace, String owner, UUID id, ChatGptTransport.ProviderFailure e) {
    String message = providerMessage(e).getMessage();
    db.jdbc.update(
        "UPDATE chatgpt_connection SET last_error=?,updated_at=now() WHERE id=? AND workspace_id=?"
            + " AND owner_email=?",
        message,
        id,
        workspace,
        owner);
  }

  public static Problem providerMessage(ChatGptTransport.ProviderFailure e) {
    String message =
        switch (e.code) {
          case "subscription_sharing_usage_limit_exceeded" ->
              "Đã đạt hạn mức ChatGPT của ứng dụng. Mở Quản lý hạn mức trong ChatGPT; tác vụ đã"
                  + " dừng.";
          case "subscription_sharing_user_not_eligible" ->
              "Tài khoản hoặc workspace này chưa đủ điều kiện dùng hạn mức ChatGPT.";
          case "subscription_sharing_unsupported_capability" ->
              "Model hoặc tính năng chưa được ChatGPT plan hỗ trợ.";
          case "invalid_grant" -> "Mã hoặc phiên ChatGPT đã hết hạn. Hãy kết nối lại.";
          default ->
              e.status == 429
                  ? "OpenAI đang giới hạn lượt dùng. Xem hạn mức ChatGPT và thử lại sau."
                  : e.status == 401
                      ? "OpenAI không chấp nhận phiên ChatGPT. Hãy kết nối lại."
                      : e.status == 403
                          ? "OpenAI chưa cho phép tài khoản, workspace hoặc khu vực dùng kết nối"
                                + " này."
                          : "OpenAI tạm thời không xử lý được yêu cầu ChatGPT. Thử lại sau.";
        };
    return Problem.conflict(message);
  }

  private void invalidate(UUID id) {
    db.jdbc.update(
        "UPDATE chatgpt_connection SET"
            + " credentials_cipher=NULL,status='REAUTH_REQUIRED',updated_at=now() WHERE id=?",
        id);
  }

  private void lockOwner(UUID workspace, String owner) {
    db.jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", workspace + ":chatgpt:" + owner);
  }

  static String required(Map<String, Object> v, String key) {
    if (!(v.get(key) instanceof String s) || s.isBlank() || s.length() > 100000)
      throw Problem.conflict("Phản hồi xác thực ChatGPT chưa đầy đủ.");
    return s;
  }

  static List<String> scopes(Object value) {
    return value instanceof String s && !s.isBlank()
        ? Arrays.stream(s.trim().split("\\s+")).distinct().toList()
        : List.of();
  }

  static Instant expiry(Map<String, Object> tokens) {
    if (!(tokens.get("expires_in") instanceof Number n)
        || n.longValue() <= 0
        || n.longValue() > 86400)
      throw Problem.conflict("Token ChatGPT thiếu thời gian hết hạn hợp lệ.");
    return Instant.now().plusSeconds(n.longValue());
  }

  static Instant earliest(Map<String, Object> tokens, Instant expires) {
    Object value = tokens.get("earliest_refresh_at");
    if (value instanceof Number n) return Instant.ofEpochSecond(n.longValue());
    if (value instanceof String s) {
      try {
        return Instant.parse(s);
      } catch (Exception ignored) {
      }
    }
    return expires.minusSeconds(60);
  }

  static String random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String challenge(String verifier) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

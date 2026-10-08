package com.marketing.webhook;

import com.marketing.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/webhooks/meta")
public class MetaWebhook {
  private final Db db;
  private final Environment env;

  public MetaWebhook(Db db, Environment env) {
    this.db = db;
    this.env = env;
  }

  @GetMapping
  ResponseEntity<?> verify(
      @RequestParam(name = "hub.mode") String mode,
      @RequestParam(name = "hub.verify_token") String token,
      @RequestParam(name = "hub.challenge") String challenge) {
    String expected = env.getProperty("app.meta-verify-token", "");
    if (expected.isBlank()
        || !"subscribe".equals(mode)
        || !MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)))
      return ResponseEntity.status(403).build();
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
  }

  @PostMapping
  @Transactional
  Object receive(
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      @RequestBody byte[] body) {
    String secret = env.getProperty("app.meta-app-secret", "");
    if (body.length > 1048576)
      throw new Problem(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "Webhook tối đa 1 MB.");
    if (secret.isBlank() || signature == null || !valid(secret, signature, body))
      throw new Problem(HttpStatus.FORBIDDEN, "INVALID_SIGNATURE", "Chữ ký webhook không hợp lệ.");
    try {
      var root = db.json.readTree(body);
      if (!"page".equals(root.path("object").asText()))
        throw Problem.bad("Webhook object không được hỗ trợ.");
      for (var entry : root.path("entry")) {
        String remote = entry.path("id").asText();
        var accounts =
            db.list(
                "SELECT id,workspace_id,brand_id FROM social_account WHERE provider='META' AND"
                    + " remote_id=?",
                remote);
        for (var account : accounts) {
          String fingerprint = Db.hash(account.get("id") + ":" + entry.toString());
          UUID id = UUID.randomUUID();
          int inserted =
              db.jdbc.update(
                  "INSERT INTO inbox_event(id,workspace_id,brand_id,account_id,fingerprint,payload)"
                      + " VALUES(?,?,?,?,?,?::jsonb) ON CONFLICT(fingerprint) DO NOTHING",
                  id,
                  account.get("workspace_id"),
                  account.get("brand_id"),
                  account.get("id"),
                  fingerprint,
                  entry.toString());
          if (inserted == 1)
            db.jdbc.update(
                "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id) VALUES(?,?,'WEBHOOK',?)",
                account.get("workspace_id"),
                account.get("brand_id"),
                id);
        }
      }
      return Map.of("ok", true);
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw Problem.bad("Webhook không đúng schema.");
    }
  }

  public static boolean valid(String secret, String signature, byte[] body) {
    try {
      if (!signature.startsWith("sha256=")) return false;
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return MessageDigest.isEqual(
          m.doFinal(body), HexFormat.of().parseHex(signature.substring(7)));
    } catch (Exception e) {
      return false;
    }
  }
}

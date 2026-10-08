package com.marketing.workflow;

import com.marketing.identity.Scope;
import com.marketing.publishing.TokenVault;
import com.marketing.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalTime;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/brands/{brand}/autopost")
public class AutopostController {
  private final Db db;
  private final Scope scope;
  private final AutopostService autopost;
  private final TokenVault vault;

  public AutopostController(Db db, Scope scope, AutopostService autopost, TokenVault vault) {
    this.db = db;
    this.scope = scope;
    this.autopost = autopost;
    this.vault = vault;
  }

  public record Settings(
      UUID account_id,
      @NotBlank @Size(max = 500) String objective,
      @NotNull @Size(min = 1, max = 20) List<@NotBlank @Size(max = 250) String> pillars,
      @NotBlank @Pattern(regexp = "REVIEW|DEMO|AUTO") String mode,
      boolean enabled,
      @NotBlank String daily_post_time,
      @NotBlank @Pattern(regexp = "TEMPLATE|OPENAI|GOOGLE|CHATGPT_WEB") String image_mode,
      @NotNull @Size(max = 2000) String image_prompt,
      @Size(max = 5000) String image_api_key,
      boolean clear_image_key,
      @NotBlank
          @Pattern(
              regexp =
                  "gpt-image-2.5-sunburst|gpt-image-2.5-flare|gpt-image-2|gpt-image-1.5|gpt-image-1|gemini-3.1-flash-image|chatgpt-web-image")
          String image_model,
      @Min(10000) @Max(10000000) long image_reserve_micros,
      boolean live_consent,
      int expected_version) {}

  @GetMapping
  Object status(@PathVariable UUID brand) {
    var m = scope.current();
    scope.brand(m, brand);
    return autopost.status(m.workspace(), brand, m.email());
  }

  @PutMapping
  @Transactional
  Object save(@PathVariable UUID brand, @Valid @RequestBody Settings s) {
    var m = scope.current();
    m.require();
    scope.brand(m, brand);
    var b =
        db.one(
            "SELECT * FROM brand WHERE id=? AND workspace_id=? FOR UPDATE", brand, m.workspace());
    var old = autopost.config(m.workspace(), brand);
    if ((old == null ? 0 : ((Number) old.get("version")).intValue()) != s.expected_version())
      throw Problem.conflict("Thiết lập đã thay đổi. Tải lại trước khi lưu.");
    String time = LocalTime.parse(s.daily_post_time()).withSecond(0).withNano(0).toString();
    if (s.account_id() != null)
      db.one(
          "SELECT id FROM social_account WHERE id=? AND workspace_id=? AND brand_id=?",
          s.account_id(),
          m.workspace(),
          brand);
    UUID campaign = old == null ? UUID.randomUUID() : Db.id(old.get("campaign_id"));
    String cipher = old == null ? null : (String) old.get("image_key_ciphertext");
    String keyProvider = "GOOGLE".equals(s.image_mode()) ? "GOOGLE" : "OPENAI";
    if (old != null && !keyProvider.equals(old.get("image_key_provider"))) cipher = null;
    if (s.clear_image_key()) cipher = null;
    if (s.image_api_key() != null && !s.image_api_key().isBlank())
      cipher = vault.encrypt(s.image_api_key().trim());
    var candidate = new LinkedHashMap<String, Object>();
    candidate.put("workspace_id", m.workspace());
    candidate.put("brand_id", brand);
    candidate.put("account_id", s.account_id());
    candidate.put("mode", s.mode());
    candidate.put("created_by", m.email());
    candidate.put("live_consent", s.live_consent());
    candidate.put("image_mode", s.image_mode());
    candidate.put("image_key_ciphertext", cipher);
    candidate.put("image_key_provider", keyProvider);
    candidate.put("image_reserve_micros", s.image_reserve_micros());
    if (s.enabled())
      for (var check : autopost.checks(candidate, b))
        if (!Boolean.TRUE.equals(check.get("ok")))
          throw Problem.conflict(check.get("message").toString());
    if (old == null)
      db.jdbc.update(
          "INSERT INTO campaign(id,workspace_id,brand_id,name,objective,pillars,created_by)"
              + " VALUES(?,?,?,'Đăng tự động hằng ngày',?,?::jsonb,?)",
          campaign,
          m.workspace(),
          brand,
          s.objective(),
          db.json(s.pillars()),
          m.email());
    else {
      autopost.cancelPending(Db.id(old.get("id")));
      db.jdbc.update(
          "UPDATE campaign SET objective=?,pillars=?::jsonb,created_by=?,version=version+1 WHERE"
              + " id=?",
          s.objective(),
          db.json(s.pillars()),
          m.email(),
          campaign);
    }
    UUID id = old == null ? UUID.randomUUID() : Db.id(old.get("id"));
    db.jdbc.update(
        "INSERT INTO"
            + " autopost_config(id,workspace_id,brand_id,campaign_id,account_id,mode,enabled,daily_post_time,image_mode,image_prompt,image_key_ciphertext,image_key_provider,image_model,image_reserve_micros,live_consent,created_by)"
            + " VALUES(?,?,?,?,?,?,?,?::time,?,?,?,?,?,?,?,?) ON CONFLICT(brand_id) DO UPDATE SET"
            + " account_id=EXCLUDED.account_id,mode=EXCLUDED.mode,enabled=EXCLUDED.enabled,daily_post_time=EXCLUDED.daily_post_time,image_mode=EXCLUDED.image_mode,image_prompt=EXCLUDED.image_prompt,image_key_ciphertext=EXCLUDED.image_key_ciphertext,image_key_provider=EXCLUDED.image_key_provider,image_model=EXCLUDED.image_model,image_reserve_micros=EXCLUDED.image_reserve_micros,live_consent=EXCLUDED.live_consent,created_by=EXCLUDED.created_by,version=autopost_config.version+1,activated_at=now(),updated_at=now()",
        id,
        m.workspace(),
        brand,
        campaign,
        s.account_id(),
        s.mode(),
        s.enabled(),
        time,
        s.image_mode(),
        s.image_prompt(),
        cipher,
        keyProvider,
        s.image_model(),
        s.image_reserve_micros(),
        s.live_consent(),
        m.email());
    db.audit(
        m.workspace(), brand, m.email(), s.enabled() ? "AUTOPOST_ENABLED" : "AUTOPOST_SAVED", id);
    return autopost.status(m.workspace(), brand, m.email());
  }

  public record Stop(int expected_version) {}

  @PostMapping("/stop")
  @Transactional
  Object stop(@PathVariable UUID brand, @RequestBody Stop s) {
    var m = scope.current();
    m.require();
    scope.brand(m, brand);
    db.one("SELECT id FROM brand WHERE id=? AND workspace_id=? FOR UPDATE", brand, m.workspace());
    var c = autopost.config(m.workspace(), brand);
    if (c == null) throw Problem.conflict("Chưa có lịch tự động.");
    if (db.jdbc.update(
            "UPDATE autopost_config SET"
                + " enabled=false,version=version+1,live_consent=false,updated_at=now() WHERE id=?"
                + " AND version=?",
            c.get("id"),
            s.expected_version())
        != 1) throw Problem.conflict("Thiết lập đã thay đổi. Tải lại trước khi tắt.");
    autopost.cancelPending(Db.id(c.get("id")));
    db.audit(m.workspace(), brand, m.email(), "AUTOPOST_STOPPED", Db.id(c.get("id")));
    return autopost.status(m.workspace(), brand, m.email());
  }

  @PostMapping("/preview")
  Object preview(@PathVariable UUID brand) {
    var m = scope.current();
    m.require();
    scope.brand(m, brand);
    UUID run = autopost.preview(m.workspace(), brand, m.email());
    return ResponseEntity.accepted().body(Map.of("run_id", run));
  }
}

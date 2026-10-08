package com.marketing.workflow;

import com.marketing.ai.AiWriter;
import com.marketing.content.ContentService;
import com.marketing.identity.ActorPolicy;
import com.marketing.operations.Budget;
import com.marketing.publishing.PublishingController;
import com.marketing.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutopostService {
  private final Db db;
  private final AiWriter writer;
  private final Budget budget;
  private final ActorPolicy actors;
  private final Environment env;
  private final PublishingController gate;
  private final ContentService contents;
  private final com.marketing.media.ChatGptWebImages webImages;

  public AutopostService(
      Db db,
      AiWriter writer,
      Budget budget,
      ActorPolicy actors,
      Environment env,
      PublishingController gate,
      ContentService contents,
      com.marketing.media.ChatGptWebImages webImages) {
    this.db = db;
    this.writer = writer;
    this.budget = budget;
    this.actors = actors;
    this.env = env;
    this.gate = gate;
    this.contents = contents;
    this.webImages = webImages;
  }

  public Map<String, Object> config(UUID workspace, UUID brand) {
    var rows =
        db.list(
            "SELECT * FROM autopost_config WHERE workspace_id=? AND brand_id=?", workspace, brand);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Map<String, Object> status(UUID workspace, UUID brand, String owner) {
    var c = config(workspace, brand);
    var result = new LinkedHashMap<String, Object>();
    if (c != null) {
      var safe = new LinkedHashMap<>(c);
      safe.remove("image_key_ciphertext");
      safe.put("image_key_saved", c.get("image_key_ciphertext") != null);
      var campaign =
          db.one("SELECT objective,pillars FROM campaign WHERE id=?", c.get("campaign_id"));
      safe.putAll(campaign);
      result.put("config", safe);
    } else result.put("config", null);
    var b = db.one("SELECT * FROM brand WHERE id=? AND workspace_id=?", brand, workspace);
    result.put("checks", c == null ? List.of() : checks(c, b));
    result.put("live_available", env.getProperty("app.live-publishing", Boolean.class, false));
    result.put(
        "meta_available",
        !env.getProperty("app.meta-app-id", "").isBlank()
            && !env.getProperty("app.meta-app-secret", "").isBlank()
            && env.getProperty("app.meta-version", "").matches("v\\d+\\.\\d+"));
    result.put("image_api_available", !env.getProperty("app.openai-key", "").isBlank());
    result.put("google_image_api_available", !env.getProperty("app.google-ai-key", "").isBlank());
    result.put("chatgpt_web_images", webImages.status());
    result.put(
        "next_post_at",
        c != null && Boolean.TRUE.equals(c.get("enabled"))
            ? nextSlot(c, b, Instant.now()).toString()
            : null);
    result.put(
        "runs",
        db.list(
            "SELECT"
                + " r.id,r.status,r.error,r.topic,r.created_at,r.autopost_due_at,r.autopost_preview,r.output_revision_id,p.status"
                + " publication_status,p.remote_url,cr.hook,cr.body,cr.cta,cr.risk_flags,cr.status"
                + " revision_status FROM workflow_run r LEFT JOIN content_revision cr ON"
                + " cr.id=r.output_revision_id LEFT JOIN publication p ON"
                + " p.revision_id=r.output_revision_id AND"
                + " p.autopost_config_id=r.autopost_config_id WHERE r.workspace_id=? AND"
                + " r.brand_id=? AND r.autopost_config_id IS NOT NULL ORDER BY r.created_at DESC"
                + " LIMIT 15",
            workspace,
            brand));
    try {
      var p = writer.plan(workspace, c == null ? owner : c.get("created_by").toString());
      result.put("ai_provider", p.provider());
      result.put("ai_model", p.model());
    } catch (Problem p) {
      result.put("ai_provider", "MISSING");
      result.put("ai_error", p.getMessage());
    }
    return result;
  }

  public List<Map<String, Object>> checks(Map<String, Object> c, Map<String, Object> b) {
    UUID workspace = Db.id(c.get("workspace_id")), brand = Db.id(c.get("brand_id"));
    String mode = c.get("mode").toString(), owner = c.get("created_by").toString();
    var checks = new ArrayList<Map<String, Object>>();
    check(
        checks,
        "brand",
        Boolean.TRUE.equals(b.get("enabled")),
        "Thương hiệu sẵn sàng",
        "Thương hiệu đang tạm dừng.");
    check(
        checks,
        "permissions",
        actors.allowed(workspace, brand, owner, "MARKETER")
            && ("REVIEW".equals(mode) || actors.allowed(workspace, brand, owner, "PUBLISHER")),
        "Người thiết lập có quyền",
        "Người thiết lập không còn quyền tạo hoặc đăng bài.");
    String aiError = "Kết nối ChatGPT và chọn model để AI viết bài.";
    boolean ai = false;
    long reserve = 0;
    String aiReady = "AI viết bài đã sẵn sàng";
    try {
      var p = writer.plan(workspace, owner);
      ai = "DEMO".equals(mode) || !"DEMO".equals(p.provider());
      if ("DEMO".equals(p.provider())) aiReady = "Nội dung mẫu sẵn sàng cho chạy thử";
      reserve =
          p.chatgpt() || "DEMO".equals(p.provider())
              ? 0
              : env.getProperty("app.generation-reserve-micros", Long.class, 100000L);
    } catch (Problem p) {
      aiError = p.getMessage();
    }
    check(checks, "ai", ai, aiReady, aiError);
    var accounts =
        c.get("account_id") == null
            ? List.<Map<String, Object>>of()
            : db.list(
                "SELECT provider,health FROM social_account WHERE id=? AND brand_id=? AND"
                    + " workspace_id=?",
                c.get("account_id"),
                brand,
                workspace);
    boolean page = !accounts.isEmpty() && "CONNECTED".equals(accounts.getFirst().get("health"));
    if (page && "DEMO".equals(mode)) page = "DEMO".equals(accounts.getFirst().get("provider"));
    if (page && "AUTO".equals(mode)) page = "META".equals(accounts.getFirst().get("provider"));
    check(
        checks,
        "page",
        page,
        "Fanpage đã chọn",
        "DEMO".equals(mode)
            ? "Chọn Page thử nghiệm; chế độ này không đăng Facebook."
            : "Chọn Fanpage đã kết nối; tự đăng cần Fanpage thật.");
    boolean source =
        !db.list(
                "SELECT id FROM knowledge_document WHERE workspace_id=? AND brand_id=? AND"
                    + " status='APPROVED' AND effective_from<=now() AND (effective_until IS NULL OR"
                    + " effective_until>now()) LIMIT 1",
                workspace,
                brand)
            .isEmpty();
    check(
        checks,
        "knowledge",
        source,
        "Thông tin thương hiệu đã sẵn sàng",
        "Nhập và duyệt thông tin thương hiệu để AI có căn cứ viết bài.");
    boolean webImage = "CHATGPT_WEB".equals(c.get("image_mode"));
    var webStatus = webImage ? webImages.status() : Map.<String, Object>of();
    boolean image =
        webImage
            ? Boolean.TRUE.equals(webStatus.get("ready"))
            : "TEMPLATE".equals(c.get("image_mode"))
                || c.get("image_key_ciphertext") != null
                || ("GOOGLE".equals(c.get("image_mode"))
                    ? !env.getProperty("app.google-ai-key", "").isBlank()
                        || c.get("image_key_ciphertext") != null
                    : !env.getProperty("app.openai-key", "").isBlank());
    check(
        checks,
        "image",
        image,
        "Cách tạo ảnh đã sẵn sàng",
        webImage
            ? webStatus.get("message").toString()
            : "Ảnh AI cần Gemini API key Google hoặc OpenAI API key.");
    if (Set.of("OPENAI", "GOOGLE").contains(c.get("image_mode")))
      reserve += ((Number) c.getOrDefault("image_reserve_micros", 100000L)).longValue();
    var spent =
        db.list(
            "SELECT spent_micros+reserved_micros total FROM budget_month WHERE brand_id=? AND"
                + " month=date_trunc('month',now())::date",
            brand);
    long total = spent.isEmpty() ? 0 : ((Number) spent.getFirst().get("total")).longValue();
    check(
        checks,
        "budget",
        reserve == 0 || total + reserve <= ((Number) b.get("monthly_budget_micros")).longValue(),
        "Ngân sách đủ cho bài tiếp theo",
        "Ngân sách tháng không đủ cho bài hoặc ảnh tiếp theo. Điều chỉnh ngân sách thương hiệu.");
    if (!"REVIEW".equals(mode))
      check(
          checks,
          "review_policy",
          !Boolean.TRUE.equals(b.get("require_separate_reviewer")),
          "Chính sách cho phép tự động",
          "Thương hiệu yêu cầu người duyệt riêng. Chọn duyệt trước khi đăng.");
    if ("AUTO".equals(mode)
        || (!accounts.isEmpty() && "META".equals(accounts.getFirst().get("provider")))) {
      check(
          checks,
          "live",
          env.getProperty("app.live-publishing", Boolean.class, false),
          "Máy chủ cho phép đăng Facebook",
          "Máy chủ đang tắt đăng thật. Quản trị viên cần mở kết nối đăng Facebook.");
      check(
          checks,
          "consent",
          Boolean.TRUE.equals(c.get("live_consent")),
          "Bạn đã cho phép tự đăng",
          "Tích xác nhận cho phép hệ thống tự đăng lên Fanpage.");
    }
    return checks;
  }

  private static void check(
      List<Map<String, Object>> list, String key, boolean ok, String yes, String no) {
    list.add(Map.of("key", key, "ok", ok, "message", ok ? yes : no));
  }

  public static Instant nextSlot(Map<String, Object> c, Map<String, Object> b, Instant now) {
    ZoneId zone = ZoneId.of(b.get("timezone").toString());
    var local = now.atZone(zone);
    var slot =
        local
            .toLocalDate()
            .atTime(LocalTime.parse(c.get("daily_post_time").toString()))
            .atZone(zone)
            .toInstant();
    if (!slot.isAfter(now))
      slot =
          local
              .toLocalDate()
              .plusDays(1)
              .atTime(LocalTime.parse(c.get("daily_post_time").toString()))
              .atZone(zone)
              .toInstant();
    return slot;
  }

  @Transactional
  public UUID daily(UUID id, Instant now) {
    var initial = db.one("SELECT * FROM autopost_config WHERE id=?", id);
    var b = db.one("SELECT * FROM brand WHERE id=? FOR UPDATE", initial.get("brand_id"));
    var c = db.one("SELECT * FROM autopost_config WHERE id=? FOR UPDATE", id);
    if (!Boolean.TRUE.equals(c.get("enabled"))) return null;
    Instant slot = nextSlot(c, b, now);
    if (slot.isAfter(now.plusSeconds(1800))
        || !slot.isAfter(Instant.parse(c.get("activated_at").toString()))) return null;
    for (var check : checks(c, b)) if (!Boolean.TRUE.equals(check.get("ok"))) return null;
    String trigger =
        "autopost:" + id + ":" + slot.atZone(ZoneId.of(b.get("timezone").toString())).toLocalDate();
    if (!db.list(
            "SELECT id FROM workflow_run WHERE workspace_id=? AND brand_id=? AND trigger_key=?",
            c.get("workspace_id"),
            c.get("brand_id"),
            trigger)
        .isEmpty()) return null;
    return enqueue(c, b, slot, false, trigger);
  }

  @Transactional
  public UUID preview(UUID workspace, UUID brand, String actor) {
    var b =
        db.one("SELECT * FROM brand WHERE id=? AND workspace_id=? FOR UPDATE", brand, workspace);
    var c = config(workspace, brand);
    if (c == null) throw Problem.conflict("Lưu thiết lập trước khi tạo bài thử.");
    if (!Boolean.TRUE.equals(b.get("enabled")))
      throw Problem.conflict("Thương hiệu đang tạm dừng.");
    if (!c.get("created_by").equals(actor))
      throw Problem.conflict("Lưu thiết lập bằng tài khoản của bạn trước khi tạo bài thử.");
    if (!db.list(
            "SELECT id FROM workflow_run WHERE autopost_config_id=? AND status IN"
                + " ('QUEUED','RUNNING')",
            c.get("id"))
        .isEmpty())
      throw Problem.conflict("Một bài đang được tạo. Đợi hoàn tất trước khi thử lại.");
    return enqueue(c, b, Instant.now(), true, null);
  }

  private UUID enqueue(
      Map<String, Object> c, Map<String, Object> b, Instant slot, boolean preview, String trigger) {
    UUID workspace = Db.id(c.get("workspace_id")), brand = Db.id(c.get("brand_id"));
    var plan = writer.plan(workspace, c.get("created_by").toString());
    var campaign = db.one("SELECT * FROM campaign WHERE id=?", c.get("campaign_id"));
    List<?> pillars = (List<?>) campaign.get("pillars");
    long day = slot.atZone(ZoneId.of(b.get("timezone").toString())).toLocalDate().toEpochDay();
    String topic = pillars.get(Math.floorMod(day, pillars.size())).toString();
    long reserve =
        plan.chatgpt() || "DEMO".equals(plan.provider())
            ? 0
            : env.getProperty("app.generation-reserve-micros", Long.class, 100000L);
    if (Set.of("OPENAI", "GOOGLE").contains(c.get("image_mode")))
      reserve += ((Number) c.get("image_reserve_micros")).longValue();
    UUID run = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " workflow_run(id,workspace_id,brand_id,campaign_id,topic,brand_version,policy_version,created_by,trigger_key,autopost_config_id,autopost_version,autopost_due_at,autopost_preview)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        run,
        workspace,
        brand,
        c.get("campaign_id"),
        topic,
        b.get("version"),
        b.get("policy_version"),
        c.get("created_by"),
        trigger,
        c.get("id"),
        c.get("version"),
        java.sql.Timestamp.from(slot),
        preview);
    writer.pin(run, plan);
    if (reserve > 0) budget.reserve(workspace, brand, run, reserve);
    db.jdbc.update(
        "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id) VALUES(?,?,'GENERATE',?)",
        workspace,
        brand,
        run);
    db.audit(
        workspace,
        brand,
        c.get("created_by").toString(),
        preview ? "AUTOPOST_PREVIEW" : "AUTOPOST_DAILY_QUEUED",
        run);
    return run;
  }

  public Map<String, Object> forRun(Map<String, Object> run, boolean lock) {
    if (run.get("autopost_config_id") == null) return null;
    var c =
        db.one(
            "SELECT * FROM autopost_config WHERE id=? AND workspace_id=? AND brand_id=?"
                + (lock ? " FOR UPDATE" : ""),
            run.get("autopost_config_id"),
            run.get("workspace_id"),
            run.get("brand_id"));
    if (!c.get("version").equals(run.get("autopost_version"))
        || (!Boolean.TRUE.equals(run.get("autopost_preview"))
            && !Boolean.TRUE.equals(c.get("enabled"))))
      throw Problem.conflict(
          "Thiết lập đăng tự động đã đổi hoặc đã tắt. Bài này không được tự đăng.");
    return c;
  }

  public void assertPublication(Map<String, Object> p, Map<String, Object> b) {
    if (p.get("autopost_config_id") == null) return;
    var c =
        db.one("SELECT * FROM autopost_config WHERE id=? FOR UPDATE", p.get("autopost_config_id"));
    if (!Boolean.TRUE.equals(c.get("enabled"))
        || !c.get("version").equals(p.get("autopost_version"))
        || !Objects.equals(c.get("account_id"), p.get("account_id")))
      throw Problem.conflict("Lịch tự động đã tắt hoặc thay đổi; bài không được gửi.");
    var account = db.one("SELECT provider FROM social_account WHERE id=?", p.get("account_id"));
    if ("META".equals(account.get("provider"))
        && (!Boolean.TRUE.equals(c.get("live_consent"))
            || !env.getProperty("app.live-publishing", Boolean.class, false)))
      throw Problem.conflict("Chưa cho phép đăng thật cho lịch tự động.");
    if (!actors.allowed(
        Db.id(c.get("workspace_id")),
        Db.id(c.get("brand_id")),
        c.get("created_by").toString(),
        "PUBLISHER")) throw Problem.conflict("Người thiết lập không còn quyền đăng bài.");
  }

  public static boolean ruleApprovalValid(
      Db db, ActorPolicy actors, Map<String, Object> r, Map<String, Object> b, String actor) {
    if (!actor.startsWith("autopost:")) return false;
    var rows =
        db.list(
            "SELECT c.*,r.autopost_version,r.policy_version run_policy,r.ai_provider FROM"
                + " workflow_run r JOIN autopost_config c ON c.id=r.autopost_config_id WHERE"
                + " r.output_revision_id=? AND r.autopost_preview=false",
            r.get("id"));
    if (rows.isEmpty()) return false;
    var c = rows.getFirst();
    if (!Boolean.TRUE.equals(c.get("enabled"))
        || !c.get("version").equals(c.get("autopost_version"))
        || !b.get("policy_version").equals(c.get("run_policy"))
        || !actor.equals("autopost:" + c.get("id") + ":" + c.get("version"))
        || !Set.of("AUTO", "DEMO").contains(c.get("mode"))
        || Boolean.TRUE.equals(b.get("require_separate_reviewer"))
        || !((List<?>) r.get("risk_flags")).isEmpty()) return false;
    return actors.allowed(
            Db.id(c.get("workspace_id")),
            Db.id(c.get("brand_id")),
            c.get("created_by").toString(),
            "PUBLISHER")
        && (!"AUTO".equals(c.get("mode"))
            || (Boolean.TRUE.equals(c.get("live_consent"))
                && !"DEMO".equals(c.get("ai_provider"))));
  }

  public void queue(
      Map<String, Object> run,
      Map<String, Object> r,
      Map<String, Object> b,
      boolean humanApproved) {
    if (Boolean.TRUE.equals(run.get("autopost_preview"))) return;
    var c = forRun(run, true);
    if (c == null || !"APPROVED".equals(r.get("status"))) return;
    if ("REVIEW".equals(c.get("mode")) && !humanApproved) return;
    Instant slot = Instant.parse(run.get("autopost_due_at").toString());
    if (slot.plusSeconds(3600).isBefore(Instant.now()))
      return; // A missed slot never posts the next day.
    if (!db.list(
            "SELECT id FROM publication WHERE revision_id=? AND autopost_config_id=?",
            r.get("id"),
            c.get("id"))
        .isEmpty()) return;
    var a =
        db.one(
            "SELECT * FROM social_account WHERE id=? AND workspace_id=? AND brand_id=? AND"
                + " health='CONNECTED'",
            c.get("account_id"),
            c.get("workspace_id"),
            c.get("brand_id"));
    if ("META".equals(a.get("provider")) && !Boolean.TRUE.equals(c.get("live_consent"))) return;
    // Rule approval is checked by the publisher after the run has its revision recorded.
    if (humanApproved) gate.approval(r, b, Instant.now());
    contents.validateSources(
        Db.id(c.get("workspace_id")),
        Db.id(c.get("brand_id")),
        ((List<?>) r.get("source_refs")).stream().map(Db::id).toList());
    var asset = db.one("SELECT * FROM asset_version WHERE revision_id=?", r.get("id"));
    var snap = new LinkedHashMap<String, Object>();
    snap.put("message", r.get("hook") + "\n\n" + r.get("body") + "\n\n" + r.get("cta"));
    snap.put("asset_key", asset.get("storage_key"));
    snap.put("asset_checksum", asset.get("checksum"));
    snap.put("revision_hash", r.get("snapshot_hash"));
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " publication(id,workspace_id,brand_id,revision_id,account_id,intent,due_at,deadline_at,snapshot,snapshot_hash,policy_version,created_by,autopost_config_id,autopost_version)"
            + " VALUES(?,?,?,?,?,?,?, ?,?::jsonb,?,?,?,?,?)",
        id,
        c.get("workspace_id"),
        c.get("brand_id"),
        r.get("id"),
        c.get("account_id"),
        "autopost:" + run.get("id"),
        java.sql.Timestamp.from(slot),
        java.sql.Timestamp.from(slot.plusSeconds(3600)),
        db.json(snap),
        r.get("snapshot_hash"),
        b.get("policy_version"),
        c.get("created_by"),
        c.get("id"),
        c.get("version"));
    db.jdbc.update(
        "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id,due_at) VALUES(?,?,'PUBLISH',?,?)",
        c.get("workspace_id"),
        c.get("brand_id"),
        id,
        java.sql.Timestamp.from(slot));
    db.audit(
        Db.id(c.get("workspace_id")),
        Db.id(c.get("brand_id")),
        c.get("created_by").toString(),
        "AUTOPOST_SCHEDULED",
        id);
    db.event(Db.id(c.get("workspace_id")), Db.id(c.get("brand_id")), "PublicationQueued", id);
  }

  public void approved(UUID revision) {
    var rows =
        db.list(
            "SELECT * FROM workflow_run WHERE output_revision_id=? AND autopost_config_id IS NOT"
                + " NULL AND autopost_preview=false",
            revision);
    if (rows.isEmpty()) return;
    var run = rows.getFirst();
    var b = db.one("SELECT * FROM brand WHERE id=? FOR UPDATE", run.get("brand_id"));
    var c = config(Db.id(run.get("workspace_id")), Db.id(run.get("brand_id")));
    if (c == null
        || !Boolean.TRUE.equals(c.get("enabled"))
        || !c.get("version").equals(run.get("autopost_version"))) return;
    var r = db.one("SELECT * FROM content_revision WHERE id=?", revision);
    queue(run, r, b, true);
    db.jdbc.update("UPDATE workflow_run SET status='COMPLETED' WHERE id=?", run.get("id"));
  }

  public void cancelPending(UUID config) {
    db.jdbc.update(
        "UPDATE publication SET status='CANCELLED',last_error='Lịch tự động đã đổi hoặc"
            + " tắt.',version=version+1 WHERE autopost_config_id=? AND status IN"
            + " ('QUEUED','RETRY_WAIT','BLOCKED')",
        config);
    db.jdbc.update(
        "UPDATE job SET status='DONE',lease_until=NULL WHERE kind='PUBLISH' AND status='READY' AND"
            + " aggregate_id IN (SELECT id FROM publication WHERE autopost_config_id=? AND"
            + " status='CANCELLED')",
        config);
  }
}

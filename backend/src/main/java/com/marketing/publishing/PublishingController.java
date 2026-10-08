package com.marketing.publishing;

import com.marketing.content.ContentService;
import com.marketing.identity.Scope;
import com.marketing.operations.Idempotency;
import com.marketing.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class PublishingController {
  private final Db db;
  private final Scope scope;
  private final Idempotency keys;
  private final ContentService contents;
  private final MetaPublisher meta;
  private final com.marketing.identity.ActorPolicy actors;

  public PublishingController(
      Db db,
      Scope scope,
      Idempotency keys,
      ContentService contents,
      MetaPublisher meta,
      com.marketing.identity.ActorPolicy actors) {
    this.db = db;
    this.scope = scope;
    this.keys = keys;
    this.contents = contents;
    this.meta = meta;
    this.actors = actors;
  }

  public record Schedule(
      @NotNull UUID revision_id,
      @NotNull UUID account_id,
      @NotNull Instant due_at,
      @NotBlank @Size(max = 100) String intent) {}

  @PostMapping("/publications")
  @Transactional
  ResponseEntity<?> schedule(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Schedule s) {
    var m = scope.current();
    m.require("PUBLISHER");
    var r =
        db.one(
            "SELECT * FROM content_revision WHERE id=? AND workspace_id=?",
            s.revision_id(),
            m.workspace());
    UUID brand = Db.id(r.get("brand_id"));
    var b = scope.brand(m, brand);
    UUID existing = keys.find(m.workspace(), "publication", key, s);
    if (existing != null) {
      var p =
          db.one(
              "SELECT * FROM publication WHERE id=? AND workspace_id=?", existing, m.workspace());
      scope.brand(m, Db.id(p.get("brand_id")));
      return ResponseEntity.accepted().body(p);
    }
    db.one(
        "SELECT id FROM content_item WHERE id=? AND workspace_id=? FOR UPDATE",
        r.get("content_id"),
        m.workspace());
    var pending =
        db.list(
            "SELECT p.id FROM publication p JOIN content_revision cr ON cr.id=p.revision_id WHERE"
                + " cr.content_id=? AND p.account_id=? AND p.workspace_id=? AND p.status IN"
                + " ('QUEUED','RETRY_WAIT','DISPATCHING','UNKNOWN')",
            r.get("content_id"),
            s.account_id(),
            m.workspace());
    if (!pending.isEmpty())
      throw Problem.conflict(
          "Nội dung này đã có lịch đang chờ hoặc kết quả chưa xác định trên Page. Hủy lịch nội bộ"
              + " hoặc đối soát trước khi tạo lịch mới.");
    if (!Boolean.TRUE.equals(b.get("enabled")))
      throw Problem.conflict("Thương hiệu đang tạm dừng.");
    if (!s.due_at().isAfter(Instant.now().minusSeconds(5))
        || s.due_at().isAfter(Instant.now().plus(Duration.ofDays(6))))
      throw Problem.bad("Lịch đăng phải từ hiện tại đến 6 ngày tới, trong thời hạn duyệt.");
    db.one(
        "SELECT * FROM social_account WHERE id=? AND workspace_id=? AND brand_id=? AND"
            + " health='CONNECTED'",
        s.account_id(),
        m.workspace(),
        brand);
    approval(r, b, s.due_at());
    contents.validateSources(
        m.workspace(), brand, ((List<?>) r.get("source_refs")).stream().map(Db::id).toList());
    var asset =
        db.one(
            "SELECT * FROM asset_version WHERE revision_id=? AND workspace_id=?",
            s.revision_id(),
            m.workspace());
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("message", r.get("hook") + "\n\n" + r.get("body") + "\n\n" + r.get("cta"));
    snapshot.put("asset_key", asset.get("storage_key"));
    snapshot.put("asset_checksum", asset.get("checksum"));
    snapshot.put("revision_hash", r.get("snapshot_hash"));
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " publication(id,workspace_id,brand_id,revision_id,account_id,intent,due_at,deadline_at,snapshot,snapshot_hash,policy_version,created_by)"
            + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?,?)",
        id,
        m.workspace(),
        brand,
        s.revision_id(),
        s.account_id(),
        s.intent(),
        java.sql.Timestamp.from(s.due_at()),
        java.sql.Timestamp.from(s.due_at().plusSeconds(3600)),
        db.json(snapshot),
        r.get("snapshot_hash"),
        b.get("policy_version"),
        m.email());
    db.jdbc.update(
        "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id,due_at) VALUES(?,?,'PUBLISH',?,?)",
        m.workspace(),
        brand,
        id,
        java.sql.Timestamp.from(s.due_at()));
    keys.save(m.workspace(), "publication", key, s, id);
    db.audit(m.workspace(), brand, m.email(), "PUBLICATION_QUEUED", id);
    db.event(m.workspace(), brand, "PublicationQueued", id);
    return ResponseEntity.accepted().body(db.one("SELECT * FROM publication WHERE id=?", id));
  }

  public void approval(Map<String, Object> r, Map<String, Object> b, Instant at) {
    if (!"APPROVED".equals(r.get("status"))) throw Problem.conflict("Revision chưa được duyệt.");
    var approvals =
        db.list(
            "SELECT id,actor FROM approval WHERE revision_id=? AND workspace_id=? AND"
                + " snapshot_hash=? AND policy_version=? AND decision='APPROVED' AND valid_until>?",
            r.get("id"),
            r.get("workspace_id"),
            r.get("snapshot_hash"),
            b.get("policy_version"),
            java.sql.Timestamp.from(at));
    if (approvals.stream()
        .noneMatch(
            a ->
                actors.allowed(
                        Db.id(r.get("workspace_id")),
                        Db.id(r.get("brand_id")),
                        a.get("actor").toString(),
                        "REVIEWER")
                    || ("RULED_AUTOPILOT".equals(b.get("mode"))
                        && a.get("actor").equals("rule:grounded-template-v1"))
                    || com.marketing.workflow.AutopostService.ruleApprovalValid(
                        db, actors, r, b, a.get("actor").toString())))
      throw Problem.conflict("Approval hết hạn hoặc policy đã thay đổi. Cần duyệt lại.");
  }

  @GetMapping("/calendar")
  Object calendar(
      @RequestParam UUID brand_id,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to) {
    var m = scope.current();
    scope.brand(m, brand_id);
    Instant start = from == null ? Instant.now().minus(Duration.ofDays(30)) : from,
        end = to == null ? Instant.now().plus(Duration.ofDays(7)) : to;
    if (Duration.between(start, end).toDays() > 100 || end.isBefore(start))
      throw Problem.bad("Khoảng lịch tối đa 100 ngày.");
    return db.list(
        "SELECT p.*,r.hook,a.name account_name,a.provider FROM publication p JOIN content_revision"
            + " r ON r.id=p.revision_id JOIN social_account a ON a.id=p.account_id WHERE"
            + " p.workspace_id=? AND p.brand_id=? AND p.due_at>=? AND p.due_at<=? ORDER BY p.due_at"
            + " LIMIT 100",
        m.workspace(),
        brand_id,
        java.sql.Timestamp.from(start),
        java.sql.Timestamp.from(end));
  }

  @PostMapping("/publications/{id}/cancel")
  @Transactional
  Object cancel(@PathVariable UUID id) {
    var m = scope.current();
    m.require("PUBLISHER");
    var p =
        db.one(
            "SELECT * FROM publication WHERE id=? AND workspace_id=? FOR UPDATE",
            id,
            m.workspace());
    UUID brand = Db.id(p.get("brand_id"));
    scope.brand(m, brand);
    if (!Set.of("QUEUED", "RETRY_WAIT", "BLOCKED").contains(p.get("status")))
      throw Problem.conflict(
          "Chỉ hủy được lịch nội bộ chưa gửi. Bài đang gửi hoặc UNKNOWN cần đối soát.");
    db.jdbc.update("UPDATE publication SET status='CANCELLED',version=version+1 WHERE id=?", id);
    db.jdbc.update(
        "UPDATE job SET status='DONE',lease_until=NULL WHERE kind='PUBLISH' AND aggregate_id=?",
        id);
    db.audit(m.workspace(), brand, m.email(), "PUBLICATION_CANCELLED", id);
    return Map.of("ok", true);
  }

  @PostMapping("/publications/{id}/reconcile")
  @Transactional
  Object reconcile(@PathVariable UUID id, @RequestBody Map<String, String> data) {
    var m = scope.current();
    m.require("PUBLISHER");
    var p =
        db.one(
            "SELECT * FROM publication WHERE id=? AND workspace_id=? FOR UPDATE",
            id,
            m.workspace());
    UUID brand = Db.id(p.get("brand_id"));
    scope.brand(m, brand);
    if (!"UNKNOWN".equals(p.get("status")))
      throw Problem.conflict("Chỉ đối soát publication UNKNOWN.");
    var a = db.one("SELECT * FROM social_account WHERE id=?", p.get("account_id"));
    if (!"META".equals(a.get("provider"))) throw Problem.bad("Đối soát thật chỉ áp dụng cho Meta.");
    String remote = data.getOrDefault("remote_post_id", "");
    if (!remote.startsWith(a.get("remote_id") + "_"))
      throw Problem.bad("Remote ID không thuộc Page đã kết nối.");
    var post = meta.fetchPost(a, remote);
    var snapshot = (Map<?, ?>) p.get("snapshot");
    if (!snapshot.get("message").equals(post.get("message")))
      throw Problem.conflict("Nội dung remote không khớp snapshot. Cần kiểm tra thủ công.");
    db.jdbc.update(
        "UPDATE publication SET"
            + " status='PUBLISHED',remote_post_id=?,remote_url=?,published_at=now(),last_error=NULL,version=version+1"
            + " WHERE id=?",
        remote,
        post.get("permalink_url"),
        id);
    db.jdbc.update(
        "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id,due_at)"
            + " VALUES(?,?,'METRICS',?,now())",
        m.workspace(),
        brand,
        id);
    db.event(m.workspace(), brand, "PublicationConfirmed", id);
    db.audit(m.workspace(), brand, m.email(), "PUBLICATION_RECONCILED", id);
    return Map.of("ok", true);
  }
}

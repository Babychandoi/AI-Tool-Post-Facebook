package com.marketing.content;

import com.marketing.identity.Scope;
import com.marketing.media.AssetStore;
import com.marketing.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class ContentController {
  private final Db db;
  private final Scope scope;
  private final ContentService service;
  private final AssetStore assets;
  private final com.marketing.workflow.AutopostService autopost;

  public ContentController(
      Db db,
      Scope scope,
      ContentService service,
      AssetStore assets,
      com.marketing.workflow.AutopostService autopost) {
    this.db = db;
    this.scope = scope;
    this.service = service;
    this.assets = assets;
    this.autopost = autopost;
  }

  public record NewContent(@NotNull UUID brand_id, @NotNull @Valid ContentService.Draft draft) {}

  public record Review(
      @Pattern(regexp = "APPROVED|CHANGES_REQUESTED") String decision,
      @NotNull @Size(max = 2000) String note,
      @NotNull String expected_hash) {}

  @GetMapping("/contents")
  Object list(@RequestParam UUID brand_id) {
    var m = scope.current();
    scope.brand(m, brand_id);
    return db.list(
        "SELECT DISTINCT ON(r.content_id) r.*,b.name brand_name FROM content_revision r JOIN brand"
            + " b ON b.id=r.brand_id WHERE r.workspace_id=? AND r.brand_id=? ORDER BY"
            + " r.content_id,r.revision_no DESC",
        m.workspace(),
        brand_id);
  }

  @PostMapping("/contents")
  Object create(@Valid @RequestBody NewContent d) {
    var m = scope.current();
    m.require("MARKETER");
    scope.brand(m, d.brand_id());
    return service.create(m.workspace(), d.brand_id(), null, null, m.email(), d.draft());
  }

  @PostMapping("/contents/{id}/revisions")
  Object revision(@PathVariable UUID id, @Valid @RequestBody ContentService.Draft d) {
    var m = scope.current();
    m.require("MARKETER");
    var item =
        db.one("SELECT * FROM content_item WHERE id=? AND workspace_id=?", id, m.workspace());
    UUID brand = Db.id(item.get("brand_id"));
    scope.brand(m, brand);
    return service.create(m.workspace(), brand, id, null, m.email(), d);
  }

  @GetMapping("/contents/{id}/revisions")
  Object history(@PathVariable UUID id) {
    var m = scope.current();
    var item =
        db.one("SELECT * FROM content_item WHERE id=? AND workspace_id=?", id, m.workspace());
    scope.brand(m, Db.id(item.get("brand_id")));
    return db.list(
        "SELECT * FROM content_revision WHERE content_id=? AND workspace_id=? ORDER BY revision_no"
            + " DESC",
        id,
        m.workspace());
  }

  @PostMapping("/revisions/{id}/submit")
  @Transactional
  Object submit(@PathVariable UUID id) {
    var m = scope.current();
    m.require("MARKETER");
    var r = revision(m, id);
    if (db.jdbc.update(
            "UPDATE content_revision SET status='IN_REVIEW' WHERE id=? AND status='DRAFT'", id)
        != 1) throw Problem.conflict("Chỉ bản nháp mới có thể gửi duyệt.");
    db.audit(m.workspace(), Db.id(r.get("brand_id")), m.email(), "REVIEW_REQUESTED", id);
    return Map.of("ok", true);
  }

  @PostMapping("/revisions/{id}/approvals")
  @Transactional
  Object approve(@PathVariable UUID id, @Valid @RequestBody Review d) {
    var m = scope.current();
    m.require("REVIEWER");
    var r =
        db.one(
            "SELECT * FROM content_revision WHERE id=? AND workspace_id=? FOR UPDATE",
            id,
            m.workspace());
    UUID brand = Db.id(r.get("brand_id"));
    var b = scope.brand(m, brand);
    b =
        db.one(
            "SELECT * FROM brand WHERE id=? AND workspace_id=? FOR UPDATE", brand, m.workspace());
    if (Boolean.TRUE.equals(b.get("require_separate_reviewer"))
        && r.get("created_by").equals(m.email()))
      throw Problem.conflict("Policy yêu cầu người duyệt khác người sửa cuối.");
    if (!"IN_REVIEW".equals(r.get("status")))
      throw Problem.conflict("Revision không còn chờ duyệt.");
    if (!r.get("snapshot_hash").equals(d.expected_hash()))
      throw Problem.conflict("Snapshot không khớp. Tải lại nội dung.");
    var refs = ((List<?>) r.get("source_refs")).stream().map(Db::id).toList();
    service.validateSources(m.workspace(), brand, refs);
    db.jdbc.update(
        "INSERT INTO"
            + " approval(workspace_id,brand_id,revision_id,policy_version,snapshot_hash,actor,decision,note,valid_until)"
            + " VALUES(?,?,?,?,?,?,?,?,now()+interval '7 days')",
        m.workspace(),
        brand,
        id,
        b.get("policy_version"),
        r.get("snapshot_hash"),
        m.email(),
        d.decision(),
        d.note());
    db.jdbc.update("UPDATE content_revision SET status=? WHERE id=?", d.decision(), id);
    db.audit(m.workspace(), brand, m.email(), "REVIEW_" + d.decision(), id);
    db.event(m.workspace(), brand, "ApprovalGranted", id);
    if ("APPROVED".equals(d.decision())) autopost.approved(id);
    return Map.of("ok", true);
  }

  @GetMapping(value = "/revisions/{id}/image", produces = "image/png")
  ResponseEntity<byte[]> image(@PathVariable UUID id) {
    var m = scope.current();
    revision(m, id);
    var a =
        db.one(
            "SELECT * FROM asset_version WHERE revision_id=? AND workspace_id=?",
            id,
            m.workspace());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(assets.get(a.get("storage_key").toString()));
  }

  private Map<String, Object> revision(Scope.Member m, UUID id) {
    var r =
        db.one("SELECT * FROM content_revision WHERE id=? AND workspace_id=?", id, m.workspace());
    scope.brand(m, Db.id(r.get("brand_id")));
    return r;
  }
}

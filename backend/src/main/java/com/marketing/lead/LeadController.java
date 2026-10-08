package com.marketing.lead;

import com.marketing.identity.Scope;
import com.marketing.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class LeadController {
  private final Db db;
  private final Scope scope;

  public LeadController(Db db, Scope scope) {
    this.db = db;
    this.scope = scope;
  }

  public record Capture(
      @NotBlank @Size(max = 150) String name,
      @NotBlank @Size(max = 200) String contact,
      @NotNull @Size(max = 2000) String interest,
      @NotBlank @Size(max = 500) String purpose,
      @AssertTrue boolean consent,
      UUID reference) {}

  public record NewLead(@NotNull UUID brand_id, @NotNull @Valid Capture lead) {}

  public record Update(
      @Pattern(regexp = "NEW|QUALIFIED|CONTACTED|WON|LOST|DISQUALIFIED") String stage,
      @Size(max = 250) String assigned_user,
      @Size(max = 2000) String next_action,
      int expected_version) {}

  @GetMapping("/leads")
  Object list(
      @RequestParam UUID brand_id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor) {
    var m = scope.current();
    m.require("SALES", "ANALYST");
    scope.brand(m, brand_id);
    limit = Math.max(1, Math.min(limit, 100));
    Instant before = Instant.now().plusSeconds(10);
    UUID beforeId = new UUID(-1, -1);
    if (cursor != null) {
      try {
        String decoded =
            new String(
                Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|");
        before = Instant.parse(parts[0]);
        beforeId = UUID.fromString(parts[1]);
      } catch (Exception e) {
        throw Problem.bad("Cursor không hợp lệ.");
      }
    }
    var rows =
        db.list(
            "SELECT * FROM lead WHERE workspace_id=? AND brand_id=? AND (?=false OR assigned_user"
                + " IS NULL OR assigned_user=?) AND (created_at,id)<(?,?) ORDER BY created_at"
                + " DESC,id DESC LIMIT ?",
            m.workspace(),
            brand_id,
            m.role().equals("SALES"),
            m.email(),
            java.sql.Timestamp.from(before),
            beforeId,
            limit + 1);
    boolean more = rows.size() > limit;
    var items = new ArrayList<>(rows.subList(0, Math.min(rows.size(), limit)));
    if (m.role().equals("ANALYST"))
      items.forEach(
          l -> {
            l.put("contact", "Đã ẩn");
            l.put("name", "Khách hàng");
            l.put("interest", "");
            l.put("next_action", null);
          });
    String next = null;
    if (more) {
      var last = items.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.get("created_at") + "|" + last.get("id"))
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("items", items);
    out.put("next_cursor", next);
    return out;
  }

  @PostMapping("/leads")
  @Transactional
  Object create(@Valid @RequestBody NewLead d) {
    var m = scope.current();
    m.require("SALES");
    scope.brand(m, d.brand_id());
    return capture(m.workspace(), d.brand_id(), d.lead(), m.email());
  }

  @PatchMapping("/leads/{id}")
  @Transactional
  Object update(@PathVariable UUID id, @Valid @RequestBody Update u) {
    var m = scope.current();
    m.require("SALES");
    var l = db.one("SELECT * FROM lead WHERE id=? AND workspace_id=?", id, m.workspace());
    UUID brand = Db.id(l.get("brand_id"));
    scope.brand(m, brand);
    if (m.role().equals("SALES")
        && l.get("assigned_user") != null
        && !m.email().equals(l.get("assigned_user"))) throw Problem.missing();
    if (u.assigned_user() != null && !u.assigned_user().isBlank()) {
      var users =
          db.list(
              "SELECT u.id FROM app_user u JOIN membership m ON m.user_id=u.id WHERE u.email=? AND"
                  + " m.workspace_id=? AND u.enabled AND m.role IN ('OWNER','ADMIN','SALES') AND"
                  + " (m.brand_scope IS NULL OR ?=ANY(m.brand_scope))",
              u.assigned_user(),
              m.workspace(),
              brand);
      if (users.isEmpty()) throw Problem.bad("Người phụ trách chưa được cấp quyền thương hiệu.");
    }
    if (db.jdbc.update(
            "UPDATE lead SET stage=?,assigned_user=?,next_action=?,version=version+1 WHERE id=? AND"
                + " workspace_id=? AND version=?",
            u.stage(),
            u.assigned_user(),
            u.next_action(),
            id,
            m.workspace(),
            u.expected_version())
        != 1) throw Problem.conflict("Lead đã thay đổi. Tải lại trước khi lưu.");
    db.jdbc.update(
        "INSERT INTO lead_activity(lead_id,actor,action) VALUES(?,?,?)",
        id,
        m.email(),
        "STAGE_" + u.stage());
    db.audit(m.workspace(), brand, m.email(), "LEAD_UPDATED", id);
    return db.one("SELECT * FROM lead WHERE id=?", id);
  }

  @GetMapping("/forms/{key}")
  Object form(@PathVariable UUID key) {
    return db.one(
        "SELECT name,color,public_form_key FROM brand WHERE public_form_key=? AND enabled=true",
        key);
  }

  @PostMapping("/forms/{key}")
  @Transactional
  Object publicCapture(@PathVariable UUID key, @Valid @RequestBody Capture c) {
    var b =
        db.one("SELECT id,workspace_id FROM brand WHERE public_form_key=? AND enabled=true", key);
    capture(Db.id(b.get("workspace_id")), Db.id(b.get("id")), c, "public-form");
    return Map.of("ok", true);
  }

  private Map<String, Object> capture(UUID workspace, UUID brand, Capture c, String actor) {
    String contact = c.contact().strip();
    if (!contact.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") && !contact.matches("[+0-9 ()-]{8,25}"))
      throw Problem.bad("Nhập email hoặc số điện thoại hợp lệ.");
    UUID source = null;
    String confidence = "UNKNOWN";
    if (c.reference() != null) {
      var p =
          db.one(
              "SELECT id FROM publication WHERE tracking_key=? AND workspace_id=? AND brand_id=?"
                  + " AND status='PUBLISHED'",
              c.reference(),
              workspace,
              brand);
      source = Db.id(p.get("id"));
      confidence = "EXACT";
    }
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " lead(id,workspace_id,brand_id,name,contact,interest,source_publication_id,attribution_confidence,purpose,consent_at,consent_version)"
            + " VALUES(?,?,?,?,?,?,?,?,?,now(),'form-v1')",
        id,
        workspace,
        brand,
        c.name(),
        contact,
        c.interest(),
        source,
        confidence,
        c.purpose());
    db.jdbc.update(
        "INSERT INTO lead_activity(lead_id,actor,action) VALUES(?,?,'CAPTURED_WITH_CONSENT')",
        id,
        actor);
    db.event(workspace, brand, "LeadCaptured", id);
    db.audit(workspace, brand, actor, "LEAD_CAPTURED", id);
    return Map.of("id", id, "attribution_confidence", confidence);
  }
}

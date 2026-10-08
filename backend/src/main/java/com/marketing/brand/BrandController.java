package com.marketing.brand;

import com.marketing.identity.Scope;
import com.marketing.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/brands")
public class BrandController {
  private final Db db;
  private final Scope scope;

  public BrandController(Db db, Scope scope) {
    this.db = db;
    this.scope = scope;
  }

  public record Config(
      @NotBlank @Size(max = 150) String name,
      @NotNull @Size(max = 500) String industry,
      @NotNull @Size(max = 2000) String voice,
      @NotNull @Size(max = 1000) String audience,
      @Pattern(regexp = "#[a-fA-F0-9]{6}") String color,
      @NotBlank String timezone,
      @Pattern(regexp = "ASSISTED|RULED_AUTOPILOT") String mode,
      @Min(0) long monthly_budget_micros,
      int expected_version,
      boolean require_separate_reviewer) {}

  @GetMapping
  Object list() {
    var m = scope.current();
    return db
        .list(
            "SELECT b.*,COALESCE(bm.spent_micros,0) spent_micros,COALESCE(bm.reserved_micros,0)"
                + " reserved_micros FROM brand b LEFT JOIN budget_month bm ON bm.brand_id=b.id AND"
                + " bm.month=date_trunc('month',now())::date WHERE b.workspace_id=? ORDER BY"
                + " b.created_at",
            m.workspace())
        .stream()
        .filter(b -> m.allBrands() || m.brands().contains(Db.id(b.get("id"))))
        .toList();
  }

  @PostMapping
  @Transactional
  Object create(@Valid @RequestBody Config c) {
    var m = scope.current();
    m.require();
    java.time.ZoneId.of(c.timezone());
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " brand(id,workspace_id,name,industry,voice,audience,color,timezone,mode,monthly_budget_micros,require_separate_reviewer)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        m.workspace(),
        c.name(),
        c.industry(),
        c.voice(),
        c.audience(),
        c.color(),
        c.timezone(),
        c.mode(),
        c.monthly_budget_micros(),
        c.require_separate_reviewer());
    var b = scope.brand(m, id);
    version(b);
    db.audit(m.workspace(), id, m.email(), "BRAND_CREATED", id);
    return b;
  }

  @PutMapping("/{id}")
  @Transactional
  Object update(@PathVariable UUID id, @Valid @RequestBody Config c) {
    var m = scope.current();
    m.require();
    scope.brand(m, id);
    java.time.ZoneId.of(c.timezone());
    int changed =
        db.jdbc.update(
            "UPDATE brand SET"
                + " name=?,industry=?,voice=?,audience=?,color=?,timezone=?,mode=?,monthly_budget_micros=?,require_separate_reviewer=?,version=version+1,policy_version=policy_version+1"
                + " WHERE id=? AND workspace_id=? AND version=?",
            c.name(),
            c.industry(),
            c.voice(),
            c.audience(),
            c.color(),
            c.timezone(),
            c.mode(),
            c.monthly_budget_micros(),
            c.require_separate_reviewer(),
            id,
            m.workspace(),
            c.expected_version());
    if (changed != 1) throw Problem.conflict("Cấu hình đã thay đổi. Tải lại trước khi lưu.");
    var b = scope.brand(m, id);
    version(b);
    db.audit(m.workspace(), id, m.email(), "BRAND_POLICY_UPDATED", id);
    return b;
  }

  @PostMapping("/{id}/pause")
  @Transactional
  Object pause(@PathVariable UUID id, @RequestBody Map<String, Boolean> data) {
    var m = scope.current();
    m.require();
    scope.brand(m, id);
    boolean enabled = Boolean.TRUE.equals(data.get("enabled"));
    db.jdbc.update(
        "UPDATE brand SET enabled=?,version=version+1 WHERE id=? AND workspace_id=?",
        enabled,
        id,
        m.workspace());
    version(scope.brand(m, id));
    db.audit(m.workspace(), id, m.email(), enabled ? "BRAND_RESUMED" : "BRAND_PAUSED", id);
    return scope.brand(m, id);
  }

  private void version(Map<String, Object> b) {
    db.jdbc.update(
        "INSERT INTO brand_version(brand_id,workspace_id,version,snapshot) VALUES(?,?,?,?::jsonb)",
        b.get("id"),
        b.get("workspace_id"),
        b.get("version"),
        db.json(b));
  }
}

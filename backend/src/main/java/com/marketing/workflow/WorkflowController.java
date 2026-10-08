package com.marketing.workflow;

import com.marketing.identity.Scope;
import com.marketing.operations.*;
import com.marketing.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class WorkflowController {
  private final Db db;
  private final Scope scope;
  private final Idempotency keys;
  private final Budget budget;
  private final Environment env;
  private final com.marketing.ai.AiWriter writer;

  public WorkflowController(
      Db db,
      Scope scope,
      Idempotency keys,
      Budget budget,
      Environment env,
      com.marketing.ai.AiWriter writer) {
    this.db = db;
    this.scope = scope;
    this.keys = keys;
    this.budget = budget;
    this.env = env;
    this.writer = writer;
  }

  public record Generate(
      @NotNull UUID brand_id, UUID campaign_id, @NotBlank @Size(max = 250) String topic) {}

  public record Campaign(
      @NotNull UUID brand_id,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 500) String objective,
      @NotNull @Size(max = 10) List<String> pillars,
      boolean generation_enabled,
      String daily_generation_time) {}

  @PostMapping("/runs")
  @Transactional
  ResponseEntity<?> generate(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Generate g) {
    var m = scope.current();
    m.require("MARKETER");
    var b = scope.brand(m, g.brand_id());
    if (!Boolean.TRUE.equals(b.get("enabled")))
      throw Problem.conflict("Thương hiệu đang tạm dừng.");
    if (g.campaign_id() != null)
      db.one(
          "SELECT id FROM campaign WHERE id=? AND brand_id=? AND workspace_id=?",
          g.campaign_id(),
          g.brand_id(),
          m.workspace());
    UUID existing = keys.find(m.workspace(), "generate", key, g);
    if (existing != null) {
      var r =
          db.one(
              "SELECT * FROM workflow_run WHERE id=? AND workspace_id=?", existing, m.workspace());
      scope.brand(m, Db.id(r.get("brand_id")));
      return ResponseEntity.accepted().body(r);
    }
    var plan = writer.plan(m.workspace(), m.email());
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " workflow_run(id,workspace_id,brand_id,campaign_id,topic,brand_version,policy_version,created_by)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        m.workspace(),
        g.brand_id(),
        g.campaign_id(),
        g.topic(),
        b.get("version"),
        b.get("policy_version"),
        m.email());
    writer.pin(id, plan);
    if (!plan.chatgpt())
      budget.reserve(
          m.workspace(),
          g.brand_id(),
          id,
          env.getProperty("app.generation-reserve-micros", Long.class, 100000L));
    db.jdbc.update(
        "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id) VALUES(?,?,'GENERATE',?)",
        m.workspace(),
        g.brand_id(),
        id);
    keys.save(m.workspace(), "generate", key, g, id);
    db.audit(m.workspace(), g.brand_id(), m.email(), "WORKFLOW_QUEUED", id);
    return ResponseEntity.accepted().body(db.one("SELECT * FROM workflow_run WHERE id=?", id));
  }

  @GetMapping("/runs")
  Object list(@RequestParam UUID brand_id) {
    var m = scope.current();
    scope.brand(m, brand_id);
    return db.list(
        "SELECT * FROM workflow_run WHERE workspace_id=? AND brand_id=? ORDER BY created_at DESC"
            + " LIMIT 50",
        m.workspace(),
        brand_id);
  }

  @GetMapping("/runs/{id}")
  Object get(@PathVariable UUID id) {
    var m = scope.current();
    var r = db.one("SELECT * FROM workflow_run WHERE id=? AND workspace_id=?", id, m.workspace());
    scope.brand(m, Db.id(r.get("brand_id")));
    r.put("steps", db.list("SELECT * FROM step_run WHERE run_id=? ORDER BY step", id));
    return r;
  }

  @GetMapping("/campaigns")
  Object campaigns(@RequestParam UUID brand_id) {
    var m = scope.current();
    scope.brand(m, brand_id);
    return db.list(
        "SELECT * FROM campaign WHERE brand_id=? AND workspace_id=? ORDER BY created_at DESC",
        brand_id,
        m.workspace());
  }

  @PostMapping("/campaigns")
  @Transactional
  Object campaign(@Valid @RequestBody Campaign c) {
    var m = scope.current();
    m.require("MARKETER");
    scope.brand(m, c.brand_id());
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " campaign(id,workspace_id,brand_id,name,objective,pillars,generation_enabled,daily_generation_time,created_by)"
            + " VALUES(?,?,?,?,?,?::jsonb,?,?::time,?)",
        id,
        m.workspace(),
        c.brand_id(),
        c.name(),
        c.objective(),
        db.json(c.pillars()),
        c.generation_enabled(),
        java.time.LocalTime.parse(
                c.daily_generation_time() == null ? "06:00" : c.daily_generation_time())
            .toString(),
        m.email());
    db.audit(m.workspace(), c.brand_id(), m.email(), "CAMPAIGN_CREATED", id);
    return db.one("SELECT * FROM campaign WHERE id=?", id);
  }

  public record Automation(
      boolean enabled, @NotBlank String daily_generation_time, int expected_version) {}

  @PutMapping("/campaigns/{id}/automation")
  @Transactional
  Object automation(@PathVariable UUID id, @Valid @RequestBody Automation a) {
    var m = scope.current();
    m.require("MARKETER");
    var c = db.one("SELECT * FROM campaign WHERE id=? AND workspace_id=?", id, m.workspace());
    UUID brand = Db.id(c.get("brand_id"));
    scope.brand(m, brand);
    String time = java.time.LocalTime.parse(a.daily_generation_time()).toString();
    if (db.jdbc.update(
            "UPDATE campaign SET"
                + " generation_enabled=?,daily_generation_time=?::time,created_by=?,version=version+1"
                + " WHERE id=? AND workspace_id=? AND version=?",
            a.enabled(),
            time,
            m.email(),
            id,
            m.workspace(),
            a.expected_version())
        != 1) throw Problem.conflict("Chiến dịch đã thay đổi. Tải lại trước khi lưu.");
    db.audit(m.workspace(), brand, m.email(), "CAMPAIGN_AUTOMATION_UPDATED", id);
    return Map.of("ok", true);
  }
}

package com.marketing.operations;

import com.marketing.identity.Scope;
import com.marketing.shared.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class OperationsController {
  private final Db db;
  private final Scope scope;
  private final Environment env;

  public OperationsController(Db db, Scope scope, Environment env) {
    this.db = db;
    this.scope = scope;
    this.env = env;
  }

  @GetMapping("/dashboard")
  Object dashboard(@RequestParam UUID brand_id) {
    var m = scope.current();
    var b = scope.brand(m, brand_id);
    var out = new LinkedHashMap<String, Object>();
    out.put("brand", b);
    out.put("demo", env.getProperty("app.demo", Boolean.class, false));
    out.put("live_publishing", env.getProperty("app.live-publishing", Boolean.class, false));
    out.put(
        "counts",
        db.one(
            "SELECT (SELECT count(*) FROM content_revision r WHERE r.workspace_id=? AND"
                + " r.brand_id=? AND r.status='IN_REVIEW') pending_review,(SELECT count(*) FROM"
                + " publication p WHERE p.workspace_id=? AND p.brand_id=? AND p.status IN"
                + " ('QUEUED','RETRY_WAIT')) scheduled,(SELECT count(*) FROM publication p WHERE"
                + " p.workspace_id=? AND p.brand_id=? AND p.status='PUBLISHED') published,(SELECT"
                + " count(*) FROM lead l WHERE l.workspace_id=? AND l.brand_id=?) leads,(SELECT"
                + " count(*) FROM publication p WHERE p.workspace_id=? AND p.brand_id=? AND"
                + " p.status IN ('UNKNOWN','BLOCKED','FAILED')) attention",
            m.workspace(),
            brand_id,
            m.workspace(),
            brand_id,
            m.workspace(),
            brand_id,
            m.workspace(),
            brand_id,
            m.workspace(),
            brand_id));
    out.put(
        "activity",
        db.list(
            "SELECT actor,action,aggregate_id,created_at FROM audit_log WHERE workspace_id=? AND"
                + " brand_id=? ORDER BY created_at DESC LIMIT 8",
            m.workspace(),
            brand_id));
    out.put(
        "budget",
        db
            .list(
                "SELECT * FROM budget_month WHERE brand_id=? AND workspace_id=? AND"
                    + " month=date_trunc('month',now())::date",
                brand_id,
                m.workspace())
            .stream()
            .findFirst()
            .orElse(Map.of("reserved_micros", 0, "spent_micros", 0)));
    return out;
  }

  @GetMapping("/operations")
  Object operations(@RequestParam UUID brand_id) {
    var m = scope.current();
    m.require();
    scope.brand(m, brand_id);
    return Map.of(
        "jobs",
        db.list(
            "SELECT * FROM job WHERE workspace_id=? AND brand_id=? ORDER BY created_at DESC LIMIT"
                + " 100",
            m.workspace(),
            brand_id),
        "audit",
        db.list(
            "SELECT * FROM audit_log WHERE workspace_id=? AND brand_id=? ORDER BY created_at DESC"
                + " LIMIT 100",
            m.workspace(),
            brand_id),
        "attempts",
        db.list(
            "SELECT a.* FROM publication_attempt a JOIN publication p ON p.id=a.publication_id"
                + " WHERE p.workspace_id=? AND p.brand_id=? ORDER BY a.created_at DESC LIMIT 50",
            m.workspace(),
            brand_id),
        "events",
        db.list(
            "SELECT * FROM outbox WHERE workspace_id=? AND brand_id=? ORDER BY created_at DESC"
                + " LIMIT 30",
            m.workspace(),
            brand_id));
  }

  @GetMapping("/reports")
  Object reports(@RequestParam UUID brand_id) {
    var m = scope.current();
    scope.brand(m, brand_id);
    return Map.of(
        "metrics",
        db.list(
            "SELECT s.*,r.hook FROM metric_snapshot s JOIN publication p ON p.id=s.publication_id"
                + " JOIN content_revision r ON r.id=p.revision_id WHERE s.workspace_id=? AND"
                + " s.brand_id=? ORDER BY s.fetched_at DESC LIMIT 100",
            m.workspace(),
            brand_id),
        "lead_stages",
        db.list(
            "SELECT stage,count(*) total FROM lead WHERE workspace_id=? AND brand_id=? GROUP BY"
                + " stage ORDER BY stage",
            m.workspace(),
            brand_id));
  }
}

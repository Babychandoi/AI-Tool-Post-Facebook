package com.marketing.workflow;

import com.marketing.identity.ActorPolicy;
import com.marketing.operations.Budget;
import com.marketing.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class CampaignScheduler {
  private final Db db;
  private final Budget budget;
  private final ActorPolicy actors;
  private final Environment env;
  private final com.marketing.ai.AiWriter writer;

  public CampaignScheduler(
      Db db, Budget budget, ActorPolicy actors, Environment env, com.marketing.ai.AiWriter writer) {
    this.db = db;
    this.budget = budget;
    this.actors = actors;
    this.env = env;
    this.writer = writer;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 15000)
  @Transactional
  public void plan() {
    for (var c :
        db.list(
            "SELECT c.*,b.timezone,b.version brand_version,b.policy_version FROM campaign c JOIN"
                + " brand b ON b.id=c.brand_id WHERE c.generation_enabled=true AND"
                + " c.status='ACTIVE' AND b.enabled=true ORDER BY b.id,c.id")) {
      ZonedDateTime now = ZonedDateTime.now(ZoneId.of(c.get("timezone").toString()));
      LocalTime due = LocalTime.parse(c.get("daily_generation_time").toString());
      if (now.toLocalTime().isBefore(due) || c.get("created_by") == null) continue;
      UUID workspace = Db.id(c.get("workspace_id")), brand = Db.id(c.get("brand_id"));
      if (!actors.allowed(workspace, brand, c.get("created_by").toString(), "MARKETER")) continue;
      String trigger = c.get("id") + ":" + now.toLocalDate();
      db.jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", trigger);
      if (!db.list(
              "SELECT id FROM workflow_run WHERE workspace_id=? AND brand_id=? AND trigger_key=?",
              workspace,
              brand,
              trigger)
          .isEmpty()) continue;
      var b = db.one("SELECT monthly_budget_micros FROM brand WHERE id=? FOR UPDATE", brand);
      com.marketing.ai.AiWriter.Plan plan;
      try {
        plan = writer.plan(workspace, c.get("created_by").toString());
      } catch (Problem disabled) {
        continue;
      }
      long reserve =
          plan.chatgpt()
              ? 0
              : env.getProperty("app.generation-reserve-micros", Long.class, 100000L);
      var spent =
          db.list(
              "SELECT spent_micros+reserved_micros total FROM budget_month WHERE brand_id=? AND"
                  + " month=date_trunc('month',now())::date",
              brand);
      if (!spent.isEmpty()
          && ((Number) spent.getFirst().get("total")).longValue() + reserve
              > ((Number) b.get("monthly_budget_micros")).longValue()) continue;
      if (reserve > ((Number) b.get("monthly_budget_micros")).longValue()) continue;
      List<?> pillars = (List<?>) c.get("pillars");
      String topic =
          pillars.isEmpty()
              ? c.get("name").toString()
              : pillars
                  .get(Math.floorMod(now.toLocalDate().toEpochDay(), pillars.size()))
                  .toString();
      if (topic.length() > 250) topic = topic.substring(0, 250);
      UUID id = UUID.randomUUID();
      db.jdbc.update(
          "INSERT INTO"
              + " workflow_run(id,workspace_id,brand_id,campaign_id,topic,brand_version,policy_version,created_by,trigger_key)"
              + " VALUES(?,?,?,?,?,?,?,?,?)",
          id,
          workspace,
          brand,
          c.get("id"),
          topic,
          c.get("brand_version"),
          c.get("policy_version"),
          c.get("created_by"),
          trigger);
      writer.pin(id, plan);
      if (!plan.chatgpt()) budget.reserve(workspace, brand, id, reserve);
      db.jdbc.update(
          "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id) VALUES(?,?,'GENERATE',?)",
          workspace,
          brand,
          id);
      db.audit(workspace, brand, "campaign-scheduler", "DAILY_WORKFLOW_QUEUED", id);
    }
  }
}

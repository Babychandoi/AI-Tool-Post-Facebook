package com.marketing.workflow;

import com.marketing.ai.AiWriter;
import com.marketing.content.ContentService;
import com.marketing.operations.*;
import com.marketing.shared.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class WorkflowProcessor {
  private final Db db;
  private final JobRepository jobs;
  private final Budget budget;
  private final ContentService content;
  private final AiWriter writer;
  private final AutopostService autopost;
  private final com.marketing.media.AiImageGenerator images;

  public WorkflowProcessor(
      Db db,
      JobRepository jobs,
      Budget budget,
      ContentService content,
      AiWriter writer,
      AutopostService autopost,
      com.marketing.media.AiImageGenerator images) {
    this.db = db;
    this.jobs = jobs;
    this.budget = budget;
    this.content = content;
    this.writer = writer;
    this.autopost = autopost;
    this.images = images;
  }

  public void process(Map<String, Object> job) {
    UUID runId = Db.id(job.get("aggregate_id"));
    var run = db.one("SELECT * FROM workflow_run WHERE id=?", runId);
    UUID brand = Db.id(run.get("brand_id")), workspace = Db.id(run.get("workspace_id"));
    var b = db.one("SELECT * FROM brand WHERE id=? AND workspace_id=?", brand, workspace);
    var config = autopost.forRun(run, false);
    if (config != null) {
      var campaign = db.one("SELECT objective FROM campaign WHERE id=?", config.get("campaign_id"));
      b.put("voice", b.get("voice") + ". Mục tiêu nội dung: " + campaign.get("objective"));
    }
    var actorPolicy = new com.marketing.identity.ActorPolicy(db);
    if (!actorPolicy.allowed(workspace, brand, run.get("created_by").toString(), "MARKETER"))
      throw Problem.conflict("Người khởi tạo không còn quyền thương hiệu.");
    if (!Boolean.TRUE.equals(b.get("enabled"))
        || !b.get("version").equals(run.get("brand_version")))
      throw Problem.conflict("Thương hiệu đã dừng hoặc cấu hình đã đổi.");
    var evidence =
        db
            .list(
                "SELECT id,title,body,source_url,version FROM knowledge_document WHERE"
                    + " workspace_id=? AND brand_id=? AND status='APPROVED' AND"
                    + " effective_from<=now() AND (effective_until IS NULL OR"
                    + " effective_until>now()) ORDER BY"
                    + " ts_rank_cd(to_tsvector('simple',body),plainto_tsquery('simple',?))"
                    + " DESC,created_at DESC LIMIT 6",
                workspace,
                brand,
                run.get("topic"))
            .stream()
            .map(
                e -> {
                  var safe = new LinkedHashMap<>(e);
                  String body = e.get("body").toString();
                  safe.put("body", body.substring(0, Math.min(body.length(), 4000)));
                  return (Map<String, Object>) safe;
                })
            .toList();
    jobs.fenced(
        job,
        () -> {
          db.jdbc.update("UPDATE workflow_run SET status='RUNNING',step=8 WHERE id=?", runId);
          step(runId, 1, "Khởi tạo workflow", "DONE");
          step(runId, 2, "Giữ ngân sách", "DONE");
          step(runId, 3, "Nạp nguồn đã duyệt", "DONE");
          step(runId, 8, "Viết bản nháp", "RUNNING");
          return null;
        });
    var result = writer.write(b, run.get("topic").toString(), evidence, run);
    var generated =
        config != null
                && Set.of("OPENAI", "GOOGLE", "CHATGPT_WEB").contains(config.get("image_mode"))
            ? images.generate(config, b, result.draft())
            : null;
    jobs.fenced(
        job,
        () -> {
          var current = db.one("SELECT * FROM brand WHERE id=? FOR UPDATE", brand);
          var latest = autopost.forRun(run, true);
          if (!Boolean.TRUE.equals(current.get("enabled"))
              || !current.get("policy_version").equals(run.get("policy_version")))
            throw Problem.conflict("Policy đã thay đổi trong khi tạo nội dung.");
          var draft =
              content.create(
                  workspace,
                  brand,
                  null,
                  run.get("campaign_id") == null ? null : Db.id(run.get("campaign_id")),
                  run.get("created_by").toString(),
                  result.draft(),
                  generated);
          UUID revision = Db.id(draft.get("id"));
          db.jdbc.update(
              "INSERT INTO"
                  + " ai_run(workspace_id,brand_id,run_id,provider,model,prompt_version,input_refs,output,input_tokens,output_tokens,usage_status)"
                  + " VALUES(?,?,?,?,?,'writer-v1',?::jsonb,?::jsonb,?,?,?)",
              workspace,
              brand,
              runId,
              result.provider(),
              result.model(),
              db.json(evidence.stream().map(e -> e.get("id")).toList()),
              db.json(result.draft()),
              result.inputTokens(),
              result.outputTokens(),
              "CHATGPT".equals(result.provider())
                  ? "CHATGPT_PLAN"
                  : result.cost() < 0 ? "ESTIMATED_RESERVED" : "RECORDED");
          boolean oldRule =
              "RULED_AUTOPILOT".equals(b.get("mode"))
                  && "DEMO".equals(result.provider())
                  && ((List<?>) draft.get("risk_flags")).isEmpty();
          boolean dailyAuto =
              latest != null
                  && !Boolean.TRUE.equals(run.get("autopost_preview"))
                  && Set.of("AUTO", "DEMO").contains(latest.get("mode"))
                  && !Boolean.TRUE.equals(current.get("require_separate_reviewer"))
                  && ((List<?>) draft.get("risk_flags")).isEmpty();
          boolean auto = latest == null ? oldRule : dailyAuto;
          db.jdbc.update(
              "UPDATE content_revision SET status=? WHERE id=?",
              auto ? "APPROVED" : "IN_REVIEW",
              revision);
          if (auto) {
            String actor =
                dailyAuto
                    ? "autopost:" + latest.get("id") + ":" + latest.get("version")
                    : "rule:grounded-template-v1";
            db.jdbc.update(
                "INSERT INTO"
                    + " approval(workspace_id,brand_id,revision_id,policy_version,snapshot_hash,actor,decision,valid_until)"
                    + " VALUES(?,?,?,?,?,?,'APPROVED',now()+interval '7 days')",
                workspace,
                brand,
                revision,
                b.get("policy_version"),
                draft.get("snapshot_hash"),
                actor);
            db.audit(workspace, brand, actor, "AUTO_APPROVED", revision);
          }
          boolean paidImage =
              config != null && Set.of("OPENAI", "GOOGLE").contains(config.get("image_mode"));
          budget.settle(runId, paidImage ? -1 : result.cost());
          step(runId, 8, "Viết bản nháp", "DONE");
          step(runId, 9, generated == null ? "Tạo ảnh mẫu" : "Tạo ảnh AI", "DONE");
          step(runId, 10, "Kiểm tra nguồn và rủi ro", "DONE");
          step(runId, 12, "Duyệt nội dung", auto ? "DONE" : "WAITING_REVIEW");
          db.jdbc.update(
              "UPDATE workflow_run SET status=?,step=12,output_revision_id=? WHERE id=?",
              auto ? "COMPLETED" : "WAITING_REVIEW",
              revision,
              runId);
          if (auto && dailyAuto)
            autopost.queue(
                run, db.one("SELECT * FROM content_revision WHERE id=?", revision), current, false);
          jobs.done(job);
          return null;
        });
  }

  private void step(UUID run, int n, String name, String status) {
    db.jdbc.update(
        "INSERT INTO step_run(run_id,step,name,status) VALUES(?,?,?,?) ON CONFLICT(run_id,step) DO"
            + " UPDATE SET status=EXCLUDED.status",
        run,
        n,
        name,
        status);
  }
}

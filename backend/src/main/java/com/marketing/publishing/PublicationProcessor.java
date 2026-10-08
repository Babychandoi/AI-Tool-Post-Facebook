package com.marketing.publishing;

import com.marketing.content.ContentService;
import com.marketing.media.AssetStore;
import com.marketing.operations.*;
import com.marketing.shared.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PublicationProcessor {
  private final Db db;
  private final JobRepository jobs;
  private final PublishingController gate;
  private final ContentService content;
  private final MetaPublisher meta;
  private final TokenVault vault;
  private final Environment env;
  private final AssetStore assets;
  private final com.marketing.identity.ActorPolicy actors;
  private final com.marketing.workflow.AutopostService autopost;

  public PublicationProcessor(
      Db db,
      JobRepository jobs,
      PublishingController gate,
      ContentService content,
      MetaPublisher meta,
      TokenVault vault,
      Environment env,
      AssetStore assets,
      com.marketing.identity.ActorPolicy actors,
      com.marketing.workflow.AutopostService autopost) {
    this.db = db;
    this.jobs = jobs;
    this.gate = gate;
    this.content = content;
    this.meta = meta;
    this.vault = vault;
    this.env = env;
    this.assets = assets;
    this.actors = actors;
    this.autopost = autopost;
  }

  @SuppressWarnings("unchecked")
  public void process(Map<String, Object> job) {
    UUID id = Db.id(job.get("aggregate_id"));
    var p = db.one("SELECT * FROM publication WHERE id=?", id);
    if (!Set.of("QUEUED", "RETRY_WAIT").contains(p.get("status"))) {
      jobs.fenced(
          job,
          () -> {
            jobs.done(job);
            return null;
          });
      return;
    }
    var account = db.one("SELECT * FROM social_account WHERE id=?", p.get("account_id"));
    if ("META".equals(account.get("provider"))) {
      if (!env.getProperty("app.live-publishing", Boolean.class, false))
        throw Problem.conflict(
            "Đăng thật chưa bật. Cấu hình LIVE_PUBLISHING sau khi xác minh Page.");
      meta.verify(
          account.get("remote_id").toString(),
          vault.decrypt(account.get("token_ciphertext").toString()));
    } else if (!env.getProperty("app.demo", Boolean.class, false))
      throw Problem.conflict("Demo adapter chỉ chạy khi DEMO_MODE=true.");
    var snap = (Map<String, Object>) p.get("snapshot");
    try {
      String checksum =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(assets.get(snap.get("asset_key").toString())));
      if (!checksum.equals(snap.get("asset_checksum")))
        throw Problem.conflict("Ảnh không khớp checksum đã duyệt.");
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw Problem.conflict("Không xác minh được ảnh.");
    }
    UUID attempt =
        jobs.fenced(
            job,
            () -> {
              var b = db.one("SELECT * FROM brand WHERE id=? FOR UPDATE", p.get("brand_id"));
              var current = db.one("SELECT * FROM publication WHERE id=? FOR UPDATE", id);
              if (!Set.of("QUEUED", "RETRY_WAIT").contains(current.get("status")))
                throw Problem.conflict("Lịch đã đổi trạng thái.");
              autopost.assertPublication(current, b);
              if (!actors.allowed(
                  Db.id(p.get("workspace_id")),
                  Db.id(p.get("brand_id")),
                  (String) p.get("created_by"),
                  "PUBLISHER")) throw Problem.conflict("Người lập lịch không còn quyền xuất bản.");
              if (!Boolean.TRUE.equals(b.get("enabled")))
                throw Problem.conflict("Thương hiệu đang tạm dừng.");
              if (Instant.parse(p.get("deadline_at").toString()).isBefore(Instant.now()))
                throw Problem.conflict("Lịch vượt deadline; cần chọn slot mới.");
              var r = db.one("SELECT * FROM content_revision WHERE id=?", p.get("revision_id"));
              gate.approval(r, b, Instant.now());
              if (!p.get("snapshot_hash").equals(r.get("snapshot_hash"))
                  || !p.get("policy_version").equals(b.get("policy_version")))
                throw Problem.conflict("Approval snapshot hoặc policy không còn hiệu lực.");
              content.validateSources(
                  Db.id(p.get("workspace_id")),
                  Db.id(p.get("brand_id")),
                  ((List<?>) r.get("source_refs")).stream().map(Db::id).toList());
              db.one(
                  "SELECT id FROM social_account WHERE id=? AND health='CONNECTED'",
                  p.get("account_id"));
              UUID a = UUID.randomUUID();
              db.jdbc.update(
                  "INSERT INTO publication_attempt(id,publication_id,job_id,fencing_token)"
                      + " VALUES(?,?,?,?)",
                  a,
                  id,
                  job.get("id"),
                  job.get("fence"));
              db.jdbc.update(
                  "UPDATE publication SET status='DISPATCHING',version=version+1 WHERE id=?", id);
              return a;
            });
    SocialPublisher.Result result;
    if ("DEMO".equals(account.get("provider")))
      result =
          new SocialPublisher.Result(
              "PUBLISHED", "demo_" + p.get("operation_key"), null, null, 0, "demo-local");
    else result = meta.publish(account, snap, Db.id(p.get("operation_key")));
    SocialPublisher.Result finalResult = result;
    jobs.fenced(
        job,
        () -> {
          String status = finalResult.status();
          if ("RETRY_WAIT".equals(status) && ((Number) job.get("attempts")).intValue() >= 5)
            status = "FAILED";
          db.jdbc.update(
              "UPDATE publication_attempt SET outcome=?,provider_request_id=?,error_code=? WHERE"
                  + " id=?",
              status,
              finalResult.requestId(),
              finalResult.errorCode(),
              attempt);
          db.jdbc.update(
              "UPDATE publication SET"
                  + " status=?,remote_post_id=?,remote_url=?,last_error=?,published_at=CASE WHEN"
                  + " ?='PUBLISHED' THEN now() ELSE NULL END,version=version+1 WHERE id=? AND"
                  + " status='DISPATCHING'",
              status,
              finalResult.remoteId(),
              finalResult.url(),
              finalResult.errorCode(),
              status,
              id);
          if ("RETRY_WAIT".equals(status)) {
            long backoff =
                Math.max(
                    finalResult.retrySeconds(),
                    (1L << Math.min(8, ((Number) job.get("attempts")).intValue())) * 15
                        + new Random().nextInt(10));
            jobs.retry(job, backoff);
          } else jobs.done(job);
          if ("PUBLISHED".equals(status)) {
            for (int hours : List.of(1, 24, 72, 168))
              db.jdbc.update(
                  "INSERT INTO job(workspace_id,brand_id,kind,aggregate_id,due_at)"
                      + " VALUES(?,?,'METRICS',?,now()+(? * interval '1 hour')) ON CONFLICT DO"
                      + " NOTHING",
                  p.get("workspace_id"),
                  p.get("brand_id"),
                  id,
                  hours);
            db.event(
                Db.id(p.get("workspace_id")), Db.id(p.get("brand_id")), "PublicationConfirmed", id);
          } else
            db.event(
                Db.id(p.get("workspace_id")), Db.id(p.get("brand_id")), "Publication" + status, id);
          return null;
        });
  }

  public void metrics(Map<String, Object> job) {
    var p = db.one("SELECT * FROM publication WHERE id=?", job.get("aggregate_id"));
    if (!"PUBLISHED".equals(p.get("status"))) {
      jobs.fenced(
          job,
          () -> {
            jobs.done(job);
            return null;
          });
      return;
    }
    var a = db.one("SELECT * FROM social_account WHERE id=?", p.get("account_id"));
    var metrics = new LinkedHashMap<String, Number>();
    String missing = "DEMO_NO_REAL_METRICS";
    if ("META".equals(a.get("provider"))) {
      metrics.putAll(meta.metrics(a, p.get("remote_post_id").toString()));
      missing = "NOT_AVAILABLE_IN_PROVIDER";
    } else
      for (String n : List.of("reach", "reactions", "comments", "shares")) metrics.put(n, null);
    String reason = missing;
    jobs.fenced(
        job,
        () -> {
          int hours =
              (int)
                  Math.max(
                      0,
                      Duration.between(
                              Instant.parse(p.get("published_at").toString()), Instant.now())
                          .toHours());
          for (var metric : metrics.entrySet())
            db.jdbc.update(
                "INSERT INTO"
                    + " metric_snapshot(workspace_id,brand_id,publication_id,metric_name,value,definition,missing_reason,provider,window_hours)"
                    + " VALUES(?,?,?,?,?,?,?,?,?) ON"
                    + " CONFLICT(publication_id,metric_name,window_hours) DO UPDATE SET"
                    + " value=EXCLUDED.value,missing_reason=EXCLUDED.missing_reason,fetched_at=now()",
                p.get("workspace_id"),
                p.get("brand_id"),
                p.get("id"),
                metric.getKey(),
                metric.getValue(),
                "Facebook Page " + metric.getKey(),
                metric.getValue() == null ? reason : null,
                a.get("provider"),
                hours);
          jobs.done(job);
          db.event(
              Db.id(p.get("workspace_id")),
              Db.id(p.get("brand_id")),
              "MetricsRefreshed",
              Db.id(p.get("id")));
          return null;
        });
  }
}

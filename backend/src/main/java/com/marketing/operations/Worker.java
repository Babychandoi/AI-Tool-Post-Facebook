package com.marketing.operations;

import com.marketing.publishing.PublicationProcessor;
import com.marketing.shared.*;
import com.marketing.workflow.WorkflowProcessor;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class Worker {
  private final Db db;
  private final JobRepository jobs;
  private final Budget budget;
  private final WorkflowProcessor workflow;
  private final PublicationProcessor publisher;
  private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();

  public Worker(
      Db db,
      JobRepository jobs,
      Budget budget,
      WorkflowProcessor workflow,
      PublicationProcessor publisher) {
    this.db = db;
    this.jobs = jobs;
    this.budget = budget;
    this.workflow = workflow;
    this.publisher = publisher;
  }

  @Scheduled(fixedDelay = 1500)
  public void tick() {
    jobs.recover(budget);
    var job = jobs.claim();
    if (job == null) return;
    var heartbeat =
        heartbeats.scheduleAtFixedRate(() -> jobs.heartbeat(job), 20, 20, TimeUnit.SECONDS);
    try {
      switch (job.get("kind").toString()) {
        case "GENERATE" -> workflow.process(job);
        case "PUBLISH" -> publisher.process(job);
        case "METRICS" -> publisher.metrics(job);
        case "WEBHOOK" ->
            jobs.fenced(
                job,
                () -> {
                  db.jdbc.update(
                      "UPDATE inbox_event SET processed_at=now() WHERE id=? AND processed_at IS"
                          + " NULL",
                      job.get("aggregate_id"));
                  jobs.done(job);
                  return null;
                });
        default -> throw new IllegalStateException("Unsupported job");
      }
    } catch (JobRepository.LeaseLost ignored) {
    } catch (Exception e) {
      try {
        jobs.fenced(
            job,
            () -> {
              String message =
                  e instanceof Problem
                      ? e.getMessage()
                      : "Tác vụ chưa hoàn tất. Kiểm tra kết nối và cấu hình provider.";
              jobs.fail(job, message);
              if ("PUBLISH".equals(job.get("kind")))
                db.jdbc.update(
                    "UPDATE publication SET status=CASE WHEN status='DISPATCHING' THEN 'UNKNOWN'"
                        + " ELSE 'BLOCKED' END,last_error=?,version=version+1 WHERE id=? AND status"
                        + " IN ('QUEUED','RETRY_WAIT','DISPATCHING')",
                    message,
                    job.get("aggregate_id"));
              if ("GENERATE".equals(job.get("kind"))) {
                db.jdbc.update(
                    "UPDATE workflow_run SET status='FAILED',error=? WHERE id=?",
                    message,
                    job.get("aggregate_id"));
                budget.settle(Db.id(job.get("aggregate_id")), -1);
              }
              return null;
            });
      } catch (JobRepository.LeaseLost ignored) {
      }
    } finally {
      heartbeat.cancel(false);
    }
  }

  @PreDestroy
  void close() {
    heartbeats.shutdownNow();
  }
}

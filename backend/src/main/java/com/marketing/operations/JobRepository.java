package com.marketing.operations;

import com.marketing.shared.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class JobRepository {
  private final Db db;
  private final TransactionTemplate tx;
  public final String owner = UUID.randomUUID().toString();

  public JobRepository(Db db, org.springframework.transaction.PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
  }

  public Map<String, Object> claim() {
    return tx.execute(
        s -> {
          var rows =
              db.list(
                  "SELECT * FROM job WHERE status='READY' AND due_at<=now() ORDER BY due_at,id FOR"
                      + " UPDATE SKIP LOCKED LIMIT 1");
          if (rows.isEmpty()) return null;
          var j = rows.getFirst();
          return db.one(
              "UPDATE job SET status='RUNNING',owner=?,lease_until=now()+interval '120"
                  + " seconds',fence=fence+1,attempts=attempts+1 WHERE id=? RETURNING *",
              owner,
              j.get("id"));
        });
  }

  public boolean heartbeat(Map<String, Object> j) {
    return db.jdbc.update(
            "UPDATE job SET lease_until=now()+interval '120 seconds' WHERE id=? AND owner=? AND"
                + " fence=? AND status='RUNNING' AND lease_until>now()",
            j.get("id"),
            owner,
            j.get("fence"))
        == 1;
  }

  public <T> T fenced(Map<String, Object> j, Supplier<T> work) {
    return tx.execute(
        s -> {
          var rows =
              db.list(
                  "SELECT id FROM job WHERE id=? AND owner=? AND fence=? AND status='RUNNING' AND"
                      + " lease_until>now() FOR UPDATE",
                  j.get("id"),
                  owner,
                  j.get("fence"));
          if (rows.isEmpty()) throw new LeaseLost();
          return work.get();
        });
  }

  public void done(Map<String, Object> j) {
    db.jdbc.update(
        "UPDATE job SET status='DONE',lease_until=NULL WHERE id=? AND owner=? AND fence=?",
        j.get("id"),
        owner,
        j.get("fence"));
  }

  public void fail(Map<String, Object> j, String error) {
    db.jdbc.update(
        "UPDATE job SET status='FAILED',last_error=?,lease_until=NULL WHERE id=? AND owner=? AND"
            + " fence=?",
        error,
        j.get("id"),
        owner,
        j.get("fence"));
  }

  public void retry(Map<String, Object> j, long seconds) {
    db.jdbc.update(
        "UPDATE job SET status='READY',due_at=now()+(? * interval '1 second'),lease_until=NULL"
            + " WHERE id=? AND owner=? AND fence=?",
        seconds,
        j.get("id"),
        owner,
        j.get("fence"));
  }

  public void recover(Budget budget) {
    tx.executeWithoutResult(
        s -> {
          var expired =
              db.list(
                  "SELECT * FROM job WHERE status='RUNNING' AND lease_until<=now() FOR UPDATE SKIP"
                      + " LOCKED");
          for (var j : expired) {
            if ("PUBLISH".equals(j.get("kind")))
              db.jdbc.update(
                  "UPDATE publication SET status=CASE WHEN status='DISPATCHING' THEN 'UNKNOWN' ELSE"
                      + " status END,last_error='LEASE_EXPIRED',version=version+1 WHERE id=?",
                  j.get("aggregate_id"));
            if ("GENERATE".equals(j.get("kind"))) {
              db.jdbc.update(
                  "UPDATE workflow_run SET status='FAILED',error='Worker mất lease; tạo run mới sau"
                      + " khi kiểm tra usage.' WHERE id=?",
                  j.get("aggregate_id"));
              budget.settle(Db.id(j.get("aggregate_id")), -1);
            }
            db.jdbc.update(
                "UPDATE job SET status=?,last_error='LEASE_EXPIRED',lease_until=NULL,fence=fence+1"
                    + " WHERE id=?",
                "METRICS".equals(j.get("kind")) || "WEBHOOK".equals(j.get("kind"))
                    ? "READY"
                    : "FAILED",
                j.get("id"));
          }
        });
  }

  public static class LeaseLost extends RuntimeException {}
}

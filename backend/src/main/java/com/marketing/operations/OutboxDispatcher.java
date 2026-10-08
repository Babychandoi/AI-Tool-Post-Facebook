package com.marketing.operations;

import com.marketing.shared.Db;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class OutboxDispatcher {
  private final Db db;

  public OutboxDispatcher(Db db) {
    this.db = db;
  }

  @Scheduled(fixedDelay = 2000)
  @Transactional
  public void dispatch() {
    for (var e :
        db.list(
            "SELECT * FROM outbox WHERE delivered_at IS NULL ORDER BY created_at FOR UPDATE SKIP"
                + " LOCKED LIMIT 50")) {
      var envelope = new LinkedHashMap<String, Object>();
      envelope.put("event_id", e.get("id"));
      envelope.put("schema_version", 1);
      envelope.put("type", e.get("event_type"));
      envelope.put("workspace_id", e.get("workspace_id"));
      envelope.put("brand_id", e.get("brand_id"));
      envelope.put("aggregate_id", e.get("aggregate_id"));
      envelope.put("occurred_at", e.get("created_at"));
      db.jdbc.update(
          "INSERT INTO event_receipt(event_id,consumer,envelope)"
              + " VALUES(?,'operations-projection',?::jsonb) ON CONFLICT(event_id) DO NOTHING",
          e.get("id"),
          db.json(envelope));
      db.jdbc.update("UPDATE outbox SET delivered_at=now() WHERE id=?", e.get("id"));
    }
  }
}

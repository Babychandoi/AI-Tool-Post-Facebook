package com.marketing.workflow;

import com.marketing.shared.*;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class AutopostScheduler {
  private final Db db;
  private final AutopostService service;

  public AutopostScheduler(Db db, AutopostService service) {
    this.db = db;
    this.service = service;
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 10000)
  public void tick() {
    for (var c : db.list("SELECT id FROM autopost_config WHERE enabled ORDER BY brand_id")) {
      try {
        service.daily(Db.id(c.get("id")), Instant.now());
      } catch (Problem | org.springframework.dao.DataAccessException e) {
        // Each brand has its own transaction. One missing connection or budget cannot stop others.
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn(
                "Autopost not queued config={} type={}", c.get("id"), e.getClass().getSimpleName());
      }
    }
  }
}

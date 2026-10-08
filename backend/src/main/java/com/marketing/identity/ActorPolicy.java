package com.marketing.identity;

import com.marketing.shared.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ActorPolicy {
  private final Db db;

  public ActorPolicy(Db db) {
    this.db = db;
  }

  public boolean allowed(UUID workspace, UUID brand, String actor, String... roles) {
    if (actor == null) return false;
    var rows =
        db.list(
            "SELECT m.role FROM membership m JOIN app_user u ON u.id=m.user_id WHERE"
                + " m.workspace_id=? AND u.email=? AND u.enabled AND (m.brand_scope IS NULL OR"
                + " ?=ANY(m.brand_scope))",
            workspace,
            actor,
            brand);
    return !rows.isEmpty()
        && (List.of("OWNER", "ADMIN").contains(rows.getFirst().get("role"))
            || Arrays.asList(roles).contains(rows.getFirst().get("role")));
  }
}

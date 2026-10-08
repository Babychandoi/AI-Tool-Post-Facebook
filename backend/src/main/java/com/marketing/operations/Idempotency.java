package com.marketing.operations;

import com.marketing.shared.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Idempotency {
  private final Db db;

  public Idempotency(Db db) {
    this.db = db;
  }

  public UUID find(UUID workspace, String scope, String key, Object request) {
    if (key == null || key.isBlank() || key.length() > 160)
      throw Problem.bad("Cần Idempotency-Key dài tối đa 160 ký tự.");
    db.jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", workspace + ":" + scope + ":" + key);
    var rows =
        db.list(
            "SELECT * FROM idempotency WHERE workspace_id=? AND scope=? AND key=?",
            workspace,
            scope,
            key);
    if (rows.isEmpty()) return null;
    var r = rows.getFirst();
    if (!r.get("request_hash").equals(Db.hash(db.json(request))))
      throw Problem.conflict("Idempotency-Key đã dùng cho yêu cầu khác.");
    return Db.id(r.get("resource_id"));
  }

  public void save(UUID workspace, String scope, String key, Object request, UUID id) {
    db.jdbc.update(
        "INSERT INTO idempotency(workspace_id,scope,key,request_hash,resource_id)"
            + " VALUES(?,?,?,?,?)",
        workspace,
        scope,
        key,
        Db.hash(db.json(request)),
        id);
  }
}

package com.marketing.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {
  public final JdbcTemplate jdbc;
  public final ObjectMapper json;

  public Db(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public List<Map<String, Object>> list(String sql, Object... args) {
    return jdbc.queryForList(sql, args).stream().map(this::normalize).toList();
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = list(sql, args);
    if (rows.isEmpty()) throw Problem.missing();
    return rows.getFirst();
  }

  public String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON");
    }
  }

  public Map<String, Object> map(String text) {
    try {
      return json.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON");
    }
  }

  private Map<String, Object> normalize(Map<String, Object> row) {
    var out = new LinkedHashMap<String, Object>();
    row.forEach(
        (k, v) -> {
          if (v instanceof PGobject p
              && ("jsonb".equals(p.getType()) || "json".equals(p.getType()))) {
            try {
              out.put(k, json.readValue(p.getValue(), Object.class));
            } catch (Exception e) {
              out.put(k, null);
            }
          } else if (v instanceof java.sql.Timestamp t) out.put(k, t.toInstant().toString());
          else out.put(k, v);
        });
    return out;
  }

  public static UUID id(Object v) {
    return UUID.fromString(v.toString());
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void audit(UUID workspace, UUID brand, String actor, String action, UUID aggregate) {
    jdbc.update(
        "INSERT INTO audit_log(workspace_id,brand_id,actor,action,aggregate_id) VALUES(?,?,?,?,?)",
        workspace,
        brand,
        actor,
        action,
        aggregate);
  }

  public void event(UUID workspace, UUID brand, String type, UUID aggregate) {
    jdbc.update(
        "INSERT INTO outbox(workspace_id,brand_id,event_type,aggregate_id) VALUES(?,?,?,?)",
        workspace,
        brand,
        type,
        aggregate);
  }
}

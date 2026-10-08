package com.marketing.knowledge;

import com.marketing.identity.Scope;
import com.marketing.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/brands/{brand}/knowledge")
public class KnowledgeController {
  private final Db db;
  private final Scope scope;

  public KnowledgeController(Db db, Scope scope) {
    this.db = db;
    this.scope = scope;
  }

  public record Import(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 200000) String body,
      @Size(max = 2000) String source_url,
      java.time.Instant effective_until) {}

  @GetMapping
  Object list(@PathVariable UUID brand) {
    var m = scope.current();
    scope.brand(m, brand);
    return db.list(
        "SELECT *, (effective_until IS NOT NULL AND effective_until<=now()) expired FROM"
            + " knowledge_document WHERE brand_id=? AND workspace_id=? ORDER BY created_at DESC"
            + " LIMIT 100",
        brand,
        m.workspace());
  }

  @PostMapping
  @Transactional
  Object create(@PathVariable UUID brand, @Valid @RequestBody Import i) {
    var m = scope.current();
    m.require("MARKETER");
    scope.brand(m, brand);
    if (i.effective_until() != null && !i.effective_until().isAfter(java.time.Instant.now()))
      throw Problem.bad("Ngày hết hiệu lực phải ở tương lai.");
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " knowledge_document(id,workspace_id,brand_id,title,body,source_url,effective_until)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        m.workspace(),
        brand,
        i.title(),
        i.body(),
        i.source_url(),
        i.effective_until() == null ? null : java.sql.Timestamp.from(i.effective_until()));
    String[] paragraphs = i.body().split("\\n\\s*\\n");
    int position = 0;
    for (String p : paragraphs)
      for (int offset = 0; offset < p.length(); offset += 1200)
        db.jdbc.update(
            "INSERT INTO knowledge_chunk(document_id,brand_id,workspace_id,position,body)"
                + " VALUES(?,?,?,?,?)",
            id,
            brand,
            m.workspace(),
            position++,
            p.substring(offset, Math.min(p.length(), offset + 1200)));
    db.audit(m.workspace(), brand, m.email(), "KNOWLEDGE_IMPORTED", id);
    return db.one("SELECT * FROM knowledge_document WHERE id=?", id);
  }

  @PostMapping("/{id}/approve")
  @Transactional
  Object approve(@PathVariable UUID brand, @PathVariable UUID id) {
    var m = scope.current();
    m.require("REVIEWER");
    scope.brand(m, brand);
    if (db.jdbc.update(
            "UPDATE knowledge_document SET status='APPROVED' WHERE id=? AND brand_id=? AND"
                + " workspace_id=? AND (effective_until IS NULL OR effective_until>now()) AND"
                + " status='PENDING'",
            id,
            brand,
            m.workspace())
        != 1) throw Problem.conflict("Nguồn không còn chờ duyệt hoặc đã hết hạn.");
    db.audit(m.workspace(), brand, m.email(), "KNOWLEDGE_APPROVED", id);
    db.event(m.workspace(), brand, "KnowledgeIndexed", id);
    return Map.of("ok", true);
  }
}

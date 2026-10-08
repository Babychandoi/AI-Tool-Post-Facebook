package com.marketing.content;

import com.marketing.media.*;
import com.marketing.shared.*;
import jakarta.validation.constraints.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentService {
  private final Db db;
  private final TemplateRenderer renderer;
  private final AssetStore storage;

  public ContentService(Db db, TemplateRenderer renderer, AssetStore storage) {
    this.db = db;
    this.renderer = renderer;
    this.storage = storage;
  }

  public record Draft(
      @NotBlank @Size(max = 250) String hook,
      @NotBlank @Size(max = 10000) String body,
      @NotNull @Size(max = 1000) String cta,
      @NotNull @Size(max = 30) List<UUID> source_refs) {}

  @Transactional
  public Map<String, Object> create(
      UUID workspace, UUID brand, UUID content, UUID campaign, String actor, Draft d) {
    return create(workspace, brand, content, campaign, actor, d, null);
  }

  @Transactional
  public Map<String, Object> create(
      UUID workspace,
      UUID brand,
      UUID content,
      UUID campaign,
      String actor,
      Draft d,
      AiImageGenerator.Image generated) {
    var b = db.one("SELECT * FROM brand WHERE id=? AND workspace_id=?", brand, workspace);
    validateSources(workspace, brand, d.source_refs());
    if (content == null) {
      content = UUID.randomUUID();
      db.jdbc.update(
          "INSERT INTO content_item(id,workspace_id,brand_id,campaign_id) VALUES(?,?,?,?)",
          content,
          workspace,
          brand,
          campaign);
    }
    db.one(
        "SELECT id FROM content_item WHERE id=? AND brand_id=? AND workspace_id=? FOR UPDATE",
        content,
        brand,
        workspace);
    int no =
        db.jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision_no),0)+1 FROM content_revision WHERE content_id=?",
            Integer.class,
            content);
    byte[] image =
        generated == null
            ? renderer.render(b.get("name").toString(), d.hook(), b.get("color").toString())
            : generated.png();
    String checksum;
    try {
      checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    var brief = new LinkedHashMap<String, Object>();
    if (generated == null) brief.put("template", "EDUCATION_V1");
    else brief.putAll(generated.provenance());
    brief.put("color", b.get("color"));
    brief.put("text", d.hook());
    brief.put("asset_checksum", checksum);
    var risk = risk(d);
    var fields = new LinkedHashMap<String, Object>();
    fields.put("hook", d.hook());
    fields.put("body", d.body());
    fields.put("cta", d.cta());
    fields.put("source_refs", d.source_refs());
    fields.put("creative_brief", brief);
    db.jdbc.update(
        "UPDATE content_revision SET status='ARCHIVED' WHERE content_id=? AND status IN"
            + " ('DRAFT','IN_REVIEW','CHANGES_REQUESTED')",
        content);
    UUID revision = UUID.randomUUID();
    String key = workspace + "/" + brand + "/" + revision + ".png";
    storage.put(key, image);
    db.jdbc.update(
        "INSERT INTO"
            + " content_revision(id,workspace_id,brand_id,content_id,revision_no,hook,body,cta,source_refs,risk_flags,creative_brief,snapshot_hash,created_by)"
            + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?)",
        revision,
        workspace,
        brand,
        content,
        no,
        d.hook(),
        d.body(),
        d.cta(),
        db.json(d.source_refs()),
        db.json(risk),
        db.json(brief),
        Db.hash(db.json(fields)),
        actor);
    db.jdbc.update(
        "INSERT INTO"
            + " asset_version(workspace_id,brand_id,revision_id,storage_key,checksum,width,height,rights)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        workspace,
        brand,
        revision,
        key,
        checksum,
        generated == null ? 1080 : generated.width(),
        generated == null ? 1080 : generated.height(),
        generated == null ? "INTERNAL_TEMPLATE" : "AI_GENERATED");
    db.audit(workspace, brand, actor, "REVISION_CREATED", revision);
    db.event(workspace, brand, "DraftCreated", revision);
    return db.one("SELECT * FROM content_revision WHERE id=?", revision);
  }

  public void validateSources(UUID workspace, UUID brand, List<UUID> refs) {
    for (UUID ref : refs) {
      var docs =
          db.list(
              "SELECT id FROM knowledge_document WHERE id=? AND workspace_id=? AND brand_id=? AND"
                  + " status='APPROVED' AND effective_from<=now() AND (effective_until IS NULL OR"
                  + " effective_until>now())",
              ref,
              workspace,
              brand);
      if (docs.isEmpty())
        throw Problem.bad("Nguồn tham chiếu chưa được duyệt, hết hạn hoặc thuộc thương hiệu khác.");
    }
  }

  public List<String> risk(Draft d) {
    var risk = new ArrayList<String>();
    String text = (d.hook() + " " + d.body() + " " + d.cta()).toLowerCase(Locale.ROOT);
    if (text.matches("(?s).*(pháp lý|luật|tòa án|kiện|hợp đồng|nhãn hiệu).*"))
      risk.add("LEGAL_CONTENT");
    if (text.matches(
        "(?s).*(\\d[\\d.,]*\\s*(đ|vnd|đồng|%|usd)|giảm giá|ưu đãi|cam kết|đảm bảo|chắc chắn).*"))
      risk.add("COMMERCIAL_CLAIM");
    if (d.source_refs().isEmpty()) risk.add("MISSING_EVIDENCE");
    return risk;
  }
}

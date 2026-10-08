package com.marketing.operations;

import com.marketing.content.ContentService;
import com.marketing.shared.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoSeed implements ApplicationRunner {
  private final Db db;
  private final Environment env;
  private final PasswordEncoder passwords;
  private final ContentService contents;

  public DemoSeed(Db db, Environment env, PasswordEncoder passwords, ContentService contents) {
    this.db = db;
    this.env = env;
    this.passwords = passwords;
    this.contents = contents;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!env.getProperty("app.demo", Boolean.class, false)
        || !"api".equals(env.getProperty("app.mode"))) return;
    db.jdbc.queryForList("SELECT pg_advisory_xact_lock(87192831)");
    if (!db.list("SELECT id FROM workspace WHERE name='Marketing Demo'").isEmpty()) return;
    UUID workspace = UUID.randomUUID(),
        brand = UUID.randomUUID(),
        source = UUID.randomUUID(),
        campaign = UUID.randomUUID(),
        account = UUID.randomUUID();
    db.jdbc.update("INSERT INTO workspace(id,name) VALUES(?,'Marketing Demo')", workspace);
    for (String role : List.of("OWNER", "MARKETER", "REVIEWER", "PUBLISHER", "SALES", "ANALYST")) {
      UUID user = UUID.randomUUID();
      String email = role.toLowerCase(Locale.ROOT) + "@demo.local";
      db.jdbc.update(
          "INSERT INTO app_user(id,email,name,password_hash) VALUES(?,?,?,?)",
          user,
          email,
          role.equals("OWNER") ? "Minh Anh" : role,
          passwords.encode(env.getProperty("app.demo-password")));
      db.jdbc.update(
          "INSERT INTO membership(user_id,workspace_id,role) VALUES(?,?,?)", user, workspace, role);
    }
    db.jdbc.update(
        "INSERT INTO brand(id,workspace_id,name,industry,voice,audience,color) VALUES(?,?,'Poip Law"
            + " · Demo','Dịch vụ pháp lý','Chuyên nghiệp, dễ hiểu, gần gũi. Không cam kết kết"
            + " quả.','Chủ doanh nghiệp và người xây dựng thương hiệu','#8b76ef')",
        brand,
        workspace);
    db.jdbc.update(
        "INSERT INTO brand_version(brand_id,workspace_id,version,snapshot) VALUES(?,?,1,?::jsonb)",
        brand,
        workspace,
        db.json(db.one("SELECT * FROM brand WHERE id=?", brand)));
    String body =
        "Poip Law · Demo là thương hiệu minh họa cho hệ thống AI Marketing. Nội dung trong"
            + " workspace này phục vụ kiểm thử, không phải thông tin dịch vụ thực tế.\n\n"
            + "Để chuẩn bị buổi trao đổi, khách hàng có thể ghi lại nhu cầu, bối cảnh và câu hỏi"
            + " muốn tìm hiểu. Đội ngũ tiếp nhận sẽ làm rõ phạm vi trước khi đề xuất bước tiếp"
            + " theo. Không cam kết kết quả hay đưa ra mức phí khi chưa có thông tin được xác"
            + " nhận.";
    db.jdbc.update(
        "INSERT INTO knowledge_document(id,workspace_id,brand_id,title,body,status)"
            + " VALUES(?,?,?,'Giới thiệu thương hiệu & quy trình tiếp nhận',?,'APPROVED')",
        source,
        workspace,
        brand,
        body);
    db.jdbc.update(
        "INSERT INTO knowledge_chunk(document_id,workspace_id,brand_id,position,body)"
            + " VALUES(?,?,?,0,?)",
        source,
        workspace,
        brand,
        body);
    db.jdbc.update(
        "INSERT INTO campaign(id,workspace_id,brand_id,name,objective,pillars) VALUES(?,?,?,'Xây"
            + " dựng niềm tin thương hiệu','Chia sẻ kiến thức và tiếp nhận khách hàng quan"
            + " tâm','[\"Kiến thức thương hiệu\",\"Quy trình tiếp nhận\",\"Câu hỏi thường"
            + " gặp\"]'::jsonb)",
        campaign,
        workspace,
        brand);
    db.jdbc.update(
        "INSERT INTO social_account(id,workspace_id,brand_id,provider,remote_id,name,permissions)"
            + " VALUES(?,?,?,'DEMO','demo-page','Facebook Page thử"
            + " nghiệm','[\"DEMO_ONLY\"]'::jsonb)",
        account,
        workspace,
        brand);
    var d1 =
        contents.create(
            workspace,
            brand,
            null,
            campaign,
            "owner@demo.local",
            new ContentService.Draft(
                "Chuẩn bị gì trước buổi trao đổi đầu tiên?",
                "Ghi lại nhu cầu, bối cảnh và các câu hỏi bạn muốn tìm hiểu. Việc chuẩn bị giúp đội"
                    + " ngũ tiếp nhận làm rõ phạm vi trước khi đề xuất bước tiếp theo.",
                "Lưu lại để chuẩn bị cho cuộc trao đổi sắp tới.",
                List.of(source)));
    var d2 =
        contents.create(
            workspace,
            brand,
            null,
            campaign,
            "owner@demo.local",
            new ContentService.Draft(
                "Bắt đầu từ một câu hỏi rõ ràng",
                "Chia sẻ bối cảnh và điều bạn đang băn khoăn. Đội ngũ tiếp nhận sẽ trao đổi để làm"
                    + " rõ phạm vi hỗ trợ.",
                "Bạn đang quan tâm đến vấn đề nào?",
                List.of(source)));
    db.jdbc.update(
        "UPDATE content_revision SET status='IN_REVIEW' WHERE id IN (?,?)",
        d1.get("id"),
        d2.get("id"));
    contents.create(
        workspace,
        brand,
        null,
        campaign,
        "owner@demo.local",
        new ContentService.Draft(
            "Một cuộc trao đổi hiệu quả bắt đầu từ sự chuẩn bị",
            "Hãy tổng hợp bối cảnh, nhu cầu và câu hỏi trước khi trao đổi với đội ngũ tiếp nhận.",
            "Lưu bài để tham khảo khi cần.",
            List.of(source)));
    for (String stage : List.of("NEW", "QUALIFIED", "CONTACTED")) {
      UUID id = UUID.randomUUID();
      db.jdbc.update(
          "INSERT INTO"
              + " lead(id,workspace_id,brand_id,name,contact,interest,stage,purpose,consent_at,consent_version)"
              + " VALUES(?,?,?,?,?,?,?,'Dữ liệu giả để kiểm thử',now(),'demo-v1')",
          id,
          workspace,
          brand,
          "Khách demo " + stage,
          stage.toLowerCase() + "@example.com",
          "Tìm hiểu quy trình tiếp nhận",
          stage);
    }
    db.audit(workspace, brand, "system", "DEMO_INITIALIZED", brand);
  }
}

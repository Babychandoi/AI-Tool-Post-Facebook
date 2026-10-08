package com.marketing.ai;

import com.marketing.content.ContentService;
import com.marketing.shared.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AiWriter {
  private final Db db;
  private final Environment env;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private final ChatGptConnections connections;
  private final ChatGptTransport chatgpt;

  public AiWriter(
      Db db, Environment env, ChatGptConnections connections, ChatGptTransport chatgpt) {
    this.db = db;
    this.env = env;
    this.connections = connections;
    this.chatgpt = chatgpt;
  }

  public record Result(
      ContentService.Draft draft,
      String provider,
      String model,
      Integer inputTokens,
      Integer outputTokens,
      long cost) {}

  public record Plan(String provider, UUID connection, String model) {
    public boolean chatgpt() {
      return "CHATGPT".equals(provider);
    }
  }

  public Plan plan(UUID workspace, String owner) {
    var account = connections.active(workspace, owner);
    if (account != null) {
      if (!"CONNECTED".equals(account.get("status")))
        throw Problem.conflict(
            "Cần cho phép dùng hạn mức hoặc kết nối lại ChatGPT trước khi tạo nội dung.");
      if (account.get("model") == null)
        throw Problem.conflict("Hãy chọn model trong Kết nối AI trước khi tạo nội dung.");
      return new Plan("CHATGPT", Db.id(account.get("id")), account.get("model").toString());
    }
    if (!env.getProperty("app.openai-key", "").isBlank())
      return new Plan("OPENAI", null, env.getProperty("app.openai-model", ""));
    if (env.getProperty("app.demo", Boolean.class, false))
      return new Plan("DEMO", null, "grounded-template");
    throw Problem.conflict("Hãy kết nối ChatGPT hoặc cấu hình OpenAI API trước khi tạo nội dung.");
  }

  public void pin(UUID run, Plan plan) {
    db.jdbc.update(
        "UPDATE workflow_run SET ai_provider=?,ai_connection_id=?,ai_model=? WHERE id=?",
        plan.provider(),
        plan.connection(),
        plan.model(),
        run);
  }

  public Plan forRun(Map<String, Object> run) {
    if (run.get("ai_provider") == null)
      return plan(Db.id(run.get("workspace_id")), run.get("created_by").toString());
    return new Plan(
        run.get("ai_provider").toString(),
        run.get("ai_connection_id") == null ? null : Db.id(run.get("ai_connection_id")),
        Objects.toString(run.get("ai_model"), ""));
  }

  public Result write(
      Map<String, Object> brand,
      String topic,
      List<Map<String, Object>> evidence,
      Map<String, Object> run) {
    Plan plan = forRun(run);
    if (evidence.isEmpty())
      throw Problem.conflict("Chưa có nguồn kiến thức đã duyệt và còn hiệu lực.");
    if ("DEMO".equals(plan.provider())) {
      var e = evidence.getFirst();
      String text = e.get("body").toString();
      if (text.length() > 1500) text = text.substring(0, 1500);
      return new Result(
          new ContentService.Draft(
              topic,
              "Từ kho kiến thức " + brand.get("name") + ":\n\n" + text,
              "Bạn muốn tìm hiểu thêm? Hãy để lại nhu cầu để đội ngũ hỗ trợ.",
              List.of(Db.id(e.get("id")))),
          "DEMO",
          "grounded-template",
          null,
          null,
          0);
    }
    String key = env.getProperty("app.openai-key", "");
    String model = plan.model();
    if ((!plan.chatgpt() && key.isBlank()) || model.isBlank())
      throw Problem.conflict("Cần cấu hình OPENAI_API_KEY và OPENAI_MODEL.");
    String system;
    Map<String, Object> schema;
    try {
      system =
          new org.springframework.core.io.ClassPathResource("prompts/writer-v1.txt")
                  .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
              + "\nVăn phong: "
              + brand.get("voice")
              + ". Đối tượng: "
              + brand.get("audience");
      schema =
          db.map(
              new org.springframework.core.io.ClassPathResource("prompts/draft-v1.schema.json")
                  .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Prompt registry unavailable");
    }
    var payload =
        Map.of(
            "model",
            model,
            "store",
            false,
            "max_output_tokens",
            2500,
            "input",
            List.of(
                Map.of("role", "system", "content", system),
                Map.of(
                    "role",
                    "user",
                    "content",
                    db.json(Map.of("topic", topic, "EVIDENCE", evidence)))),
            "text",
            Map.of(
                "format",
                Map.of(
                    "type",
                    "json_schema",
                    "name",
                    "facebook_draft_v1",
                    "strict",
                    true,
                    "schema",
                    schema)));
    if (plan.chatgpt()) {
      var direct = new LinkedHashMap<String, Object>(payload);
      direct.remove("max_output_tokens");
      direct.put("stream", true);
      direct.put("instructions", system);
      direct.put(
          "input",
          List.of(
              Map.of(
                  "role",
                  "user",
                  "content",
                  db.json(Map.of("topic", topic, "EVIDENCE", evidence)))));
      payload = direct;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode root;
      if (plan.chatgpt()) {
        UUID workspace = Db.id(run.get("workspace_id"));
        String owner = run.get("created_by").toString();
        var access = connections.access(workspace, owner, plan.connection(), true);
        try {
          root = chatgpt.respond(access.token(), payload);
        } catch (ChatGptTransport.ProviderFailure e) {
          connections.recordFailure(workspace, owner, plan.connection(), e);
          throw ChatGptConnections.providerMessage(e);
        }
      } else {
        var request =
            HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(db.json(payload)))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
          throw new IllegalStateException("OpenAI request failed: HTTP " + response.statusCode());
        root = db.json.readTree(response.body());
      }
      if (!"completed".equals(root.path("status").asText()))
        throw new IllegalStateException("OpenAI output incomplete or refused");
      String output = null;
      for (var item : root.path("output"))
        for (var c : item.path("content"))
          if ("output_text".equals(c.path("type").asText())) output = c.path("text").asText();
      if (output == null) throw new IllegalStateException("OpenAI refused or returned no draft");
      var draft = db.json.readValue(output, ContentService.Draft.class);
      if (draft.hook() == null
          || draft.hook().isBlank()
          || draft.hook().length() > 250
          || draft.body() == null
          || draft.body().isBlank()
          || draft.body().length() > 10000
          || draft.cta() == null
          || draft.cta().length() > 1000
          || draft.source_refs() == null
          || draft.source_refs().isEmpty()
          || draft.source_refs().size() > 30)
        throw new IllegalStateException("Draft schema bounds invalid");
      var allowed = evidence.stream().map(e -> Db.id(e.get("id"))).toList();
      if (!allowed.containsAll(draft.source_refs()))
        throw new IllegalStateException("AI returned unsupported evidence");
      Integer input =
          root.path("usage").path("input_tokens").isInt()
              ? root.path("usage").path("input_tokens").asInt()
              : null;
      Integer out =
          root.path("usage").path("output_tokens").isInt()
              ? root.path("usage").path("output_tokens").asInt()
              : null;
      long inputRate = env.getProperty("app.input-price-micros-per-million", Long.class, 0L),
          outputRate = env.getProperty("app.output-price-micros-per-million", Long.class, 0L);
      long cost =
          (input == null || out == null || inputRate <= 0 || outputRate <= 0)
              ? -1
              : Math.ceilDiv(input * inputRate + out * outputRate, 1000000L);
      return new Result(draft, plan.provider(), model, input, out, plan.chatgpt() ? 0 : cost);
    } catch (Problem e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AI request interrupted");
    } catch (Exception e) {
      throw new IllegalStateException(
          "Không hoàn tất tác vụ AI; xem cấu hình provider và thử lại bằng một run mới.");
    }
  }
}

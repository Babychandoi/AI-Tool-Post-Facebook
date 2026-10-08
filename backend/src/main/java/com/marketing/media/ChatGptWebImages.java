package com.marketing.media;

import com.marketing.shared.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ChatGptWebImages {
  private final Environment env;
  private final Db db;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public ChatGptWebImages(Environment env, Db db) {
    this.env = env;
    this.db = db;
  }

  private URI endpoint(String path) {
    String base = env.getProperty("app.chatgpt-web-image-url", "http://127.0.0.1:58110");
    URI uri = URI.create(base);
    if (!"http".equals(uri.getScheme())
        || !Set.of("127.0.0.1", "localhost", "host.docker.internal", "image-bridge")
            .contains(uri.getHost())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
      throw Problem.conflict("Cầu nối ảnh ChatGPT Web chỉ hỗ trợ localhost hoặc máy host Docker.");
    return URI.create(base.replaceAll("/$", "") + path);
  }

  public Map<String, Object> status() {
    try {
      var request =
          HttpRequest.newBuilder(endpoint("/health")).timeout(Duration.ofSeconds(3)).GET().build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      var data = db.map(response.body());
      if (response.statusCode() != 200
          || !"marketing-chatgpt-web-images".equals(data.get("service"))) throw new IOException();
      boolean ready = Boolean.TRUE.equals(data.get("ready"));
      String mode = Objects.toString(data.get("mode"), "DEDICATED_BROWSER");
      boolean extension = "BROWSER_EXTENSION".equals(mode);
      return Map.of(
          "ready",
          ready,
          "mode",
          mode,
          "paired",
          Boolean.TRUE.equals(data.get("paired")),
          "connected",
          Boolean.TRUE.equals(data.get("connected")),
          "extension_id",
          Objects.toString(data.get("extension_id"), ""),
          "message",
          ready
              ? extension
                  ? "Cốc Cốc đã kết nối. Ứng dụng tự tạo và lấy ảnh ChatGPT."
                  : "ChatGPT Web sẵn sàng tạo ảnh thử nghiệm"
              : extension
                  ? "QUOTA".equals(data.get("code"))
                      ? "ChatGPT đã hết lượt tạo ảnh. Chờ hạn mức đặt lại hoặc đổi tài khoản trong Cốc Cốc."
                      : Boolean.TRUE.equals(data.get("paired"))
                      ? "Mở Cốc Cốc và đăng nhập ChatGPT để tiện ích kết nối lại."
                      : "Ghép nối tiện ích Cốc Cốc để ứng dụng tự tạo và lấy ảnh."
                  : "Đăng nhập ChatGPT trong cửa sổ cầu nối để tạo ảnh.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Map.of("ready", false, "message", "Kiểm tra lại kết nối cầu nối ChatGPT Web.");
    } catch (Exception e) {
      return Map.of(
          "ready",
          false,
          "message",
          "Chạy scripts/run-chatgpt-web-images.sh trên máy đang chạy backend.");
    }
  }

  private String token() throws IOException {
    String token = env.getProperty("app.chatgpt-web-image-token", "");
    if (token.isBlank())
      token =
          Files.readString(
                  Path.of(
                      env.getProperty(
                          "app.chatgpt-web-image-token-file", ".runtime/chatgpt-web-images/token")))
              .trim();
    if (token.length() < 32) throw new IOException();
    return token;
  }

  public Map<String, Object> pairing() {
    try {
      var request =
          HttpRequest.newBuilder(endpoint("/connector/pairing"))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Bearer " + token())
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IOException();
      var data = db.map(response.body());
      String code = Objects.toString(data.get("code"), "");
      if (!code.matches("\\d{6}")) throw new IOException();
      return Map.of(
          "code",
          code,
          "expires_in",
          120,
          "extension_id",
          Objects.toString(data.get("extension_id"), ""));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Problem.conflict("Kết nối tiện ích đã dừng.");
    } catch (Exception e) {
      throw Problem.conflict(
          "Chưa tạo được mã kết nối. Kiểm tra cầu nối ảnh đang chạy và không có ảnh đang tạo.");
    }
  }

  public AiImageGenerator.Image generate(String prompt) {
    try {
      String token = token();
      var request =
          HttpRequest.newBuilder(endpoint("/v1/images/generations"))
              .timeout(Duration.ofSeconds(310))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(db.json(Map.of("prompt", prompt))))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      var timer = Executors.newSingleThreadScheduledExecutor();
      try (var body = response.body()) {
        timer.schedule(
            () -> {
              try {
                body.close();
              } catch (IOException ignored) {
              }
            },
            310,
            TimeUnit.SECONDS);
        byte[] bytes = body.readNBytes(16 * 1024 * 1024 + 1);
        if (bytes.length > 16 * 1024 * 1024)
          throw Problem.conflict("Ảnh ChatGPT Web vượt giới hạn phản hồi.");
        var data = db.json.readTree(bytes);
        if (response.statusCode() != 200) {
          String code = data.path("error").path("code").asText();
          throw Problem.conflict(
              switch (code) {
                case "LOGIN_REQUIRED" ->
                    "Mở Cốc Cốc và đăng nhập ChatGPT để tiện ích tạo ảnh.";
                case "UNAUTHORIZED" -> "Token cầu nối không khớp. Kiểm tra cấu hình local.";
                case "BUSY" -> "Một ảnh ChatGPT Web đang được tạo. Đợi hoàn tất trước khi thử lại.";
                case "QUOTA" -> "ChatGPT báo hết hạn mức tạo ảnh.";
                case "TIMEOUT" ->
                    "Chưa nhận được ảnh sau 5 phút. Kiểm tra ChatGPT trước khi thử lại.";
                case "UI_CHANGED" ->
                    "Tiện ích chưa thao tác được giao diện ChatGPT. Kiểm tra tab ảnh được giữ lại.";
                case "DOWNLOAD_FAILED", "INVALID_IMAGE" ->
                    "ChatGPT đã phản hồi nhưng tiện ích chưa lấy được ảnh hợp lệ. Kiểm tra tab ảnh.";
                case "INTERRUPTED" ->
                    "Kết nối tạo ảnh bị ngắt. Kiểm tra ChatGPT trước khi tạo lại để tránh trùng yêu cầu.";
                default ->
                    "Chưa lấy được ảnh ChatGPT Web. Kiểm tra kết nối tiện ích trong Cốc Cốc.";
              });
        }
        String base64 = data.path("data").path(0).path("b64_json").asText();
        return normalize(
            Base64.getDecoder().decode(base64),
            Map.of(
                "provider",
                "CHATGPT_WEB",
                "generation_mode",
                data.path("generation_mode").asText("DEDICATED_BROWSER"),
                "model",
                "chatgpt-web-image",
                "billing",
                "CHATGPT_PLAN",
                "usage_status",
                "ACCOUNT_QUOTA_UNKNOWN",
                "prompt_hash",
                Db.hash(prompt),
                "experimental",
                true));
      } finally {
        timer.shutdownNow();
      }
    } catch (Problem e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Problem.conflict(
          "Tạo ảnh ChatGPT Web bị ngắt. Kiểm tra ảnh trong ChatGPT trước khi thử lại.");
    } catch (Exception e) {
      throw Problem.conflict(
          "Cầu nối ảnh chưa sẵn sàng. Mở ứng dụng, Cốc Cốc và kiểm tra kết nối ảnh.");
    }
  }

  // ChatGPT Web chooses its own dimensions. Fit the whole image into the app's square PNG format.
  public static AiImageGenerator.Image normalize(byte[] bytes, Map<String, Object> provenance) {
    if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024)
      throw Problem.conflict("Ảnh ChatGPT Web không hợp lệ hoặc vượt 10 MB.");
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new IOException();
      var reader = readers.next();
      try {
        reader.setInput(stream);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (!Set.of("png", "jpeg", "jpg").contains(format)
            || width < 256
            || height < 256
            || (long) width * height > 16_000_000) throw new IOException();
        var source = reader.read(0);
        var square = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        var g = square.createGraphics();
        try {
          g.setColor(Color.WHITE);
          g.fillRect(0, 0, 1024, 1024);
          g.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          double ratio = Math.min(1024.0 / width, 1024.0 / height);
          int w = (int) Math.round(width * ratio), h = (int) Math.round(height * ratio);
          g.drawImage(source, (1024 - w) / 2, (1024 - h) / 2, w, h, null);
        } finally {
          g.dispose();
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(square, "png", out);
        return AiImageGenerator.validate(out.toByteArray(), provenance);
      } finally {
        reader.dispose();
      }
    } catch (Exception e) {
      throw Problem.conflict("Ảnh ChatGPT Web bị hỏng hoặc sai định dạng PNG/JPEG.");
    }
  }
}

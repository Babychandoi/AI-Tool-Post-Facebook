package com.marketing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketing.media.ChatGptWebImages;
import com.marketing.shared.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ChatGptWebImagesTest {
  private byte[] image() throws Exception {
    var image = new BufferedImage(512, 768, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(0, 0, 512, 768);
    g.dispose();
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "jpeg", out);
    return out.toByteArray();
  }

  @Test
  void fitsPortraitDownloadIntoSquareWithoutCroppingAndRejectsInvalidData() throws Exception {
    var result = ChatGptWebImages.normalize(image(), Map.of("provider", "CHATGPT_WEB"));
    var png = ImageIO.read(new ByteArrayInputStream(result.png()));
    assertEquals(1024, png.getWidth());
    assertEquals(1024, png.getHeight());
    assertEquals(Color.WHITE.getRGB(), png.getRGB(0, 512));
    assertTrue(new Color(png.getRGB(512, 512)).getRed() > 200);
    assertThrows(Problem.class, () -> ChatGptWebImages.normalize(new byte[] {1, 2, 3}, Map.of()));
    assertThrows(
        Problem.class, () -> ChatGptWebImages.normalize(new byte[11 * 1024 * 1024], Map.of()));
  }

  @Test
  void bridgeContractUsesItsOwnTokenAndRecordsPlanUsageWithoutApiCharges() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var json = new ObjectMapper();
    byte[] image = image();
    var observed = new java.util.concurrent.atomic.AtomicReference<String>();
    server.createContext(
        "/v1/images/generations",
        exchange -> {
          observed.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              json.writeValueAsBytes(
                  Map.of(
                      "data",
                      java.util.List.of(
                          Map.of("b64_json", Base64.getEncoder().encodeToString(image)))));
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var env =
          new MockEnvironment()
              .withProperty(
                  "app.chatgpt-web-image-url", "http://127.0.0.1:" + server.getAddress().getPort())
              .withProperty(
                  "app.chatgpt-web-image-token", "fixture-bridge-token-with-32-characters");
      var result = new ChatGptWebImages(env, new Db(null, json)).generate("fixture prompt");
      assertEquals("Bearer fixture-bridge-token-with-32-characters", observed.get());
      assertEquals("CHATGPT_PLAN", result.provenance().get("billing"));
      assertEquals("ACCOUNT_QUOTA_UNKNOWN", result.provenance().get("usage_status"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsNonLocalBridgeAndReportsMissingBridgeWithoutExposingSecrets() {
    var env =
        new MockEnvironment()
            .withProperty("app.chatgpt-web-image-url", "https://example.com")
            .withProperty("app.chatgpt-web-image-token", "fixture-bridge-token-with-32-characters");
    var bridge = new ChatGptWebImages(env, new Db(null, new ObjectMapper()));
    assertFalse((Boolean) bridge.status().get("ready"));
    assertThrows(Problem.class, () -> bridge.generate("fixture prompt"));
  }
}

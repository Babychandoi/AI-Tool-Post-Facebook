package com.marketing.media;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class TemplateRenderer {
  public byte[] render(String brand, String hook, String color) {
    try {
      BufferedImage image = new BufferedImage(1080, 1080, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      Color primary = Color.decode(color);
      g.setColor(new Color(20, 24, 40));
      g.fillRect(0, 0, 1080, 1080);
      g.setColor(primary);
      g.fillOval(550, -420, 950, 950);
      g.setColor(new Color(255, 255, 255, 15));
      g.fillOval(-350, 700, 800, 800);
      g.setColor(Color.WHITE);
      g.setFont(new Font("SansSerif", Font.BOLD, 28));
      String brandText = brand.toUpperCase(java.util.Locale.ROOT);
      while (g.getFontMetrics().stringWidth(brandText) > 900 && brandText.length() > 1)
        brandText = brandText.substring(0, brandText.length() - 2) + "…";
      g.drawString(brandText, 80, 115);
      g.setFont(new Font("SansSerif", Font.PLAIN, 22));
      g.drawString("GÓC CHIA SẺ", 80, 310);
      int size = 70;
      ArrayList<String> lines;
      do {
        g.setFont(new Font("SansSerif", Font.BOLD, size));
        lines = wrap(hook, g.getFontMetrics(), 900);
        if (lines.size() <= 6) break;
        size -= 3;
      } while (size > 35);
      int y = 415;
      for (String line : lines) {
        g.drawString(line, 80, y);
        y += size + 20;
      }
      g.setColor(primary);
      g.fillRoundRect(80, 910, 70, 8, 8, 8);
      g.setColor(Color.LIGHT_GRAY);
      g.setFont(new Font("SansSerif", Font.PLAIN, 24));
      g.drawString("KIẾN THỨC HỮU ÍCH • GIÁ TRỊ BỀN VỮNG", 80, 990);
      g.dispose();
      var out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Template rendering failed", e);
    }
  }

  private ArrayList<String> wrap(String text, FontMetrics metrics, int width) {
    var lines = new ArrayList<String>();
    String current = "";
    for (String original : text.split("\\s+")) {
      String word = original;
      while (metrics.stringWidth(word) > width) {
        int cut = word.length() - 1;
        while (cut > 1 && metrics.stringWidth(word.substring(0, cut)) > width) cut--;
        if (!current.isEmpty()) {
          lines.add(current);
          current = "";
        }
        lines.add(word.substring(0, cut));
        word = word.substring(cut);
      }
      String candidate = current.isEmpty() ? word : current + " " + word;
      if (metrics.stringWidth(candidate) > width && !current.isEmpty()) {
        lines.add(current);
        current = word;
      } else current = candidate;
    }
    if (!current.isEmpty()) lines.add(current);
    return lines;
  }
}

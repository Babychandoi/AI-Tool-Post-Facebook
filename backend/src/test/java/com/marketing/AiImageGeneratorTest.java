package com.marketing;

import static org.junit.jupiter.api.Assertions.*;

import com.marketing.media.AiImageGenerator;
import com.marketing.shared.Problem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AiImageGeneratorTest {
  private byte[] image(String format, int size) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), format, out);
    return out.toByteArray();
  }

  @Test
  void acceptsPngAndKeepsExactBytesAndProvenance() throws Exception {
    byte[] png = image("png", 1024);
    var result = AiImageGenerator.validate(png, Map.of("provider", "OPENAI_IMAGES"));
    assertEquals(1024, result.width());
    assertEquals(1024, result.height());
    assertArrayEquals(png, result.png());
    assertEquals("OPENAI_IMAGES", result.provenance().get("provider"));
  }

  @Test
  void rejectsWrongFormatDimensionsAndTruncatedData() throws Exception {
    assertThrows(Problem.class, () -> AiImageGenerator.validate(image("jpg", 1024), Map.of()));
    assertThrows(Problem.class, () -> AiImageGenerator.validate(image("png", 1080), Map.of()));
    assertThrows(
        Problem.class,
        () ->
            AiImageGenerator.validate(
                new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, Map.of()));
    assertThrows(
        Problem.class, () -> AiImageGenerator.validate(new byte[11 * 1024 * 1024], Map.of()));
  }
}

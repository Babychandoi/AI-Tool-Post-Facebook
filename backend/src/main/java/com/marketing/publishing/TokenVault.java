package com.marketing.publishing;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class TokenVault {
  private final Environment env;

  public TokenVault(Environment env) {
    this.env = env;
  }

  private SecretKeySpec key() {
    try {
      byte[] key = Base64.getDecoder().decode(env.getProperty("app.encryption-key", ""));
      if (key.length != 32) throw new IllegalArgumentException();
      return new SecretKeySpec(key, "AES");
    } catch (Exception e) {
      throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must be a base64 32-byte key");
    }
  }

  public String encrypt(String text) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      byte[] cipher = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + cipher.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(cipher, 0, out, iv.length, cipher.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encrypt provider token");
    }
  }

  public String decrypt(String text) {
    try {
      byte[] all = Base64.getDecoder().decode(text);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(all, 0, 12)));
      return new String(c.doFinal(Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot decrypt provider token");
    }
  }
}

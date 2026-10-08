package com.marketing.operations;

import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupGuard implements ApplicationRunner {
  private final Environment env;

  public StartupGuard(Environment env) {
    this.env = env;
  }

  @Override
  public void run(ApplicationArguments args) {
    if ("test".equals(env.getProperty("app.mode"))) return;
    boolean demo = env.getProperty("app.demo", Boolean.class, false);
    if (!demo
        && "api".equals(env.getProperty("app.mode"))
        && !Arrays.asList(env.getActiveProfiles()).contains("oidc"))
      throw new IllegalStateException(
          "Production API requires the oidc profile and provisioned memberships");
    if (env.getProperty("app.live-publishing", Boolean.class, false)) {
      String key = env.getProperty("app.encryption-key", "");
      if (Base64.getDecoder().decode(key).length != 32)
        throw new IllegalStateException("Live publishing requires TOKEN_ENCRYPTION_KEY");
      for (String property : List.of("app.meta-version", "app.meta-app-id", "app.meta-app-secret"))
        if (env.getProperty(property, "").isBlank())
          throw new IllegalStateException("Live publishing requires " + property);
    }
  }
}

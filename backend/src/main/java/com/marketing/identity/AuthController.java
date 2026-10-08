package com.marketing.identity;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
  private final Scope scope;
  private final Environment env;

  public AuthController(Scope scope, Environment env) {
    this.scope = scope;
    this.env = env;
  }

  @GetMapping("/csrf")
  Object csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "header", token.getHeaderName());
  }

  @GetMapping("/config")
  Object config() {
    return Map.of(
        "demo",
        env.getProperty("app.demo", Boolean.class, false),
        "oidc",
        java.util.Arrays.asList(env.getActiveProfiles()).contains("oidc"));
  }

  @GetMapping("/me")
  Object me() {
    return scope.current();
  }
}

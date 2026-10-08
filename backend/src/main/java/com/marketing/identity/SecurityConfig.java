package com.marketing.identity;

import com.marketing.shared.Db;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(Db db, Environment env) {
    return email -> {
      if (!env.getProperty("app.demo", Boolean.class, false))
        throw new UsernameNotFoundException("Local login disabled");
      var rows =
          db.list(
              "SELECT * FROM app_user WHERE email=? AND enabled=true AND password_hash IS NOT NULL",
              email);
      if (rows.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
      var u = rows.getFirst();
      return User.withUsername(email)
          .password(u.get("password_hash").toString())
          .roles("USER")
          .build();
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, Environment env) throws Exception {
    var handler = new CsrfTokenRequestAttributeHandler();
    handler.setCsrfRequestAttributeName(null);
    http.csrf(
            c ->
                c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(handler)
                    .ignoringRequestMatchers("/v1/webhooks/meta", "/v1/forms/**"))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health/**",
                        "/v1/auth/csrf",
                        "/v1/auth/config",
                        "/v1/webhooks/meta",
                        "/v1/auth/login",
                        "/v1/forms/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, err) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Vui lòng đăng"
                                      + " nhập.\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, err) -> {
                          res.setStatus(403);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\"FORBIDDEN\",\"message\":\"Bạn không có quyền hoặc"
                                      + " phiên CSRF đã hết hạn.\"}");
                        }))
        .logout(
            l ->
                l.logoutUrl("/v1/auth/logout")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
    if (env.getProperty("app.demo", Boolean.class, false))
      http.formLogin(
          f ->
              f.loginProcessingUrl("/v1/auth/login")
                  .successHandler(
                      (req, res, auth) -> {
                        res.setContentType("application/json");
                        res.getWriter().write("{\"ok\":true}");
                      })
                  .failureHandler(
                      (req, res, e) -> {
                        res.setStatus(401);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"message\":\"Email hoặc mật khẩu không đúng.\"}");
                      }));
    if (java.util.Arrays.asList(env.getActiveProfiles()).contains("oidc"))
      http.oauth2Login(o -> o.defaultSuccessUrl("/", true));
    return http.build();
  }
}

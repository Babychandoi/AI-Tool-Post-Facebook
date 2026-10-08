package com.marketing.operations;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "app.rate-limit-backend", havingValue = "memory")
public class LocalRateLimitFilter extends OncePerRequestFilter {
  private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
  private long minute = System.currentTimeMillis() / 60000;

  public LocalRateLimitFilter(Environment env) {
    if (!env.getProperty("app.demo", Boolean.class, false))
      throw new IllegalStateException("In-memory rate limit is restricted to demo mode");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest r) {
    return !"POST".equals(r.getMethod())
        || !(r.getRequestURI().equals("/v1/auth/login")
            || r.getRequestURI().startsWith("/v1/forms/")
            || r.getRequestURI().equals("/v1/webhooks/meta"));
  }

  private synchronized int count(String key) {
    long current = System.currentTimeMillis() / 60000;
    if (current != minute) {
      counts.clear();
      minute = current;
    }
    return counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (count(req.getRemoteAddr() + req.getRequestURI()) > 30) {
      res.setStatus(429);
      res.setHeader("Retry-After", "60");
      res.setContentType("application/json");
      res.getWriter().write("{\"message\":\"Quá nhiều yêu cầu. Thử lại sau một phút.\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}

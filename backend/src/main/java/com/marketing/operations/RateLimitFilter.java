package com.marketing.operations;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.rate-limit-backend",
    havingValue = "redis",
    matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {
  private final StringRedisTemplate redis;
  private final DefaultRedisScript<Long> script =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end;"
              + " return n",
          Long.class);

  public RateLimitFilter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest r) {
    return !"POST".equals(r.getMethod())
        || !(r.getRequestURI().equals("/v1/auth/login")
            || r.getRequestURI().startsWith("/v1/forms/")
            || r.getRequestURI().equals("/v1/webhooks/meta"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    try {
      Long n =
          redis.execute(script, List.of("rate:" + req.getRemoteAddr() + ":" + req.getRequestURI()));
      long limit = req.getRequestURI().equals("/v1/webhooks/meta") ? 300 : 30;
      if (n != null && n > limit) {
        res.setStatus(429);
        res.setHeader("Retry-After", "60");
        res.setContentType("application/json");
        res.getWriter().write("{\"message\":\"Quá nhiều yêu cầu. Thử lại sau một phút.\"}");
        return;
      }
    } catch (Exception e) {
      res.setStatus(503);
      res.setContentType("application/json");
      res.getWriter().write("{\"message\":\"Bộ giới hạn yêu cầu chưa sẵn sàng.\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}

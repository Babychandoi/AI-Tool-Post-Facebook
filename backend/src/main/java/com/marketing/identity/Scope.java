package com.marketing.identity;

import com.marketing.shared.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class Scope {
  private final Db db;

  public Scope(Db db) {
    this.db = db;
  }

  public record Member(UUID workspace, String email, String name, String role, List<UUID> brands) {
    public boolean allBrands() {
      return brands == null;
    }

    public void require(String... roles) {
      if (!role.equals("OWNER") && !role.equals("ADMIN") && !Arrays.asList(roles).contains(role))
        throw new Problem(
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "Vai trò hiện tại không được thực hiện thao tác này.");
    }
  }

  public Member current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated())
      throw new Problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập.");
    String email = auth.getName();
    if (auth.getPrincipal() instanceof OidcUser user) {
      if (!Boolean.TRUE.equals(user.getEmailVerified()))
        throw new Problem(HttpStatus.FORBIDDEN, "EMAIL_UNVERIFIED", "Email chưa được xác minh.");
      email = user.getEmail();
    }
    var row =
        db.one(
            "SELECT m.workspace_id,m.role,m.brand_scope,u.email,u.name FROM membership m JOIN"
                + " app_user u ON u.id=m.user_id WHERE u.email=? AND u.enabled=true ORDER BY"
                + " m.workspace_id LIMIT 1",
            email);
    List<UUID> brands = null;
    if (row.get("brand_scope") instanceof java.sql.Array array) {
      try {
        brands = Arrays.stream((Object[]) array.getArray()).map(Db::id).toList();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    return new Member(
        Db.id(row.get("workspace_id")),
        email,
        row.get("name").toString(),
        row.get("role").toString(),
        brands);
  }

  public Map<String, Object> brand(Member m, UUID id) {
    if (!m.allBrands() && !m.brands().contains(id)) throw Problem.missing();
    return db.one("SELECT * FROM brand WHERE id=? AND workspace_id=?", id, m.workspace());
  }
}

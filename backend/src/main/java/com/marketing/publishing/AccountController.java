package com.marketing.publishing;

import com.marketing.identity.Scope;
import com.marketing.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/brands/{brand}/accounts")
public class AccountController {
  private final Db db;
  private final Scope scope;
  private final MetaPublisher meta;
  private final TokenVault vault;

  public AccountController(Db db, Scope scope, MetaPublisher meta, TokenVault vault) {
    this.db = db;
    this.scope = scope;
    this.meta = meta;
    this.vault = vault;
  }

  public record Connect(@NotBlank String page_id, @NotBlank @Size(max = 5000) String page_token) {}

  @GetMapping
  Object list(@PathVariable UUID brand) {
    var m = scope.current();
    scope.brand(m, brand);
    return db.list(
        "SELECT id,brand_id,provider,remote_id,name,health,permissions,created_at FROM"
            + " social_account WHERE brand_id=? AND workspace_id=? ORDER BY created_at",
        brand,
        m.workspace());
  }

  @PostMapping
  @Transactional
  Object connect(@PathVariable UUID brand, @Valid @RequestBody Connect c) {
    var m = scope.current();
    m.require();
    scope.brand(m, brand);
    var verified = meta.verify(c.page_id(), c.page_token());
    UUID id = UUID.randomUUID();
    var rows =
        db.list(
            "INSERT INTO"
                + " social_account(id,workspace_id,brand_id,provider,remote_id,name,token_ciphertext,permissions)"
                + " VALUES(?,?,?,'META',?,?,?,?::jsonb) ON"
                + " CONFLICT(workspace_id,provider,remote_id) DO UPDATE SET"
                + " token_ciphertext=EXCLUDED.token_ciphertext,permissions=EXCLUDED.permissions,health='CONNECTED'"
                + " WHERE social_account.brand_id=EXCLUDED.brand_id RETURNING id",
            id,
            m.workspace(),
            brand,
            c.page_id(),
            verified.get("name"),
            vault.encrypt(c.page_token()),
            db.json(verified.get("permissions")));
    if (rows.isEmpty()) throw Problem.conflict("Page đã được kết nối với thương hiệu khác.");
    id = Db.id(rows.getFirst().get("id"));
    db.audit(m.workspace(), brand, m.email(), "META_CONNECTED", id);
    return Map.of("id", id, "health", "CONNECTED");
  }
}

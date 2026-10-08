package com.marketing.operations;

import com.marketing.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Budget {
  private final Db db;

  public Budget(Db db) {
    this.db = db;
  }

  @Transactional
  public void reserve(UUID workspace, UUID brand, UUID run, long amount) {
    if (amount <= 0) throw Problem.bad("Khoản giữ ngân sách phải lớn hơn 0.");
    var b =
        db.one(
            "SELECT monthly_budget_micros FROM brand WHERE id=? AND workspace_id=? FOR UPDATE",
            brand,
            workspace);
    db.jdbc.update(
        "INSERT INTO budget_month(workspace_id,brand_id,month)"
            + " VALUES(?,?,date_trunc('month',now())::date) ON CONFLICT DO NOTHING",
        workspace,
        brand);
    int n =
        db.jdbc.update(
            "UPDATE budget_month SET reserved_micros=reserved_micros+? WHERE brand_id=? AND"
                + " month=date_trunc('month',now())::date AND spent_micros+reserved_micros+?<=?",
            amount,
            brand,
            amount,
            b.get("monthly_budget_micros"));
    if (n != 1) throw Problem.conflict("Ngân sách tháng không đủ để giữ hạn mức cho tác vụ này.");
    db.jdbc.update(
        "INSERT INTO budget_ledger(workspace_id,brand_id,run_id,month,reserved_micros)"
            + " VALUES(?,?,?,date_trunc('month',now())::date,?)",
        workspace,
        brand,
        run,
        amount);
  }

  @Transactional
  public void settle(UUID run, long charge) {
    var rows =
        db.list("SELECT * FROM budget_ledger WHERE run_id=? AND status='RESERVED' FOR UPDATE", run);
    if (rows.isEmpty()) return;
    var l = rows.getFirst();
    long reserve = ((Number) l.get("reserved_micros")).longValue();
    long cost = charge < 0 ? reserve : charge;
    db.jdbc.update(
        "UPDATE budget_month SET reserved_micros=reserved_micros-?,spent_micros=spent_micros+?"
            + " WHERE brand_id=? AND month=?",
        reserve,
        cost,
        l.get("brand_id"),
        l.get("month"));
    db.jdbc.update(
        "UPDATE budget_ledger SET status='SETTLED',charged_micros=? WHERE run_id=?", cost, run);
  }
}

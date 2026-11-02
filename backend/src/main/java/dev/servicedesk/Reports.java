package dev.servicedesk;

import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class Reports {
  private final JdbcTemplate db;
  private final DeskController desk;
  private final Access access;

  public Reports(JdbcTemplate db, DeskController desk, Access access) {
    this.db = db;
    this.desk = desk;
    this.access = access;
  }

  @GetMapping("/api/tenants/{tenant}/sla-report")
  public Map<String, Object> report(@PathVariable UUID tenant, Principal p) {
    access.admin(desk.role(tenant, desk.user(p)));
    return Map.of(
        "response",
            db.queryForMap(
                "SELECT count(*) FILTER(WHERE responded_at IS NOT NULL) AS completed,count(*)"
                    + " FILTER(WHERE responded_at IS NOT NULL AND responded_at<=response_due AND"
                    + " NOT response_breached) AS within_target,count(*) FILTER(WHERE responded_at"
                    + " IS NULL AND response_due<now() AND status<>'CANCELLED') AS active_overdue"
                    + " FROM ticket WHERE tenant_id=?",
                tenant),
        "resolution",
            db.queryForMap(
                "SELECT count(*) AS completed,count(*) FILTER(WHERE NOT breached) AS within_target"
                    + " FROM (SELECT breached FROM resolution_cycle_history WHERE tenant_id=? UNION"
                    + " ALL SELECT (resolution_breached OR resolved_at>resolution_due) AS breached"
                    + " FROM ticket WHERE tenant_id=? AND resolved_at IS NOT NULL) cycles",
                tenant,
                tenant),
        "active",
            db.queryForMap(
                "SELECT count(*) FILTER(WHERE assignee_id IS NULL) AS unassigned,count(*)"
                    + " FILTER(WHERE paused_at IS NULL AND resolution_due<now()) AS"
                    + " overdue_resolution FROM ticket WHERE tenant_id=? AND status NOT IN"
                    + " ('RESOLVED','CLOSED','CANCELLED')",
                tenant),
        "workload",
            db.queryForList(
                "SELECT u.display_name,count(t.id) AS tickets FROM ticket t JOIN app_user u ON"
                    + " u.id=t.assignee_id WHERE t.tenant_id=? AND t.status NOT IN"
                    + " ('RESOLVED','CLOSED','CANCELLED') GROUP BY u.id,u.display_name ORDER BY"
                    + " tickets DESC",
                tenant));
  }
}

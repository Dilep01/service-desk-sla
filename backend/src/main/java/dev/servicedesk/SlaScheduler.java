package dev.servicedesk;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SlaScheduler {
  private final JdbcTemplate db;

  public SlaScheduler(JdbcTemplate db) {
    this.db = db;
  }

  @Scheduled(fixedDelay = 30000)
  @Transactional
  public void scan() {
    db.queryForObject("SELECT set_config('desk.worker','on',true)", String.class);
    for (String metric : new String[] {"response", "resolution"}) {
      String completion = metric.equals("response") ? "responded_at" : "resolved_at";
      String condition = metric.equals("resolution") ? " AND paused_at IS NULL" : "";
      var warnings =
          db.queryForList(
              "UPDATE ticket SET "
                  + metric
                  + "_warned=TRUE FROM tenant WHERE ticket.tenant_id=tenant.id AND NOT "
                  + metric
                  + "_warned AND "
                  + completion
                  + " IS NULL AND "
                  + metric
                  + "_due>now() AND "
                  + metric
                  + "_due<=now()+tenant.warning_minutes*interval '1 minute' AND ticket.status NOT"
                  + " IN ('CANCELLED','CLOSED','RESOLVED')"
                  + condition
                  + " RETURNING ticket.id,ticket.tenant_id,ticket.assignee_id,ticket.requester_id");
      for (var t : warnings) {
        Object recipient = t.get("assignee_id");
        if (recipient == null) recipient = t.get("requester_id");
        db.update(
            "INSERT INTO notification(id,tenant_id,user_id,message) VALUES(?,?,?,?)",
            UUID.randomUUID(),
            t.get("tenant_id"),
            recipient,
            metric + " SLA approaching for " + t.get("id"));
        db.update(
            "INSERT INTO audit_event(id,tenant_id,ticket_id,action,details) VALUES(?,?,?,?,?)",
            UUID.randomUUID(),
            t.get("tenant_id"),
            t.get("id"),
            "SLA_WARNING",
            metric);
      }
      var changed =
          db.queryForList(
              "UPDATE ticket SET "
                  + metric
                  + "_breached=TRUE WHERE NOT "
                  + metric
                  + "_breached AND "
                  + metric
                  + "_due<=now() AND ("
                  + completion
                  + " IS NULL OR "
                  + completion
                  + ">"
                  + metric
                  + "_due) AND status<>'CANCELLED'"
                  + condition
                  + " RETURNING id,tenant_id,assignee_id,requester_id");
      for (var t : changed) {
        db.update(
            "INSERT INTO audit_event(id,tenant_id,ticket_id,action,details) VALUES(?,?,?,?,?)",
            UUID.randomUUID(),
            t.get("tenant_id"),
            t.get("id"),
            "SLA_BREACHED",
            metric);
        Object recipient = t.get("assignee_id");
        if (recipient == null) recipient = t.get("requester_id");
        java.util.Set<Object> recipients = new java.util.HashSet<>();
        recipients.add(recipient);
        recipients.addAll(
            db.queryForList(
                "SELECT m.user_id FROM membership m JOIN tenant o ON o.id=m.tenant_id WHERE o.id=?"
                    + " AND o.escalate_to_leads AND m.active AND (m.role='ADMIN' OR (m.role='LEAD'"
                    + " AND EXISTS(SELECT 1 FROM team_member tm JOIN ticket ti ON"
                    + " ti.tenant_id=tm.tenant_id AND ti.team_id=tm.team_id WHERE ti.id=? AND"
                    + " tm.user_id=m.user_id)))",
                java.util.UUID.class,
                t.get("tenant_id"),
                t.get("id")));
        for (Object target : recipients)
          db.update(
              "INSERT INTO notification(id,tenant_id,user_id,message) VALUES(?,?,?,?)",
              UUID.randomUUID(),
              t.get("tenant_id"),
              target,
              metric + " SLA breached for " + t.get("id"));
      }
    }
  }
}

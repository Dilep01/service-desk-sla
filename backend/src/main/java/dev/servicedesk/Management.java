package dev.servicedesk;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tenants/{tenant}")
public class Management {
  private final JdbcTemplate db;
  private final DeskController desk;
  private final Access access;

  public Management(JdbcTemplate db, DeskController desk, Access access) {
    this.db = db;
    this.desk = desk;
    this.access = access;
  }

  @PostMapping("/notifications/{id}/read")
  public void read(@PathVariable UUID tenant, @PathVariable UUID id, Principal p) {
    UUID user = desk.user(p);
    desk.role(tenant, user);
    if (db.update(
            "UPDATE notification SET read_at=COALESCE(read_at,now()) WHERE tenant_id=? AND id=? AND"
                + " user_id=?",
            tenant,
            id,
            user)
        != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  @GetMapping("/delivery")
  public List<Map<String, Object>> delivery(@PathVariable UUID tenant, Principal p) {
    access.admin(desk.role(tenant, desk.user(p)));
    return db.queryForList(
        "SELECT id,recipient,subject,attempts,available_at,delivered_at,last_error FROM mail_outbox"
            + " WHERE tenant_id=? ORDER BY available_at DESC LIMIT 100",
        tenant);
  }

  @PostMapping("/delivery/{id}/retry")
  @Transactional
  public void retry(@PathVariable UUID tenant, @PathVariable UUID id, Principal p) {
    UUID u = desk.user(p);
    access.admin(desk.role(tenant, u));
    if (db.update(
            "UPDATE mail_outbox SET attempts=0,available_at=now(),last_error=NULL WHERE tenant_id=?"
                + " AND id=? AND delivered_at IS NULL",
            tenant,
            id)
        != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    desk.audit(tenant, null, u, "MAIL_RETRY", id.toString());
  }

  @DeleteMapping("/teams/{team}/members/{member}")
  @Transactional
  public void remove(
      @PathVariable UUID tenant, @PathVariable UUID team, @PathVariable UUID member, Principal p) {
    UUID u = desk.user(p);
    access.admin(desk.role(tenant, u));
    db.update(
        "UPDATE ticket SET assignee_id=NULL,version=version+1 WHERE tenant_id=? AND team_id=? AND"
            + " assignee_id=?",
        tenant,
        team,
        member);
    db.update(
        "DELETE FROM team_member WHERE tenant_id=? AND team_id=? AND user_id=?",
        tenant,
        team,
        member);
    desk.audit(tenant, null, u, "TEAM_MEMBER_REMOVED", team + ":" + member);
  }

  @GetMapping("/teams/{team}/members")
  public List<Map<String, Object>> members(
      @PathVariable UUID tenant, @PathVariable UUID team, Principal p) {
    desk.staff(desk.role(tenant, desk.user(p)));
    return db.queryForList(
        "SELECT u.id,u.display_name FROM team_member tm JOIN app_user u ON u.id=tm.user_id JOIN"
            + " membership m ON m.tenant_id=tm.tenant_id AND m.user_id=tm.user_id WHERE"
            + " tm.tenant_id=? AND tm.team_id=? AND m.active AND m.role IN"
            + " ('AGENT','LEAD','ADMIN')",
        tenant,
        team);
  }

  @PutMapping("/articles/{id}")
  @Transactional
  public void edit(
      @PathVariable UUID tenant,
      @PathVariable UUID id,
      @Valid @RequestBody SupportController.Article body,
      Principal p) {
    UUID u = desk.user(p);
    access.admin(desk.role(tenant, u));
    if (db.update(
            "UPDATE knowledge_article SET title=?,body=?,published=? WHERE tenant_id=? AND id=?",
            body.title(),
            body.body(),
            body.published(),
            tenant,
            id)
        != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    desk.audit(tenant, null, u, "ARTICLE_UPDATED", id.toString());
  }

  @DeleteMapping("/tickets/{ticket}/attachments/{id}")
  @Transactional
  public void delete(
      @PathVariable UUID tenant, @PathVariable UUID ticket, @PathVariable UUID id, Principal p) {
    UUID u = desk.user(p);
    String role = desk.role(tenant, u);
    desk.ticket(tenant, ticket, u, role);
    if (db.update(
            "DELETE FROM ticket_attachment WHERE tenant_id=? AND ticket_id=? AND id=? AND"
                + " (uploader_id=? OR ?='ADMIN')",
            tenant,
            ticket,
            id,
            u,
            role)
        != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    desk.audit(tenant, ticket, u, "ATTACHMENT_DELETED", id.toString());
  }
}

package dev.servicedesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tenants/{tenant}/teams")
public class TeamController {
  private final JdbcTemplate db;
  private final DeskController desk;
  private final Access access;

  public TeamController(JdbcTemplate db, DeskController desk, Access access) {
    this.db = db;
    this.desk = desk;
    this.access = access;
  }

  @GetMapping
  public List<Map<String, Object>> list(@PathVariable UUID tenant, Principal p) {
    desk.role(tenant, desk.user(p));
    return db.queryForList(
        "SELECT id,name FROM support_team WHERE tenant_id=? ORDER BY name", tenant);
  }

  public record NewTeam(@NotBlank @Size(max = 100) String name) {}

  public record Routing(@NotNull UUID teamId, long version) {}

  @PutMapping("/route/{ticket}")
  @Transactional
  public void route(
      @PathVariable UUID tenant,
      @PathVariable UUID ticket,
      @Valid @RequestBody Routing body,
      Principal p) {
    UUID actor = desk.user(p);
    access.admin(desk.role(tenant, actor));
    desk.ticket(tenant, ticket, actor, "ADMIN");
    if (db.queryForObject(
            "SELECT count(*) FROM support_team WHERE tenant_id=? AND id=?",
            Integer.class,
            tenant,
            body.teamId())
        != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid team");
    if (db.update(
            "UPDATE ticket SET team_id=?,assignee_id=NULL,version=version+1,updated_at=now() WHERE"
                + " tenant_id=? AND id=? AND version=?",
            body.teamId(),
            tenant,
            ticket,
            body.version())
        != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
    desk.audit(tenant, ticket, actor, "TEAM_CHANGED", body.teamId().toString());
  }

  @PostMapping
  @Transactional
  public Map<String, UUID> create(
      @PathVariable UUID tenant, @Valid @RequestBody NewTeam body, Principal p) {
    UUID u = desk.user(p);
    access.admin(desk.role(tenant, u));
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO support_team(id,tenant_id,name) VALUES(?,?,?)",
        id,
        tenant,
        body.name().trim());
    desk.audit(tenant, null, u, "TEAM_CREATED", body.name());
    return Map.of("id", id);
  }

  @PutMapping("/{team}/members/{member}")
  @Transactional
  public void add(
      @PathVariable UUID tenant, @PathVariable UUID team, @PathVariable UUID member, Principal p) {
    UUID u = desk.user(p);
    access.admin(desk.role(tenant, u));
    desk.staff(desk.role(tenant, member));
    if (db.queryForObject(
            "SELECT count(*) FROM support_team WHERE tenant_id=? AND id=?",
            Integer.class,
            tenant,
            team)
        != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    db.update(
        "INSERT INTO team_member(tenant_id,team_id,user_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",
        tenant,
        team,
        member);
    desk.audit(tenant, null, u, "TEAM_MEMBER_ADDED", team + ":" + member);
  }
}

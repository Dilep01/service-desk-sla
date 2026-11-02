package dev.servicedesk;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenants/{tenant}/sla-policies")
public class SlaPolicyController {
  private final JdbcTemplate db;
  private final DeskController desk;
  private final Access access;
  private final SlaPolicies policies;

  public SlaPolicyController(
      JdbcTemplate db, DeskController desk, Access access, SlaPolicies policies) {
    this.db = db;
    this.desk = desk;
    this.access = access;
    this.policies = policies;
  }

  @GetMapping
  public List<Map<String, Object>> list(@PathVariable UUID tenant, Principal p) {
    access.admin(desk.role(tenant, desk.user(p)));
    return db.queryForList(
        "SELECT id,priority,version,definition,created_at FROM sla_policy_version WHERE tenant_id=?"
            + " ORDER BY priority,version DESC",
        tenant);
  }

  @PostMapping
  @Transactional
  public Map<String, Object> create(
      @PathVariable UUID tenant, @Valid @RequestBody SlaPolicies.Definition body, Principal p) {
    UUID user = desk.user(p);
    access.admin(desk.role(tenant, user));
    String definition = policies.encode(body);
    db.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE", UUID.class, tenant);
    int version =
        db.queryForObject(
            "SELECT COALESCE(max(version),0)+1 FROM sla_policy_version WHERE tenant_id=? AND"
                + " priority=?",
            Integer.class,
            tenant,
            body.priority());
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO sla_policy_version(id,tenant_id,priority,version,definition)"
            + " VALUES(?,?,?,?,?)",
        id,
        tenant,
        body.priority(),
        version,
        definition);
    desk.audit(tenant, null, user, "SLA_POLICY_CREATED", body.priority() + ":" + version);
    return Map.of("id", id, "version", version);
  }
}

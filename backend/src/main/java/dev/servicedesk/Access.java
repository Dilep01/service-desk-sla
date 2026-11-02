package dev.servicedesk;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Access {
  private final JdbcTemplate db;

  public Access(JdbcTemplate db) {
    this.db = db;
  }

  public static String visibility(String role) {
    return switch (role) {
      case "ADMIN" -> "TRUE";
      case "REQUESTER" -> "requester_id=:user";
      case "AGENT", "LEAD" ->
          "EXISTS (SELECT 1 FROM team_member tm WHERE tm.tenant_id=ticket.tenant_id AND"
              + " tm.team_id=ticket.team_id AND tm.user_id=:user)";
      default -> "FALSE";
    };
  }

  public void admin(String role) {
    if (!"ADMIN".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  public void eligible(UUID tenant, UUID team, UUID user) {
    Integer n =
        db.queryForObject(
            "SELECT count(*) FROM team_member tm JOIN membership m ON m.tenant_id=tm.tenant_id AND"
                + " m.user_id=tm.user_id WHERE tm.tenant_id=? AND tm.team_id=? AND tm.user_id=? AND"
                + " m.active AND m.role IN ('AGENT','LEAD','ADMIN')",
            Integer.class,
            tenant,
            team,
            user);
    if (n == null || n == 0)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Assignee must be an active member of the ticket team");
  }
}

package dev.servicedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketIdempotency {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  public TicketIdempotency(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  String fingerprint(Object body) {
    try {
      return Accounts.hash(json.writeValueAsString(body));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public UUID existing(UUID tenant, UUID user, UUID key, Object body) {
    if (key == null) return null;
    db.execute(
        (org.springframework.jdbc.core.ConnectionCallback<Void>)
            connection -> {
              try (var statement =
                  connection.prepareStatement(
                      "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, tenant + ":" + user + ":" + key);
                statement.execute();
              }
              return null;
            });
    var rows =
        db.queryForList(
            "SELECT fingerprint,ticket_id FROM ticket_request WHERE tenant_id=? AND user_id=? AND"
                + " request_key=?",
            tenant,
            user,
            key);
    if (rows.isEmpty()) return null;
    if (!rows.getFirst().get("fingerprint").equals(fingerprint(body)))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This submission code was already used for different ticket details");
    return (UUID) rows.getFirst().get("ticket_id");
  }

  public void record(UUID tenant, UUID user, UUID key, Object body, UUID ticket) {
    if (key != null)
      db.update(
          "INSERT INTO ticket_request(tenant_id,user_id,request_key,fingerprint,ticket_id)"
              + " VALUES(?,?,?,?,?)",
          tenant,
          user,
          key,
          fingerprint(body),
          ticket);
  }
}

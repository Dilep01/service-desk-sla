package dev.servicedesk;

import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoData implements CommandLineRunner {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;

  public DemoData(JdbcTemplate db, PasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  @Override
  @Transactional
  public void run(String... args) {
    for (int i = 1; i <= 2; i++) {
      UUID tenant =
          UUID.nameUUIDFromBytes(("tenant" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      db.update(
          "INSERT INTO tenant(id,name) VALUES(?,?) ON CONFLICT DO NOTHING",
          tenant,
          i == 1 ? "Northstar IT" : "Harbor Services");
      db.queryForObject("SELECT set_config('desk.tenant',?,true)", String.class, tenant.toString());
      UUID team =
          UUID.nameUUIDFromBytes(("team" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      db.update(
          "INSERT INTO support_team(id,tenant_id,name) VALUES(?,?,?) ON CONFLICT DO NOTHING",
          team,
          tenant,
          "IT Support");
      for (String role : new String[] {"ADMIN", "AGENT", "REQUESTER"}) {
        String email = role.toLowerCase() + i + "@demo.local";
        UUID id = UUID.nameUUIDFromBytes(email.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.update(
            "INSERT INTO app_user(id,email,display_name,password_hash) VALUES(?,?,?,?) ON CONFLICT"
                + " DO NOTHING",
            id,
            email,
            role.toLowerCase() + " " + i,
            encoder.encode("Demo-local-2026!"));
        db.update(
            "INSERT INTO membership(tenant_id,user_id,role) VALUES(?,?,?) ON CONFLICT DO NOTHING",
            tenant,
            id,
            role);
        if (!role.equals("REQUESTER"))
          db.update(
              "INSERT INTO team_member(tenant_id,team_id,user_id) VALUES(?,?,?) ON CONFLICT DO"
                  + " NOTHING",
              tenant,
              team,
              id);
      }
    }
  }
}

package dev.servicedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SlaPolicies {
  public record Definition(
      @NotNull @Pattern(regexp = "P[1-4]") String priority,
      @Min(1) @Max(525600) int responseMinutes,
      @Min(1) @Max(525600) int resolutionMinutes,
      boolean businessHours,
      String zone,
      String opens,
      String closes,
      Set<Integer> days,
      Set<String> holidays) {}

  public record Targets(UUID policyId, Instant response, Instant resolution) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;

  public SlaPolicies(JdbcTemplate db, ObjectMapper json, Clock clock) {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  public Instant now() {
    return clock.instant();
  }

  public BusinessCalendar calendar(Definition d) {
    if (!d.businessHours()) return null;
    try {
      if (d.days() == null
          || d.days().isEmpty()
          || d.holidays() == null
          || d.holidays().size() > 366) throw new IllegalArgumentException();
      Set<DayOfWeek> days = new HashSet<>();
      for (Integer day : d.days()) days.add(DayOfWeek.of(day));
      Set<LocalDate> holidays = new HashSet<>();
      for (String date : d.holidays()) holidays.add(LocalDate.parse(date));
      return new BusinessCalendar(
          ZoneId.of(d.zone()),
          LocalTime.parse(d.opens()),
          LocalTime.parse(d.closes()),
          days,
          holidays);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid business calendar");
    }
  }

  public String encode(Definition d) {
    calendar(d);
    try {
      return json.writeValueAsString(d);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  Definition decode(String value) {
    try {
      return json.readValue(value, Definition.class);
    } catch (Exception e) {
      throw new IllegalStateException("Stored SLA policy is invalid", e);
    }
  }

  Definition policy(UUID tenant, UUID id) {
    if (id == null) return null;
    return decode(
        db.queryForObject(
            "SELECT definition FROM sla_policy_version WHERE tenant_id=? AND id=?",
            String.class,
            tenant,
            id));
  }

  public Targets targets(UUID tenant, String priority, Instant start) {
    var rows =
        db.queryForList(
            "SELECT id,definition FROM sla_policy_version WHERE tenant_id=? AND priority=? ORDER BY"
                + " version DESC LIMIT 1",
            tenant,
            priority);
    if (rows.isEmpty())
      return new Targets(
          null, start.plus(Sla.response(priority)), start.plus(Sla.resolution(priority)));
    var row = rows.getFirst();
    Definition d = decode((String) row.get("definition"));
    BusinessCalendar c = calendar(d);
    return new Targets(
        (UUID) row.get("id"),
        add(c, start, Duration.ofMinutes(d.responseMinutes())),
        add(c, start, Duration.ofMinutes(d.resolutionMinutes())));
  }

  Instant add(BusinessCalendar c, Instant start, Duration duration) {
    return c == null ? start.plus(duration) : c.add(start, duration);
  }

  public void transition(UUID tenant, UUID id, long version, String target) {
    var rows =
        db.queryForList("SELECT * FROM ticket WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var t = rows.getFirst();
    if (((Number) t.get("version")).longValue() != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Ticket changed; refresh and retry");
    if (!Sla.transition((String) t.get("status"), target))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transition");
    Instant now = now(), due = ((Timestamp) t.get("resolution_due")).toInstant();
    Definition d = policy(tenant, (UUID) t.get("sla_policy_id"));
    BusinessCalendar c = d == null ? null : calendar(d);
    if (t.get("status").equals("RESOLVED") && target.equals("IN_PROGRESS")) {
      Instant resolved = ((Timestamp) t.get("resolved_at")).toInstant();
      db.update(
          "INSERT INTO"
              + " resolution_cycle_history(tenant_id,ticket_id,cycle,deadline,resolved_at,breached,summary)"
              + " VALUES(?,?,?,?,?,?,?)",
          tenant,
          id,
          t.get("resolution_cycle"),
          Timestamp.from(due),
          Timestamp.from(resolved),
          (Boolean) t.get("resolution_breached") || resolved.isAfter(due),
          t.get("resolution_summary"));
      Duration budget =
          d == null
              ? Sla.resolution((String) t.get("priority"))
              : Duration.ofMinutes(d.resolutionMinutes());
      db.update(
          "UPDATE ticket SET"
              + " status='IN_PROGRESS',version=version+1,updated_at=?,resolution_due=?,resolved_at=NULL,resolution_summary=NULL,resolution_breached=false,resolution_warned=false,paused_at=NULL,paused_remaining_seconds=NULL,resolution_cycle=resolution_cycle+1"
              + " WHERE tenant_id=? AND id=?",
          Timestamp.from(now),
          Timestamp.from(add(c, now, budget)),
          tenant,
          id);
      return;
    }
    Long remaining = null;
    Timestamp paused = null;
    boolean breached = (Boolean) t.get("resolution_breached");
    if (target.equals("WAITING_FOR_CUSTOMER")) {
      breached = breached || !now.isBefore(due);
      if (breached && !(Boolean) t.get("resolution_breached")) {
        db.update(
            "INSERT INTO audit_event(id,tenant_id,ticket_id,action,details) VALUES(?,?,?,?,?)",
            UUID.randomUUID(),
            tenant,
            id,
            "SLA_BREACHED",
            "resolution");
        Object recipient = t.get("assignee_id");
        if (recipient == null) recipient = t.get("requester_id");
        db.update(
            "INSERT INTO notification(id,tenant_id,user_id,message) VALUES(?,?,?,?)",
            UUID.randomUUID(),
            tenant,
            recipient,
            "resolution SLA breached for " + id);
      }
      remaining =
          Math.max(0, (c == null ? Duration.between(now, due) : c.between(now, due)).getSeconds());
      paused = Timestamp.from(now);
    } else if (t.get("paused_at") != null) {
      Number saved = (Number) t.get("paused_remaining_seconds");
      if (saved != null) due = add(c, now, Duration.ofSeconds(saved.longValue()));
      else due = due.plus(Duration.between(((Timestamp) t.get("paused_at")).toInstant(), now));
    }
    db.update(
        "UPDATE ticket SET"
            + " status=?,version=version+1,updated_at=?,resolution_due=?,paused_at=?,paused_remaining_seconds=?,resolution_breached=?,resolved_at=CASE"
            + " WHEN ?='RESOLVED' THEN ? ELSE resolved_at END WHERE tenant_id=? AND id=?",
        target,
        Timestamp.from(now),
        Timestamp.from(due),
        paused,
        remaining,
        breached,
        target,
        Timestamp.from(now),
        tenant,
        id);
  }
}

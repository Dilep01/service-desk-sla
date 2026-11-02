package dev.servicedesk;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes transaction-local row-security context after membership validation. */
public class TenantDatabaseContext extends OncePerRequestFilter {
  private final JdbcTemplate db;
  private final TransactionTemplate transaction;
  private final Pattern path = Pattern.compile("^/api/tenants/([0-9a-fA-F-]{36})(?:/.*)?$");

  public TenantDatabaseContext(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    transaction = new TransactionTemplate(manager);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest q, HttpServletResponse r, FilterChain chain)
      throws ServletException, IOException {
    var match = path.matcher(q.getRequestURI());
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!match.matches() || auth == null || auth.getName().equals("anonymousUser")) {
      chain.doFilter(q, r);
      return;
    }
    UUID tenant;
    try {
      tenant = UUID.fromString(match.group(1));
    } catch (IllegalArgumentException e) {
      r.sendError(400);
      return;
    }
    if (db.queryForObject(
            "SELECT count(*) FROM membership m JOIN app_user u ON u.id=m.user_id WHERE"
                + " m.tenant_id=? AND u.email=? AND m.active",
            Integer.class,
            tenant,
            auth.getName())
        != 1) {
      r.sendError(404);
      return;
    }
    try {
      transaction.executeWithoutResult(
          status -> {
            db.queryForObject(
                "SELECT set_config('desk.tenant',?,true)", String.class, tenant.toString());
            try {
              chain.doFilter(q, r);
              if (r.getStatus() >= 400) status.setRollbackOnly();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
    } catch (RuntimeException e) {
      if (e.getCause() instanceof ServletException se) throw se;
      if (e.getCause() instanceof IOException io) throw io;
      throw e;
    }
  }
}

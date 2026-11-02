package dev.servicedesk;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionGuard extends OncePerRequestFilter {
  private final JdbcTemplate db;

  public SessionGuard(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest q, HttpServletResponse r, FilterChain chain)
      throws ServletException, IOException {
    if (q.getMethod().equals("POST")
        && (q.getRequestURI().equals("/api/login")
            || q.getRequestURI().startsWith("/api/account/reset"))) {
      String key = q.getRemoteAddr() + ":" + q.getRequestURI();
      Integer count =
          db.queryForObject(
              "INSERT INTO login_bucket(address,attempts,expires_at) VALUES(?,1,now()+interval '1"
                  + " minute') ON CONFLICT(address) DO UPDATE SET attempts=CASE WHEN"
                  + " login_bucket.expires_at<now() THEN 1 ELSE login_bucket.attempts+1"
                  + " END,expires_at=CASE WHEN login_bucket.expires_at<now() THEN now()+interval '1"
                  + " minute' ELSE login_bucket.expires_at END RETURNING attempts",
              Integer.class,
              key);
      if (count > 10) {
        r.setHeader("Retry-After", "60");
        r.sendError(429, "Try again later");
        return;
      }
    }
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var session = q.getSession(false);
    if (auth != null && session != null && session.getAttribute("accountVersion") != null) {
      Long version =
          db.queryForObject(
              "SELECT session_version FROM app_user WHERE email=?", Long.class, auth.getName());
      if (!version.equals(session.getAttribute("accountVersion"))) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        r.sendError(401);
        return;
      }
    }
    chain.doFilter(q, r);
  }
}

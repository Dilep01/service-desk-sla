package dev.servicedesk;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableWebSocket
public class LiveUpdates extends TextWebSocketHandler implements WebSocketConfigurer {
  private final JdbcTemplate db;
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

  public LiveUpdates(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(this, "/api/live")
        .addInterceptors(
            new HandshakeInterceptor() {
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Map<String, Object> attrs) {
                try {
                  if (request.getPrincipal() == null) return false;
                  UUID tenant =
                      UUID.fromString(
                          UriComponentsBuilder.fromUri(request.getURI())
                              .build()
                              .getQueryParams()
                              .getFirst("tenant"));
                  String email = request.getPrincipal().getName();
                  var rows =
                      db.queryForList(
                          "SELECT u.session_version FROM membership m JOIN app_user u ON"
                              + " u.id=m.user_id WHERE m.tenant_id=? AND u.email=? AND m.active",
                          tenant,
                          email);
                  if (rows.isEmpty()) return false;
                  attrs.put("tenant", tenant);
                  attrs.put("email", email);
                  attrs.put("version", rows.getFirst().get("session_version"));
                  if (request instanceof ServletServerHttpRequest servlet)
                    attrs.put("session", servlet.getServletRequest().getSession(false));
                  return attrs.get("session") != null;
                } catch (Exception e) {
                  return false;
                }
              }

              public void afterHandshake(
                  ServerHttpRequest r, ServerHttpResponse s, WebSocketHandler h, Exception e) {}
            });
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    sessions.put(session.getId(), session);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    sessions.remove(session.getId());
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
    s.close(CloseStatus.POLICY_VIOLATION);
  }

  @Scheduled(fixedDelay = 3000)
  @org.springframework.transaction.annotation.Transactional
  public void update() {
    for (WebSocketSession s : sessions.values()) {
      try {
        var a = s.getAttributes();
        ((HttpSession) a.get("session")).getCreationTime();
        var rows =
            db.queryForList(
                "SELECT u.session_version FROM app_user u JOIN membership m ON m.user_id=u.id WHERE"
                    + " u.email=? AND m.tenant_id=? AND m.active",
                a.get("email"),
                a.get("tenant"));
        if (rows.isEmpty() || !rows.getFirst().get("session_version").equals(a.get("version"))) {
          s.close(CloseStatus.POLICY_VIOLATION);
          continue;
        }
        db.queryForObject(
            "SELECT set_config('desk.tenant',?,true)", String.class, a.get("tenant").toString());
        // Send only an invalidation marker. The authenticated HTTP endpoints recheck access.
        Long revision =
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE tenant_id=?", Long.class, a.get("tenant"));
        if (!revision.equals(a.get("revision"))) {
          s.sendMessage(new TextMessage("{\"refresh\":true}"));
          a.put("revision", revision);
        }
      } catch (Exception e) {
        try {
          s.close();
        } catch (Exception ignored) {
        }
        sessions.remove(s.getId());
      }
    }
  }
}

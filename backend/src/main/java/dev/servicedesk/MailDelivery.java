package dev.servicedesk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailDelivery {
  private final JdbcTemplate db;
  private final JavaMailSender sender;
  private final boolean enabled;

  public MailDelivery(
      JdbcTemplate db,
      JavaMailSender sender,
      @Value("${desk.mail.enabled:false}") boolean enabled) {
    this.db = db;
    this.sender = sender;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 5000)
  @Transactional
  public void deliver() {
    if (!enabled) return;
    for (var row :
        db.queryForList(
            "SELECT * FROM mail_outbox WHERE delivered_at IS NULL AND attempts<5 AND"
                + " available_at<=now() ORDER BY available_at LIMIT 10 FOR UPDATE SKIP LOCKED")) {
      try {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("servicedesk@localhost");
        message.setTo((String) row.get("recipient"));
        message.setSubject((String) row.get("subject"));
        message.setText((String) row.get("body"));
        sender.send(message);
        db.update(
            "UPDATE mail_outbox SET delivered_at=now(),attempts=attempts+1,last_error=NULL WHERE"
                + " id=?",
            row.get("id"));
      } catch (Exception e) {
        db.update(
            "UPDATE mail_outbox SET attempts=attempts+1,last_error='SMTP delivery"
                + " failed',available_at=now()+interval '1 minute' WHERE id=?",
            row.get("id"));
      }
    }
  }
}

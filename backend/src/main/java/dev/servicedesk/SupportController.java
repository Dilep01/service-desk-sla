package dev.servicedesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tenants/{tenant}")
public class SupportController {
  private final JdbcTemplate db;
  private final DeskController desk;
  private final Access access;

  public SupportController(JdbcTemplate db, DeskController desk, Access access) {
    this.db = db;
    this.desk = desk;
    this.access = access;
  }

  public record Article(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 40000) String body,
      boolean published) {}

  @GetMapping("/articles")
  public List<Map<String, Object>> articles(@PathVariable UUID tenant, Principal p) {
    String role = desk.role(tenant, desk.user(p));
    return db.queryForList(
        "SELECT id,title,body,published FROM knowledge_article WHERE tenant_id=?"
            + (role.equals("REQUESTER") ? " AND published" : "")
            + " ORDER BY created_at DESC LIMIT 100",
        tenant);
  }

  @PostMapping("/articles")
  @Transactional
  public Map<String, UUID> article(
      @PathVariable UUID tenant, @Valid @RequestBody Article body, Principal p) {
    UUID u = desk.user(p);
    access.admin(desk.role(tenant, u));
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO knowledge_article(id,tenant_id,title,body,published,author_id)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        tenant,
        body.title(),
        body.body(),
        body.published(),
        u);
    desk.audit(tenant, null, u, "ARTICLE_CREATED", id.toString());
    return Map.of("id", id);
  }

  public record Approval(@NotNull UUID approverId, @NotBlank @Size(max = 2000) String reason) {}

  @GetMapping("/tickets/{ticket}/approvals")
  public List<Map<String, Object>> approvals(
      @PathVariable UUID tenant, @PathVariable UUID ticket, Principal p) {
    UUID u = desk.user(p);
    desk.ticket(tenant, ticket, u, desk.role(tenant, u));
    return db.queryForList(
        "SELECT * FROM ticket_approval WHERE tenant_id=? AND ticket_id=? ORDER BY created_at",
        tenant,
        ticket);
  }

  @PostMapping("/tickets/{ticket}/approvals")
  @Transactional
  public Map<String, UUID> request(
      @PathVariable UUID tenant,
      @PathVariable UUID ticket,
      @Valid @RequestBody Approval body,
      Principal p) {
    UUID u = desk.user(p);
    String r = desk.role(tenant, u);
    desk.staff(r);
    desk.ticket(tenant, ticket, u, r);
    String ar = desk.role(tenant, body.approverId());
    desk.staff(ar);
    desk.ticket(tenant, ticket, body.approverId(), ar);
    if (u.equals(body.approverId()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another approver");
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO ticket_approval(id,tenant_id,ticket_id,approver_id,requested_by,reason)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        tenant,
        ticket,
        body.approverId(),
        u,
        body.reason());
    db.update(
        "INSERT INTO notification(id,tenant_id,user_id,message) VALUES(?,?,?,?)",
        UUID.randomUUID(),
        tenant,
        body.approverId(),
        "Approval requested for " + ticket);
    desk.audit(tenant, ticket, u, "APPROVAL_REQUESTED", id.toString());
    return Map.of("id", id);
  }

  public record Decision(@NotNull @Pattern(regexp = "APPROVED|REJECTED") String decision) {}

  @PostMapping("/tickets/{ticket}/approvals/{id}")
  @Transactional
  public void decide(
      @PathVariable UUID tenant,
      @PathVariable UUID ticket,
      @PathVariable UUID id,
      @Valid @RequestBody Decision body,
      Principal p) {
    UUID u = desk.user(p);
    String r = desk.role(tenant, u);
    desk.staff(r);
    desk.ticket(tenant, ticket, u, r);
    if (db.update(
            "UPDATE ticket_approval SET decision=?,decided_at=now() WHERE tenant_id=? AND"
                + " ticket_id=? AND id=? AND approver_id=? AND decision IS NULL",
            body.decision(),
            tenant,
            ticket,
            id,
            u)
        != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Approval unavailable or already decided");
    desk.audit(tenant, ticket, u, "APPROVAL_DECIDED", id + ":" + body.decision());
  }

  @GetMapping("/tickets/{ticket}/attachments")
  public List<Map<String, Object>> attachments(
      @PathVariable UUID tenant, @PathVariable UUID ticket, Principal p) {
    UUID u = desk.user(p);
    desk.ticket(tenant, ticket, u, desk.role(tenant, u));
    return db.queryForList(
        "SELECT id,name,mime_type,octet_length(content) AS size,created_at FROM ticket_attachment"
            + " WHERE tenant_id=? AND ticket_id=? ORDER BY created_at",
        tenant,
        ticket);
  }

  @PostMapping("/tickets/{ticket}/attachments")
  @Transactional
  public Map<String, UUID> upload(
      @PathVariable UUID tenant,
      @PathVariable UUID ticket,
      @RequestParam MultipartFile file,
      Principal p)
      throws IOException {
    UUID u = desk.user(p);
    desk.ticket(tenant, ticket, u, desk.role(tenant, u));
    if (file.isEmpty() || file.getSize() > 2097152)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "File must be between 1 byte and 2 MiB");
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("attachment");
    if (name.length() > 200
        || name.contains("/")
        || name.contains("\\")
        || name.chars().anyMatch(Character::isISOControl))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
    String lower = name.toLowerCase(Locale.ROOT);
    if (!(lower.endsWith(".txt")
        || lower.endsWith(".png")
        || lower.endsWith(".jpg")
        || lower.endsWith(".pdf")))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Allowed extensions: txt, png, jpg, pdf");
    db.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE", UUID.class, tenant);
    Long size =
        db.queryForObject(
            "SELECT COALESCE(sum(octet_length(content)),0) FROM ticket_attachment WHERE"
                + " tenant_id=?",
            Long.class,
            tenant);
    if (size + file.getSize() > 52428800)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Organization attachment quota reached (50 MiB)");
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO ticket_attachment(id,tenant_id,ticket_id,uploader_id,name,mime_type,content)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        tenant,
        ticket,
        u,
        name,
        "application/octet-stream",
        file.getBytes());
    desk.audit(tenant, ticket, u, "ATTACHMENT_ADDED", id.toString());
    return Map.of("id", id);
  }

  @GetMapping("/tickets/{ticket}/attachments/{id}")
  public ResponseEntity<byte[]> download(
      @PathVariable UUID tenant, @PathVariable UUID ticket, @PathVariable UUID id, Principal p) {
    UUID u = desk.user(p);
    desk.ticket(tenant, ticket, u, desk.role(tenant, u));
    var row =
        db
            .queryForList(
                "SELECT name,content FROM ticket_attachment WHERE tenant_id=? AND ticket_id=? AND"
                    + " id=?",
                tenant,
                ticket,
                id)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store")
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename((String) row.get("name"), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .body((byte[]) row.get("content"));
  }
}

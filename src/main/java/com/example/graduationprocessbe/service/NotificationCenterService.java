package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Inbox for the signed-in user plus the admin screens: templates and delivery history. */
@Service
@RequiredArgsConstructor
public class NotificationCenterService {
    private final JdbcTemplate jdbc;
    private final WorkflowMailService mail;

    public record TemplateInput(String subject, String body, String priority) {}
    public record PreviewInput(String subject, String body) {}

    public Map<String, Object> inbox(String userId, boolean unreadOnly, int page, int size) {
        int limit = Math.max(1, Math.min(size, 100)), offset = Math.max(0, page) * limit;
        String filter = unreadOnly ? " AND read_at IS NULL" : "";
        var items = jdbc.queryForList("SELECT id,title,body,link,priority,category,read_at,created_at FROM notifications " +
                "WHERE user_id=?" + filter + " ORDER BY created_at DESC LIMIT ? OFFSET ?", userId, limit, offset);
        Integer total = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?" + filter, Integer.class, userId);
        return Map.of("items", items, "total", total == null ? 0 : total, "unread", unreadCount(userId));
    }

    public int unreadCount(String userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL", Integer.class, userId);
        return count == null ? 0 : count;
    }

    public void markRead(String userId, String id) {
        jdbc.update("UPDATE notifications SET read_at=now() WHERE id=? AND user_id=? AND read_at IS NULL", id, userId);
    }

    public void markAllRead(String userId) {
        jdbc.update("UPDATE notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL", userId);
    }

    public List<Map<String, Object>> templates() {
        return jdbc.queryForList("SELECT t.code,t.name,t.description,t.priority,t.subject,t.body,t.updated_at," +
                "u.full_name AS updated_by_name,(t.subject<>t.default_subject OR t.body<>t.default_body) AS customized " +
                "FROM notification_templates t LEFT JOIN users u ON u.id=t.updated_by ORDER BY t.code");
    }

    public List<String> variables() {
        return NotificationService.VARIABLES.stream().sorted().toList();
    }

    @Transactional
    public void saveTemplate(String code, TemplateInput input, String userId) {
        if (input == null || input.subject() == null || input.subject().isBlank() || input.subject().length() > 255
                || input.body() == null || input.body().isBlank())
            throw new IllegalArgumentException("Cần nhập tiêu đề (tối đa 255 ký tự) và nội dung");
        String priority = input.priority() == null || input.priority().isBlank() ? "NORMAL" : input.priority();
        if (!NotificationService.PRIORITIES.contains(priority)) throw new IllegalArgumentException("Mức ưu tiên không hợp lệ");
        NotificationService.validate(input.subject(), "Tiêu đề");
        NotificationService.validate(input.body(), "Nội dung");
        int updated = jdbc.update("UPDATE notification_templates SET subject=?,body=?,priority=?,updated_by=?,updated_at=now() WHERE code=?",
                input.subject().trim(), input.body().trim(), priority, userId, code);
        if (updated == 0) throw new IllegalArgumentException("Không tìm thấy mẫu thông báo");
    }

    @Transactional
    public void resetTemplate(String code, String userId) {
        int updated = jdbc.update("UPDATE notification_templates SET subject=default_subject,body=default_body," +
                "updated_by=?,updated_at=now() WHERE code=?", userId, code);
        if (updated == 0) throw new IllegalArgumentException("Không tìm thấy mẫu thông báo");
    }

    public Map<String, String> preview(PreviewInput input) {
        if (input == null) throw new IllegalArgumentException("Thiếu nội dung xem trước");
        NotificationService.validate(input.subject(), "Tiêu đề");
        NotificationService.validate(input.body(), "Nội dung");
        var vars = NotificationService.sampleVars();
        String subject = NotificationService.render(input.subject(), vars);
        String body = NotificationService.render(input.body(), vars);
        return Map.of("subject", subject, "body", body, "html", mail.render(subject, body, "/tasks"));
    }

    /** Sends the template, filled with sample values, to the admin's own mailbox. */
    public void sendTest(String code, String email) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Tài khoản của bạn chưa có email");
        var template = jdbc.queryForList("SELECT subject,body FROM notification_templates WHERE code=?", code);
        if (template.isEmpty()) throw new IllegalArgumentException("Không tìm thấy mẫu thông báo");
        var vars = NotificationService.sampleVars();
        mail.sendNow(email, "[Gửi thử] " + NotificationService.render((String) template.getFirst().get("subject"), vars),
                NotificationService.render((String) template.getFirst().get("body"), vars), "/tasks");
    }

    public List<Map<String, Object>> deliveries(String status, String q, String roundId) {
        var sql = new StringBuilder("SELECT o.id,o.round_id,r.name AS round_name,o.step_key,o.event_key,o.recipient_email,o.cc,o.bcc," +
                "o.subject,o.priority,o.status,o.attempts,o.last_error,o.created_at,o.sent_at,o.next_attempt_at " +
                "FROM workflow_mail_outbox o LEFT JOIN thesis_rounds r ON r.id=o.round_id WHERE 1=1");
        var args = new ArrayList<Object>();
        if (status != null && !status.isBlank()) { sql.append(" AND o.status=?"); args.add(status); }
        if (roundId != null && !roundId.isBlank()) { sql.append(" AND o.round_id=?"); args.add(roundId); }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (o.recipient_email ILIKE ? OR o.subject ILIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY o.created_at DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> deliveryStats() {
        var stats = new HashMap<String, Object>();
        for (var row : jdbc.queryForList("SELECT status,count(*) AS total FROM workflow_mail_outbox GROUP BY status"))
            stats.put((String) row.get("status"), row.get("total"));
        return stats;
    }

    public void retry(String id) {
        int updated = jdbc.update("UPDATE workflow_mail_outbox SET status='PENDING',attempts=0,next_attempt_at=now(),last_error=NULL " +
                "WHERE id=? AND status='FAILED'", id);
        if (updated == 0) throw new IllegalArgumentException("Chỉ gửi lại được email thất bại");
    }
}

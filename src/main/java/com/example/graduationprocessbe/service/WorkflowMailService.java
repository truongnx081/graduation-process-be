package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class WorkflowMailService {
    private final JdbcTemplate jdbc;
    private final JavaMailSender sender;

    @Value("${app.mail.enabled:false}") private boolean enabled;
    @Value("${app.mail.from:}") private String from;

    public void enqueue(String roundId, String stepKey, String eventKey, String recipient,
                        String subject, String message, String roundName, String stepName, String deadline) {
        if (recipient == null || recipient.isBlank()) return;
        String html = render(subject, message, roundName, stepName, deadline);
        jdbc.update("INSERT INTO workflow_mail_outbox(id,round_id,step_key,event_key,recipient_email,subject,body) " +
                        "VALUES(?,?,?,?,?,?,?) ON CONFLICT(round_id,step_key,event_key,recipient_email) DO NOTHING",
                UUID.randomUUID().toString(), roundId, stepKey, eventKey, recipient.trim(), subject, html);
    }

    private String render(String title, String message, String roundName, String stepName, String deadline) {
        try {
            String html = new ClassPathResource("mail/workflow-notification.html")
                    .getContentAsString(StandardCharsets.UTF_8);
            return html.replace("{{title}}", escape(title)).replace("{{message}}", escape(message))
                    .replace("{{roundName}}", escape(roundName)).replace("{{stepName}}", escape(stepName))
                    .replace("{{deadline}}", escape(deadline));
        } catch (IOException ex) {
            throw new IllegalStateException("Không đọc được mẫu email thông báo", ex);
        }
    }

    private String escape(String value) {
        if (value == null) return "—";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void deliverPending() {
        if (!enabled || from == null || from.isBlank()) return;
        for (Map<String,Object> mail : jdbc.queryForList(
                "SELECT id,recipient_email,subject,body,attempts FROM workflow_mail_outbox " +
                "WHERE status='PENDING' AND attempts<5 AND next_attempt_at<=now() ORDER BY created_at LIMIT 100")) {
            String id = (String) mail.get("id");
            try {
                var mime = sender.createMimeMessage();
                var helper = new org.springframework.mail.javamail.MimeMessageHelper(mime, "UTF-8");
                helper.setFrom(from);
                helper.setTo((String) mail.get("recipient_email"));
                helper.setSubject((String) mail.get("subject"));
                helper.setText((String) mail.get("body"), true);
                sender.send(mime);
                jdbc.update("UPDATE workflow_mail_outbox SET status='SENT',sent_at=now(),attempts=attempts+1,last_error=NULL WHERE id=?", id);
            } catch (Exception ex) {
                jdbc.update("UPDATE workflow_mail_outbox SET attempts=attempts+1,last_error=?,next_attempt_at=? WHERE id=?",
                        ex.getMessage(), OffsetDateTime.now().plusMinutes(5L << Math.min(4, ((Number)mail.getOrDefault("attempts",0)).intValue())), id);
            }
        }
    }
}

package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Mail outbox: rows are queued inside the caller's transaction and delivered later with retry/backoff. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowMailService {
    public static final int MAX_ATTEMPTS = 5;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+$");

    private final JdbcTemplate jdbc;
    private final JavaMailSender sender;

    @Value("${app.mail.enabled:false}") private boolean enabled;
    @Value("${app.mail.from:}") private String from;
    @Value("${app.frontend-url:http://localhost:5173}") private String frontendUrl;

    public record Mail(String roundId, String stepKey, String eventKey, String userId, String email,
                       String subject, String text, String link, String priority, String cc, String bcc) {}

    public boolean isEnabled() { return enabled && from != null && !from.isBlank(); }

    public void enqueue(Mail mail) {
        if (mail.email() == null || mail.email().isBlank()) return;
        jdbc.update("INSERT INTO workflow_mail_outbox(id,round_id,step_key,event_key,recipient_user_id,recipient_email,subject,body,priority,cc,bcc) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(round_id,step_key,event_key,recipient_email) DO NOTHING",
                UUID.randomUUID().toString(), mail.roundId(), mail.stepKey(), cut(mail.eventKey(), 60), mail.userId(),
                mail.email().trim(), cut(mail.subject(), 255), render(mail.subject(), mail.text(), mail.link()),
                mail.priority() == null ? "NORMAL" : mail.priority(), blankToNull(mail.cc()), blankToNull(mail.bcc()));
    }

    /** Sends immediately (used by "gửi thử" so the admin sees the result at once). */
    public void sendNow(String to, String subject, String text, String link) {
        if (!isEnabled()) throw new IllegalArgumentException("Hệ thống chưa bật gửi email (app.mail.enabled / app.mail.from)");
        try {
            send(to, null, null, subject, render(subject, text, link));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Gửi email thử thất bại: " + ex.getMessage());
        }
    }

    /** Escaped HTML so admin-edited text can never inject markup into the mail. */
    public String render(String title, String text, String link) {
        try {
            String html = new ClassPathResource("mail/workflow-notification.html").getContentAsString(StandardCharsets.UTF_8);
            String button = "";
            if (link != null && !link.isBlank()) {
                String url = absolute(link);
                button = "<a href=\"" + escape(url) + "\" style=\"display:inline-block;background:#164e8a;color:#fff;" +
                        "text-decoration:none;padding:12px 28px;border-radius:6px;font-weight:600;font-size:15px\">Xem chi tiết trên hệ thống</a>";
            }
            return html.replace("{{title}}", escape(title)).replace("{{message}}", renderMessage(text)).replace("{{button}}", button);
        } catch (IOException ex) {
            throw new IllegalStateException("Không đọc được mẫu email thông báo", ex);
        }
    }

    public String absolute(String link) {
        if (link == null || link.isBlank() || link.startsWith("http://") || link.startsWith("https://")) return link;
        return frontendUrl.replaceAll("/+$", "") + (link.startsWith("/") ? link : "/" + link);
    }

    /** Normalises a comma/semicolon separated list; rejects invalid addresses and more than 20 of them. */
    public static String normalizeAddresses(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        var addresses = new LinkedHashSet<String>();
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isBlank()) continue;
            if (!EMAIL.matcher(part).matches()) throw new IllegalArgumentException(label + " có địa chỉ không hợp lệ: " + part);
            addresses.add(part.toLowerCase());
        }
        if (addresses.size() > 20) throw new IllegalArgumentException(label + " tối đa 20 địa chỉ");
        return addresses.isEmpty() ? null : String.join(", ", addresses);
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void deliverPending() {
        if (!isEnabled()) return;
        // A row left in SENDING by a crash is retried after its claim expires; give up once attempts are spent.
        jdbc.update("UPDATE workflow_mail_outbox SET status='FAILED',last_error=coalesce(last_error,'Gửi bị gián đoạn') " +
                "WHERE status='SENDING' AND attempts>=? AND next_attempt_at<=now()", MAX_ATTEMPTS);
        List<Map<String, Object>> batch = jdbc.queryForList(
                "UPDATE workflow_mail_outbox SET status='SENDING',attempts=attempts+1,next_attempt_at=now()+interval '15 minutes' " +
                "WHERE id IN (SELECT id FROM workflow_mail_outbox WHERE status IN ('PENDING','SENDING') AND attempts<? " +
                "AND next_attempt_at<=now() " +
                "ORDER BY CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END,created_at LIMIT 50 FOR UPDATE SKIP LOCKED) " +
                "RETURNING id,recipient_email,cc,bcc,subject,body,attempts", MAX_ATTEMPTS);
        for (Map<String, Object> mail : batch) {
            String id = (String) mail.get("id");
            int attempts = ((Number) mail.get("attempts")).intValue();
            try {
                send((String) mail.get("recipient_email"), (String) mail.get("cc"), (String) mail.get("bcc"),
                        (String) mail.get("subject"), (String) mail.get("body"));
                jdbc.update("UPDATE workflow_mail_outbox SET status='SENT',sent_at=now(),last_error=NULL WHERE id=?", id);
            } catch (Exception ex) {
                log.warn("Gửi email {} thất bại lần {}: {}", id, attempts, ex.getMessage());
                if (attempts >= MAX_ATTEMPTS)
                    jdbc.update("UPDATE workflow_mail_outbox SET status='FAILED',last_error=? WHERE id=?", cut(ex.getMessage(), 1000), id);
                else
                    jdbc.update("UPDATE workflow_mail_outbox SET status='PENDING',last_error=?,next_attempt_at=? WHERE id=?",
                            cut(ex.getMessage(), 1000), OffsetDateTime.now().plusMinutes(5L << Math.min(4, attempts - 1)), id);
            }
        }
    }

    private void send(String to, String cc, String bcc, String subject, String html) throws Exception {
        var mime = sender.createMimeMessage();
        var helper = new MimeMessageHelper(mime, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        if (cc != null && !cc.isBlank()) helper.setCc(split(cc));
        if (bcc != null && !bcc.isBlank()) helper.setBcc(split(bcc));
        helper.setSubject(subject);
        helper.setText(html, true);
        sender.send(mime);
    }

    private static String[] split(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static boolean isRichText(String text) {
        return text != null && java.util.regex.Pattern.compile("<(p|h[1-6]|table|ul|ol|blockquote)(\\s|>)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    static String plainMessage(String text) {
        return isRichText(text) ? org.jsoup.Jsoup.parse(renderMessage(text)).text() : text;
    }

    /** Allow only editor formatting; never keep scripts, user styles or event attributes. */
    static String renderMessage(String text) {
        if (text == null) return "";
        if (isRichText(text)) {
            var allowed = new org.jsoup.safety.Safelist()
                    .addTags("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "s", "ul", "ol", "li", "blockquote", "pre", "code", "hr", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "a")
                    .addAttributes("a", "href").addProtocols("a", "href", "https", "http", "mailto");
            var document = org.jsoup.Jsoup.parseBodyFragment(org.jsoup.Jsoup.clean(text, allowed));
            document.select("table").attr("style", "border-collapse:collapse;width:100%;margin:16px 0;white-space:normal");
            document.select("th,td").attr("style", "border:1px solid #cbd5e1;padding:8px;text-align:left;vertical-align:top");
            document.outputSettings().prettyPrint(false);
            return document.body().html();
        }
        var html = new StringBuilder();
        boolean table = false;
        boolean header = false;
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1) {
                String[] cells = trimmed.substring(1, trimmed.length()-1).split("\\|", -1);
                if (Arrays.stream(cells).allMatch(cell -> cell.trim().matches(":?-{3,}:?"))) continue;
                if (!table) {
                    html.append("<table style=\"border-collapse:collapse;width:100%;margin:16px 0;white-space:normal\">");
                    table = true;
                    header = true;
                }
                html.append("<tr>");
                String tag = header ? "th" : "td";
                for (String cell : cells) html.append("<").append(tag)
                        .append(" style=\"border:1px solid #cbd5e1;padding:8px;text-align:left;vertical-align:top\">")
                        .append(escape(cell.trim())).append("</").append(tag).append(">");
                html.append("</tr>");
                header = false;
            } else {
                if (table) { html.append("</table>"); table = false; }
                html.append(escape(line)).append("\n");
            }
        }
        if (table) html.append("</table>");
        return html.toString();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    static String cut(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}

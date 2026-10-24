package com.example.graduationprocessbe.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single entry point for every notification: writes the in-app inbox row and queues the mail.
 * Callers publish inside their own transaction; delivery happens after commit in a separate
 * transaction, so a mail/template problem can never roll back the business action.
 */
@Slf4j
@Service
public class NotificationService {
    public static final Set<String> VARIABLES = Set.of("tenNguoiNhan", "maSinhVien", "tenDot", "tenMoc", "batDau",
            "hanChot", "link", "tenHoSo", "ketQua", "nhanXet", "phong", "hoiDong");
    public static final Set<String> PRIORITIES = Set.of("HIGH", "NORMAL", "LOW");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final WorkflowMailService mail;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate separateTx;

    public NotificationService(JdbcTemplate jdbc, WorkflowMailService mail, ApplicationEventPublisher events,
                               PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.mail = mail;
        this.events = events;
        this.separateTx = new TransactionTemplate(txManager);
        this.separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * templateCode is used when subject/body are null. ref = roundId:stepKey:eventKey is the dedup key per user,
     * so the same reminder is never shown or mailed twice.
     */
    public record Notice(String templateCode, String category, String roundId, String stepKey, String eventKey,
                         Collection<String> userIds, Map<String, String> vars, String link,
                         String subject, String body, String priority, String cc, String bcc) {
        public static Notice of(String templateCode, String category, String roundId, String stepKey, String eventKey,
                                Collection<String> userIds, Map<String, String> vars, String link) {
            return new Notice(templateCode, category, roundId, stepKey, eventKey, userIds, vars, link,
                    null, null, null, null, null);
        }
    }

    public record Published(Notice notice) {}

    public void publish(Notice notice) {
        if (notice.userIds() == null || notice.userIds().isEmpty()) return;
        events.publishEvent(new Published(notice));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPublished(Published event) {
        try {
            separateTx.executeWithoutResult(status -> deliver(event.notice()));
        } catch (Exception ex) {
            log.warn("Không tạo được thông báo {}: {}", event.notice().eventKey(), ex.getMessage());
        }
    }

    /** Runs in the caller's transaction; returns how many users received a new notification. */
    public int deliver(Notice n) {
        String subject = n.subject(), body = n.body(), priority = n.priority();
        if (subject == null || body == null) {
            var template = jdbc.queryForList("SELECT subject,body,priority FROM notification_templates WHERE code=?", n.templateCode());
            if (template.isEmpty()) throw new IllegalStateException("Không có mẫu thông báo " + n.templateCode());
            subject = (String) template.getFirst().get("subject");
            body = (String) template.getFirst().get("body");
            if (priority == null) priority = (String) template.getFirst().get("priority");
        }
        if (priority == null || !PRIORITIES.contains(priority)) priority = "NORMAL";
        String ref = WorkflowMailService.cut(Objects.toString(n.roundId(), "-") + ":" + Objects.toString(n.stepKey(), "-")
                + ":" + n.eventKey(), 200);
        List<Map<String, Object>> users = activeUsers(n.userIds());
        boolean sendMail = mail.isEnabled() && !"LOW".equals(priority);
        boolean singleRecipient = users.size() == 1;
        int created = 0;
        String lastSubject = null, lastBody = null;
        for (Map<String, Object> user : users) {
            var vars = new HashMap<String, String>();
            if (n.vars() != null) vars.putAll(n.vars());
            String username = (String) user.get("username");
            String fullName = (String) user.get("full_name");
            vars.put("tenNguoiNhan", fullName == null || fullName.isBlank() ? username : fullName);
            vars.put("maSinhVien", username);
            vars.put("link", mail.absolute(n.link()));
            String title = WorkflowMailService.cut(render(subject, vars), 255);
            String text = renderBody(body, vars);
            int inserted = jdbc.update("INSERT INTO notifications(id,user_id,title,body,link,priority,category,ref_key) " +
                            "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id,ref_key) DO NOTHING",
                    UUID.randomUUID().toString(), user.get("id"), title, WorkflowMailService.plainMessage(text),
                    WorkflowMailService.cut(n.link(), 500), priority, n.category(), ref);
            if (inserted == 0) continue;
            created++;
            lastSubject = title;
            lastBody = text;
            String email = (String) user.get("email");
            if (sendMail && email != null && !email.isBlank())
                mail.enqueue(new WorkflowMailService.Mail(n.roundId(), n.stepKey(), n.eventKey(), (String) user.get("id"),
                        email, title, text, n.link(), priority,
                        singleRecipient ? n.cc() : null, singleRecipient ? n.bcc() : null));
        }
        // With many recipients CC/BCC would leak one personal mail to them per student; send a single copy instead.
        if (sendMail && !singleRecipient && created > 0) {
            var copies = new LinkedHashSet<String>();
            for (String list : new String[]{n.cc(), n.bcc()})
                if (list != null) for (String a : list.split(",")) if (!a.isBlank()) copies.add(a.trim());
            for (String address : copies)
                mail.enqueue(new WorkflowMailService.Mail(n.roundId(), n.stepKey(), n.eventKey() + ":COPY", null, address,
                        "[Bản sao] " + lastSubject, "Bản sao thông báo đã gửi tới " + created + " người nhận.\n\n" + lastBody,
                        n.link(), priority, null, null));
        }
        return created;
    }

    /**
     * Everyone in a round for an audience: STUDENT (members), LECTURER (active round lecturers),
     * FACULTY_STAFF / COMMITTEE (round-scoped role), ALL or null (members + lecturers).
     */
    public List<String> roundAudience(String roundId, String audience) {
        String students = "SELECT user_id FROM round_notification_students WHERE round_id=:round";
        String lecturers = "SELECT rl.lecturer_id FROM round_lecturers rl WHERE rl.round_id=:round AND rl.active=true";
        String sql = switch (audience == null ? "ALL" : audience) {
            case "STUDENT" -> students;
            case "LECTURER" -> lecturers;
            case "FACULTY_STAFF", "COMMITTEE" -> "SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id=ur.role_id " +
                    "WHERE r.role_code=:role AND ur.thesis_round_id=:round";
            default -> students + " UNION " + lecturers;
        };
        return named.queryForList("SELECT DISTINCT user_id FROM (" + sql + ") a(user_id) WHERE user_id IS NOT NULL",
                Map.of("round", roundId, "role", Objects.toString(audience, "")), String.class);
    }

    private List<Map<String, Object>> activeUsers(Collection<String> ids) {
        var unique = new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(Objects::nonNull).toList()));
        var result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < unique.size(); i += 1000)
            result.addAll(named.queryForList("SELECT id,username,full_name,email FROM users WHERE status='ACTIVE' AND id IN (:ids)",
                    Map.of("ids", unique.subList(i, Math.min(unique.size(), i + 1000)))));
        return result;
    }

    public Map<String,Object> audienceSummary(String roundId,String audience) {
        if (audience!=null && !RoundMailManagementService.AUDIENCES.contains(audience))
            throw new IllegalArgumentException("Đối tượng nhận không hợp lệ");
        var users=activeUsers(roundAudience(roundId,audience==null?"STUDENT":audience));
        long emails=users.stream().filter(user -> user.get("email") instanceof String email && !email.isBlank()).count();
        return Map.of("recipientCount",users.size(),"emailCount",emails,"missingEmailCount",users.size()-emails);
    }

    /** Replaces known placeholders; unknown ones become empty so a typo never reaches the reader as raw "{{x}}". */
    public static String render(String template, Map<String, String> vars) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        var out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(Objects.toString(vars.get(m.group(1)), "")));
        m.appendTail(out);
        return out.toString();
    }

    public static String renderBody(String template, Map<String, String> vars) {
        if (!WorkflowMailService.isRichText(template)) return render(template, vars);
        var escaped = new HashMap<String, String>();
        vars.forEach((key, value) -> escaped.put(key, WorkflowMailService.escape(value)));
        return render(template, escaped);
    }

    /** Rejects placeholders the system cannot fill so admins see the typo when saving. */
    public static void validate(String text, String label) {
        if (text == null) return;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find())
            if (!VARIABLES.contains(m.group(1)))
                throw new IllegalArgumentException(label + " dùng biến không hỗ trợ: {{" + m.group(1) + "}}");
    }

    /** Sample values for preview and test-send. */
    public static Map<String, String> sampleVars() {
        return Map.ofEntries(Map.entry("tenNguoiNhan", "Nguyễn Văn A"), Map.entry("maSinhVien", "20210001"),
                Map.entry("tenDot", "Đợt ĐATN HK1 2026-2027"), Map.entry("tenMoc", "Nộp đề cương"),
                Map.entry("batDau", "01/11/2026 08:00"), Map.entry("hanChot", "15/11/2026 17:00"),
                Map.entry("link", "/tasks"), Map.entry("tenHoSo", "Hệ thống quản lý đồ án"),
                Map.entry("ketQua", "đã được duyệt"), Map.entry("nhanXet", "Đề cương rõ ràng."),
                Map.entry("phong", "A2-301"), Map.entry("hoiDong", "Hội đồng 1"));
    }
}

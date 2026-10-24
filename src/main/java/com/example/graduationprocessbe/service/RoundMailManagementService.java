package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoundMailManagementService {
    public static final Set<String> AUDIENCES = Set.of("STUDENT", "LECTURER", "ALL");

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final WorkflowMailService mail;

    public List<Map<String,Object>> recipientStudents(String roundId) {
        requireRound(roundId);
        return jdbc.queryForList("SELECT u.id,u.username,u.full_name,u.email,u.status," +
                "EXISTS(SELECT 1 FROM round_notification_students n WHERE n.round_id=? AND n.user_id=u.id) AS selected " +
                "FROM users u WHERE u.user_type='STUDENT' ORDER BY u.full_name,u.username",roundId);
    }

    @Transactional
    public void saveRecipientStudents(String roundId,List<String> userIds) {
        requireRound(roundId);
        if (userIds==null || userIds.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Danh sách sinh viên không hợp lệ");
        jdbc.queryForList("SELECT id FROM thesis_rounds WHERE id=? FOR UPDATE",roundId);
        var unique=new java.util.LinkedHashSet<>(userIds);
        for (String id:unique) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND user_type='STUDENT')",Boolean.class,id)))
                throw new IllegalArgumentException("Danh sách chứa tài khoản không phải sinh viên");
        }
        jdbc.update("DELETE FROM round_notification_students WHERE round_id=?",roundId);
        jdbc.batchUpdate("INSERT INTO round_notification_students(round_id,user_id) VALUES(?,?)",
                unique.stream().map(id -> new Object[]{roundId,id}).toList());
    }

    public record CampaignRequest(String subject, String message, OffsetDateTime scheduledAt, String audience,
                                  String stepKey, String priority, String cc, String bcc,
                                  String mode, String draftId) {}

    public record ReminderInput(int beforeOpenHours,int beforeCloseHours,int afterCloseHours) {}

    @Transactional
    public void saveReminder(String roundId,String stepKey,ReminderInput input) {
        if (input==null || input.beforeOpenHours()<0 || input.beforeOpenHours()>720 || input.beforeCloseHours()<0
                || input.beforeCloseHours()>720 || input.afterCloseHours()<0 || input.afterCloseHours()>720)
            throw new IllegalArgumentException("Số giờ nhắc phải từ 0 đến 720; 0 để tắt");
        int updated=jdbc.update("UPDATE round_step_windows SET before_open_hours=?,before_close_hours=?,after_close_hours=? WHERE round_id=? AND step_key=?",
                input.beforeOpenHours(),input.beforeCloseHours(),input.afterCloseHours(),roundId,stepKey);
        if (updated==0) throw new IllegalArgumentException("Không tìm thấy mốc thời gian");
    }

    public Map<String,Object> preview(String roundId, CampaignRequest input) {
        requireRound(roundId);
        validateContent(input);
        var vars=variables(roundId,input.stepKey());
        vars.put("tenNguoiNhan","Sinh viên");
        vars.put("maSinhVien","Mã sinh viên");
        vars.put("link",mail.absolute("/dashboard"));
        String subject=NotificationService.render(input.subject(),vars);
        String body=NotificationService.renderBody(input.message(),vars);
        var result=new HashMap<String,Object>();
        result.put("subject",subject);
        result.put("html",mail.render(subject,body,"/dashboard"));
        result.putAll(notifications.audienceSummary(roundId,input.audience()));
        result.put("mailEnabled",mail.isEnabled());
        return result;
    }

    private void validateContent(CampaignRequest input) {
        if (input==null || input.subject()==null || input.subject().isBlank() || input.subject().length()>255
                || input.message()==null || input.message().isBlank())
            throw new IllegalArgumentException("Cần tiêu đề (tối đa 255 ký tự) và nội dung");
        NotificationService.validate(input.subject(),"Tiêu đề");
        NotificationService.validate(input.message(),"Nội dung");
    }

    private HashMap<String,String> variables(String roundId,String stepKey) {
        var vars=new HashMap<String,String>();
        vars.put("tenDot",jdbc.queryForObject("SELECT name FROM thesis_rounds WHERE id=?",String.class,roundId));
        if (stepKey!=null && !stepKey.isBlank()) {
            var slots=jdbc.queryForList("SELECT w.opens_at,w.closes_at,ws.label FROM round_step_windows w " +
                    "JOIN thesis_rounds r ON r.id=w.round_id JOIN workflow_templates wt ON wt.process_definition_id=r.workflow_definition_id " +
                    "JOIN workflow_steps ws ON ws.template_id=wt.id AND ws.step_key=w.step_key WHERE w.round_id=? AND w.step_key=?",roundId,stepKey);
            if (slots.isEmpty()) throw new IllegalArgumentException("Mốc chưa được cấu hình trong đợt");
            var slot=slots.getFirst();
            vars.put("tenMoc",String.valueOf(slot.get("label")));
            vars.put("batDau",RoundReminderService.format(RoundReminderService.asOffset(slot.get("opens_at"))));
            vars.put("hanChot",RoundReminderService.format(RoundReminderService.asOffset(slot.get("closes_at"))));
        }
        return vars;
    }

    public Map<String,Object> settings(String roundId) {
        requireRound(roundId);
        return jdbc.queryForMap("SELECT ? AS round_id,coalesce((SELECT remind_before_hours FROM round_mail_settings WHERE round_id=?),48) AS remind_before_hours," +
                "coalesce((SELECT remind_after_hours FROM round_mail_settings WHERE round_id=?),24) AS remind_after_hours",roundId,roundId,roundId);
    }

    @Transactional
    public void saveSettings(String roundId, int before, int after) {
        requireRound(roundId);
        if (before<0 || before>720 || after<0 || after>720)
            throw new IllegalArgumentException("Thời gian nhắc phải từ 0 đến 720 giờ; 0 nghĩa là tắt");
        jdbc.update("INSERT INTO round_mail_settings(round_id,remind_before_hours,remind_after_hours) VALUES(?,?,?) " +
                "ON CONFLICT(round_id) DO UPDATE SET remind_before_hours=EXCLUDED.remind_before_hours," +
                "remind_after_hours=EXCLUDED.remind_after_hours,updated_at=now()",roundId,before,after);
        jdbc.update("DELETE FROM workflow_mail_outbox WHERE round_id=? AND status='PENDING' " +
                "AND (event_key LIKE 'BEFORE_%' OR event_key LIKE 'OVERDUE_%')",roundId);
    }

    public List<Map<String,Object>> campaigns(String roundId) {
        requireRound(roundId);
        return jdbc.queryForList("SELECT c.id,c.subject,c.message,c.scheduled_at,c.status,c.recipient_count,c.created_at,c.queued_at," +
                "c.audience,c.step_key,c.priority,c.cc,c.bcc,u.full_name AS created_by_name " +
                "FROM round_mail_campaigns c LEFT JOIN users u ON u.id=c.created_by WHERE c.round_id=? ORDER BY c.created_at DESC",roundId);
    }

    public List<Map<String,Object>> deliveries(String roundId) {
        requireRound(roundId);
        return jdbc.queryForList("SELECT id,recipient_user_id,recipient_email,cc,subject,priority,event_key,status,attempts,last_error,created_at,sent_at " +
                "FROM workflow_mail_outbox WHERE round_id=? ORDER BY created_at DESC LIMIT 500",roundId);
    }

    @Transactional
    public String schedule(String roundId, CampaignRequest input, String createdBy) {
        requireRound(roundId);
        validateContent(input);
        String mode=input.mode()==null ? "SCHEDULED" : input.mode();
        if (!Set.of("DRAFT","NOW","SCHEDULED").contains(mode)) throw new IllegalArgumentException("Cách gửi không hợp lệ");
        if ("SCHEDULED".equals(mode) && (input.scheduledAt()==null || !input.scheduledAt().isAfter(OffsetDateTime.now())))
            throw new IllegalArgumentException("Chọn thời gian gửi trong tương lai");
        OffsetDateTime scheduledAt="SCHEDULED".equals(mode) ? input.scheduledAt() : OffsetDateTime.now();
        String audience=input.audience()==null || input.audience().isBlank() ? "STUDENT" : input.audience();
        if (!AUDIENCES.contains(audience)) throw new IllegalArgumentException("Người nhận phải là STUDENT, LECTURER hoặc ALL");
        String priority=input.priority()==null || input.priority().isBlank() ? "NORMAL" : input.priority();
        if (!NotificationService.PRIORITIES.contains(priority)) throw new IllegalArgumentException("Mức ưu tiên không hợp lệ");
        String stepKey=input.stepKey()==null || input.stepKey().isBlank() ? null : input.stepKey();
        if (stepKey!=null) {
            Integer count=jdbc.queryForObject("SELECT count(*) FROM round_step_windows WHERE round_id=? AND step_key=?",
                    Integer.class,roundId,stepKey);
            if (count==null || count==0) throw new IllegalArgumentException("Mốc đã chọn chưa được cấu hình thời gian trong đợt");
        }
        NotificationService.validate(input.subject(),"Tiêu đề");
        NotificationService.validate(input.message(),"Nội dung");
        String cc=WorkflowMailService.normalizeAddresses(input.cc(),"CC");
        String bcc=WorkflowMailService.normalizeAddresses(input.bcc(),"BCC");
        String id=input.draftId()==null ? UUID.randomUUID().toString() : input.draftId();
        String status="DRAFT".equals(mode) ? "DRAFT" : "SCHEDULED";
        if (input.draftId()==null) {
            jdbc.update("INSERT INTO round_mail_campaigns(id,round_id,subject,message,scheduled_at,audience,step_key,priority,cc,bcc,created_by,status) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,roundId,input.subject().trim(),input.message().trim(),scheduledAt,audience,stepKey,priority,cc,bcc,createdBy,status);
        } else {
            int updated=jdbc.update("UPDATE round_mail_campaigns SET subject=?,message=?,scheduled_at=?,audience=?,step_key=?,priority=?,cc=?,bcc=?,status=?,created_by=? " +
                            "WHERE id=? AND round_id=? AND status='DRAFT'",input.subject().trim(),input.message().trim(),scheduledAt,audience,stepKey,priority,cc,bcc,status,createdBy,id,roundId);
            if (updated==0) throw new IllegalArgumentException("Bản nháp đã được gửi hoặc không còn tồn tại");
        }
        if ("NOW".equals(mode)) {
            int created=notifications.deliver(new NotificationService.Notice(null,"CAMPAIGN",roundId,stepKey==null?"campaign":stepKey,
                    "CAMPAIGN_"+id,notifications.roundAudience(roundId,audience),variables(roundId,stepKey),"/dashboard",
                    input.subject().trim(),input.message().trim(),priority,cc,bcc));
            if (created==0) throw new IllegalArgumentException("Không có người nhận đang hoạt động trong đợt");
            jdbc.update("UPDATE round_mail_campaigns SET status='QUEUED',recipient_count=?,queued_at=now() WHERE id=?",created,id);
        }
        return id;
    }

    @Transactional
    public void cancel(String roundId, String campaignId) {
        int updated=jdbc.update("UPDATE round_mail_campaigns SET status='CANCELLED' WHERE id=? AND round_id=? AND status='SCHEDULED'",
                campaignId,roundId);
        if (updated==0) throw new IllegalArgumentException("Chỉ hủy được thông báo đang chờ gửi");
    }

    @Scheduled(initialDelayString="PT1M", fixedDelayString="PT1M")
    @Transactional
    public void queueScheduled() {
        for (var campaign:jdbc.queryForList("SELECT c.id,c.round_id,c.subject,c.message,c.audience,c.step_key,c.priority,c.cc,c.bcc," +
                "r.name AS round_name,w.opens_at,w.closes_at," +
                "(SELECT ws.label FROM workflow_templates wt JOIN workflow_steps ws ON ws.template_id=wt.id " +
                " WHERE wt.process_definition_id=r.workflow_definition_id AND ws.step_key=c.step_key LIMIT 1) AS step_name " +
                "FROM round_mail_campaigns c JOIN thesis_rounds r ON r.id=c.round_id " +
                "LEFT JOIN round_step_windows w ON w.round_id=c.round_id AND w.step_key=c.step_key " +
                "WHERE c.status='SCHEDULED' AND c.scheduled_at<=now() FOR UPDATE OF c SKIP LOCKED")) {
            String id=(String)campaign.get("id"), roundId=(String)campaign.get("round_id");
            String stepKey=(String)campaign.get("step_key");
            var vars=new HashMap<String,String>();
            vars.put("tenDot",(String)campaign.get("round_name"));
            if (campaign.get("step_name")!=null) vars.put("tenMoc",(String)campaign.get("step_name"));
            if (campaign.get("opens_at")!=null) vars.put("batDau",RoundReminderService.format(RoundReminderService.asOffset(campaign.get("opens_at"))));
            if (campaign.get("closes_at")!=null) vars.put("hanChot",RoundReminderService.format(RoundReminderService.asOffset(campaign.get("closes_at"))));
            List<String> users=notifications.roundAudience(roundId,(String)campaign.get("audience"));
            int created=notifications.deliver(new NotificationService.Notice(null,"CAMPAIGN",roundId,stepKey==null?"campaign":stepKey,
                        "CAMPAIGN_"+id,users,vars,"/dashboard",(String)campaign.get("subject"),(String)campaign.get("message"),
                        (String)campaign.get("priority"),(String)campaign.get("cc"),(String)campaign.get("bcc")));
            jdbc.update("UPDATE round_mail_campaigns SET status='QUEUED',recipient_count=?,queued_at=now() WHERE id=?",created,id);
        }
    }

    private void requireRound(String roundId) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM thesis_rounds WHERE id=?",Integer.class,roundId);
        if (count==null || count==0) throw new IllegalArgumentException("Không tìm thấy đợt ĐATN");
    }
}

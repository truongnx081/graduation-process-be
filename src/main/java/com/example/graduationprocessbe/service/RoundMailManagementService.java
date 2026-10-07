package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoundMailManagementService {
    private final JdbcTemplate jdbc;
    private final WorkflowMailService mail;
    @Value("${app.mail.enabled:false}") private boolean mailEnabled;

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
        return jdbc.queryForList("SELECT id,subject,message,scheduled_at,status,recipient_count,created_at,queued_at " +
                "FROM round_mail_campaigns WHERE round_id=? ORDER BY created_at DESC",roundId);
    }

    public List<Map<String,Object>> deliveries(String roundId) {
        requireRound(roundId);
        return jdbc.queryForList("SELECT id,recipient_email,subject,status,attempts,last_error,created_at,sent_at " +
                "FROM workflow_mail_outbox WHERE round_id=? ORDER BY created_at DESC LIMIT 500",roundId);
    }

    @Transactional
    public String schedule(String roundId, String subject, String message, OffsetDateTime when) {
        requireRound(roundId);
        if (subject==null || subject.isBlank() || subject.length()>255 || message==null || message.isBlank() || when==null)
            throw new IllegalArgumentException("Cần tiêu đề, nội dung và thời gian gửi hợp lệ");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO round_mail_campaigns(id,round_id,subject,message,scheduled_at) VALUES(?,?,?,?,?)",
                id,roundId,subject.trim(),message.trim(),when);
        return id;
    }

    @Scheduled(fixedDelayString="PT1M")
    @Transactional
    public void queueScheduled() {
        if (!mailEnabled) return;
        for (var campaign:jdbc.queryForList("SELECT c.id,c.round_id,c.subject,c.message,r.name AS round_name " +
                "FROM round_mail_campaigns c JOIN thesis_rounds r ON r.id=c.round_id " +
                "WHERE c.status='SCHEDULED' AND c.scheduled_at<=now() FOR UPDATE OF c SKIP LOCKED")) {
            String id=(String)campaign.get("id"), roundId=(String)campaign.get("round_id");
            List<String> recipients=jdbc.queryForList("SELECT DISTINCT u.email FROM members m JOIN users u ON u.id=m.user_id " +
                    "WHERE m.thesis_round_id=? AND u.user_type='STUDENT' AND u.status='ACTIVE' " +
                    "AND u.email IS NOT NULL AND u.email<>''",String.class,roundId);
            for (String recipient:recipients) mail.enqueue(roundId,"campaign","CAMPAIGN_"+id,recipient,
                    (String)campaign.get("subject"),(String)campaign.get("message"),
                    (String)campaign.get("round_name"),"Thông báo chung","Theo thông báo");
            jdbc.update("UPDATE round_mail_campaigns SET status='QUEUED',recipient_count=?,queued_at=now() WHERE id=?",recipients.size(),id);
        }
    }

    private void requireRound(String roundId) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM thesis_rounds WHERE id=?",Integer.class,roundId);
        if (count==null || count==0) throw new IllegalArgumentException("Không tìm thấy đợt ĐATN");
    }
}

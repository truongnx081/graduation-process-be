package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RoundReminderService {
    private final JdbcTemplate jdbc;
    private final WorkflowMailService mail;
    @Value("${app.mail.enabled:false}") private boolean mailEnabled;

    /** Shared round deadlines are mailed once to every enrolled student and participating adviser. */
    @Scheduled(fixedDelayString = "PT15M")
    public void collectDueReminders() {
        if (!mailEnabled) return;
        List<Map<String,Object>> windows=jdbc.queryForList("SELECT w.round_id,w.step_key,w.closes_at,r.name AS round_name,ws.label AS step_name, " +
                "coalesce(ms.remind_before_hours,48) AS before_hours,coalesce(ms.remind_after_hours,24) AS after_hours " +
                "FROM round_step_windows w JOIN thesis_rounds r ON r.id=w.round_id " +
                "LEFT JOIN round_mail_settings ms ON ms.round_id=r.id " +
                "JOIN workflow_templates wt ON wt.process_definition_id=r.workflow_definition_id " +
                "JOIN workflow_steps ws ON ws.template_id=wt.id AND ws.step_key=w.step_key " +
                "WHERE r.active=true AND w.closes_at>now()-interval '30 days' AND w.closes_at<now()+interval '30 days'");
        OffsetDateTime now=OffsetDateTime.now();
        for (var window: windows) {
            OffsetDateTime closes=asOffset(window.get("closes_at"));
            int before=((Number)window.get("before_hours")).intValue();
            int after=((Number)window.get("after_hours")).intValue();
            if (before>0 && now.isAfter(closes.minusHours(before)) && now.isBefore(closes))
                enqueue(window,"BEFORE_"+before+"H","Sắp đến hạn");
            if (after>0 && now.isAfter(closes.plusHours(after)))
                enqueue(window,"OVERDUE_"+after+"H","Đã quá hạn");
        }
    }

    private void enqueue(Map<String,Object> window,String eventKey,String eventLabel) {
        String roundId=(String)window.get("round_id"), stepKey=(String)window.get("step_key");
        String subject="[ĐATN] "+eventLabel+": "+window.get("step_name");
        String deadline=asOffset(window.get("closes_at")).atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        String body=eventLabel+" cho bước “"+window.get("step_name")+"” của "+window.get("round_name")+".";
        List<String> recipients=jdbc.queryForList(
                "SELECT DISTINCT email FROM ("+
                "SELECT u.email FROM members m JOIN users u ON u.id=m.user_id WHERE m.thesis_round_id=? AND u.status='ACTIVE' "+
                "UNION SELECT u.email FROM round_lecturers rl JOIN users u ON u.id=rl.lecturer_id WHERE rl.round_id=? AND rl.active=true AND u.status='ACTIVE'"+
                ") recipients WHERE email IS NOT NULL AND email<>''",String.class,roundId,roundId);
        for (String recipient:recipients) mail.enqueue(roundId,stepKey,eventKey,recipient,subject,body,
                (String)window.get("round_name"),(String)window.get("step_name"),deadline);
    }

    private OffsetDateTime asOffset(Object value) {
        if (value instanceof OffsetDateTime time) return time;
        return ((java.sql.Timestamp)value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}

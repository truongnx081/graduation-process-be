package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Milestone notifications for every round window: OPEN when it starts, BEFORE_xH ahead of the deadline and
 * OVERDUE_xH after it. Recipients follow the step's assignee role; the dedup key keeps each event to one send.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundReminderService {
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT15M")
    public void collectDueReminders() {
        List<Map<String, Object>> windows = jdbc.queryForList("SELECT w.round_id,w.step_key,w.opens_at,w.closes_at," +
                "r.name AS round_name,ws.label AS step_name,ws.assignee_role," +
                "w.before_open_hours,coalesce(w.before_close_hours,ms.remind_before_hours,48) AS before_hours," +
                "coalesce(w.after_close_hours,ms.remind_after_hours,24) AS after_hours " +
                "FROM round_step_windows w JOIN thesis_rounds r ON r.id=w.round_id " +
                "LEFT JOIN round_mail_settings ms ON ms.round_id=r.id " +
                "JOIN workflow_templates wt ON wt.process_definition_id=r.workflow_definition_id " +
                "JOIN workflow_steps ws ON ws.template_id=wt.id AND ws.step_key=w.step_key " +
                "WHERE r.active=true AND w.closes_at>now()-interval '30 days' AND w.opens_at<now()+interval '30 days'");
        OffsetDateTime now = OffsetDateTime.now();
        for (var window : windows) {
            OffsetDateTime opens = asOffset(window.get("opens_at"));
            OffsetDateTime closes = asOffset(window.get("closes_at"));
            int before = ((Number) window.get("before_hours")).intValue();
            int after = ((Number) window.get("after_hours")).intValue();
            int beforeOpen = ((Number) window.get("before_open_hours")).intValue();
            if (beforeOpen > 0 && !now.isBefore(opens.minusHours(beforeOpen)) && now.isBefore(opens))
                notify(window, "STEP_UPCOMING", "UPCOMING", opens, closes);
            if (!now.isBefore(opens) && now.isBefore(closes)
                    && opens.isAfter(now.minusDays(3)))
                notify(window, "STEP_OPEN", "OPEN", opens, closes);
            if (before > 0 && !now.isBefore(opens) && now.isAfter(closes.minusHours(before)) && now.isBefore(closes))
                notify(window, "STEP_BEFORE", "BEFORE_" + before + "H", opens, closes);
            if (after > 0 && now.isAfter(closes.plusHours(after)) && now.isBefore(closes.plusHours(after).plusDays(3)))
                notify(window, "STEP_OVERDUE", "OVERDUE_" + after + "H", opens, closes);
        }
    }

    private void notify(Map<String, Object> window, String template, String eventKey, OffsetDateTime opens, OffsetDateTime closes) {
        String roundId = (String) window.get("round_id"), stepKey = (String) window.get("step_key");
        String role = (String) window.get("assignee_role");
        try {
            List<String> users = notifications.roundAudience(roundId, role);
            var vars = Map.of("tenDot", String.valueOf(window.get("round_name")), "tenMoc", String.valueOf(window.get("step_name")),
                    "batDau", format(opens), "hanChot", format(closes));
            // Date changes create a new reminder; repeated polls of the same schedule remain idempotent.
            String versionedKey=eventKey+":"+opens.toEpochSecond()+":"+closes.toEpochSecond();
            notifications.publish(NotificationService.Notice.of(template, "DEADLINE", roundId, stepKey, versionedKey, users, vars, link(role)));
        } catch (Exception ex) {
            log.warn("Không tạo được nhắc hạn {} {} {}: {}", roundId, stepKey, eventKey, ex.getMessage());
        }
    }

    private static String link(String role) {
        if ("STUDENT".equals(role)) return "/student/progress";
        if ("LECTURER".equals(role)) return "/lecturer/advising/proposals";
        return "/tasks";
    }

    static String format(OffsetDateTime time) {
        return time.atZoneSameInstant(VN).format(FORMAT);
    }

    static OffsetDateTime asOffset(Object value) {
        if (value instanceof OffsetDateTime time) return time;
        return ((java.sql.Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }
}

package com.example.graduationprocessbe.config;

import com.example.graduationprocessbe.entity.Thesis;
import com.example.graduationprocessbe.entity.User;
import com.example.graduationprocessbe.repository.ThesisRepository;
import com.example.graduationprocessbe.repository.UserRepository;
import com.example.graduationprocessbe.service.AuditLogService;
import com.example.graduationprocessbe.service.CoreWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Optional demo catalog and real, resumable Flowable scenarios. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataSeeder {
    private static final String VERSION = "demo-comprehensive-v5";
    private final JdbcTemplate jdbc;
    private final CoreWorkflowService core;
    private final UserRepository users;
    private final ThesisRepository theses;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final AuditLogService audit;

    @Transactional
    public void seed() {
        jdbc.execute("SELECT pg_advisory_xact_lock(20261007, 25)");
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_seed_versions WHERE version=?)", Boolean.class, VERSION))) return;
        // The single initialization script prepares demo tables before the template is published.
        jdbc.update("UPDATE thesis_rounds SET workflow_definition_id=" +
                "(SELECT process_definition_id FROM workflow_templates WHERE id='core-template-v2') " +
                "WHERE id IN (SELECT id FROM demo_rounds) AND workflow_definition_id IS NULL");
        var steps = core.steps("core-template-v2").stream()
                .collect(Collectors.toMap(s -> (String) s.get("step_key"), Function.identity()));
        var scenarios = jdbc.queryForList("SELECT p.*,r.id AS round_id,r.workflow_definition_id " +
                "FROM demo_case_plan p JOIN demo_rounds d ON d.slot=p.round_slot " +
                "JOIN thesis_rounds r ON r.id=d.id ORDER BY p.case_no");
        int created = 0;
        for (var scenario : scenarios) {
            if (seedCase(scenario, steps)) created++;
        }
        if (scenarios.size() < 15) log.warn("Demo: skipped scenarios for semesters already occupied by other rounds.");
        jdbc.update("INSERT INTO app_seed_versions(version) VALUES(?)", VERSION);
        log.info("Demo initialization completed: {} new cases, {} scenarios available.", created, scenarios.size());
    }

    private boolean seedCase(Map<String, Object> plan, Map<String, Map<String, Object>> steps) {
        String roundId = (String) plan.get("round_id");
        User student = user((String) plan.get("student_username"));
        User lecturer = user((String) plan.get("lecturer_username"));
        var members = new ArrayList<User>();
        members.add(student);
        if (plan.get("partner_username") instanceof String partner) members.add(user(partner));
        for (User member : members) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM members WHERE thesis_round_id=? AND user_id=?)",
                    Boolean.class, roundId, member.getId()))) return false;
        }
        Thesis thesis = new Thesis();
        thesis.setTitle((String) plan.get("title"));
        thesis.setDescription((String) plan.get("summary"));
        thesis.setStudent(student);
        thesis.setLecturer(lecturer);
        thesis.setPhaseId(roundId);
        thesis = theses.saveAndFlush(thesis);
        for (User member : members) {
            jdbc.update("INSERT INTO members(thesis_id,user_id,thesis_round_id) VALUES(?,?,?)",
                    thesis.getId(), member.getId(), roundId);
        }
        var instance = runtime.startProcessInstanceById((String) plan.get("workflow_definition_id"),
                thesis.getId(), Map.of("thesisId", thesis.getId(), "studentId", student.getId(),
                        "studentIds", members.stream().map(User::getId).collect(Collectors.joining(",")),
                        "lecturerId", lecturer.getId(), "studentEmail", student.getEmail()));
        thesis.setProcessInstanceId(instance.getId());
        theses.saveAndFlush(thesis);
        audit.record(instance.getId(), student, "DEMO_CREATE", "submitProposal",
                Map.of("title", thesis.getTitle(), "demo", true));

        boolean rejectOnce = Boolean.TRUE.equals(plan.get("reject_proposal"));
        String target = (String) plan.get("target_step");
        for (int count = 0; count < 40; count++) {
            Task task = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
            if (task == null) {
                if (!"COMPLETED".equals(target)) throw new IllegalStateException("Demo ended before " + target);
                thesis.setCurrentStatus("COMPLETED");
                theses.saveAndFlush(thesis);
                return true;
            }
            String key = task.getTaskDefinitionKey();
            if (key.equals(target) && !rejectOnce) {
                int overdue = ((Number) plan.get("overdue_days")).intValue();
                if (overdue > 0) tasks.setDueDate(task.getId(), Date.from(Instant.now().minus(overdue, ChronoUnit.DAYS)));
                thesis.setCurrentStatus(key);
                theses.saveAndFlush(thesis);
                return true;
            }
            boolean approved = !(rejectOnce && "reviewProposal".equals(key));
            completeDemoTask(thesis, task, steps.get(key), approved, plan);
            if (!approved) rejectOnce = false;
        }
        throw new IllegalStateException("Demo workflow did not reach " + target);
    }

    private void completeDemoTask(Thesis thesis, Task task, Map<String, Object> step,
                                  boolean approved, Map<String, Object> plan) {
        if (step == null) throw new IllegalStateException("Missing demo workflow step: " + task.getTaskDefinitionKey());
        String key = task.getTaskDefinitionKey();
        User actor = switch ((String) step.get("assignee_role")) {
            case "STUDENT" -> thesis.getStudent();
            case "LECTURER" -> thesis.getLecturer();
            case "FACULTY_STAFF" -> user("khoa");
            case "COMMITTEE" -> user("committee");
            default -> throw new IllegalStateException("Unsupported demo role");
        };
        String content = submissionContent(key, thesis);
        String comment = approved ? "Đã kiểm tra " + step.get("label") + ". Nội dung đáp ứng yêu cầu; đồng ý chuyển bước."
                : "Cần làm rõ phạm vi dữ liệu, bổ sung tiêu chí đánh giá và kế hoạch thực hiện theo tuần. Vui lòng sửa và nộp lại đề cương.";
        if ("SUBMIT".equals(step.get("kind"))) {
            jdbc.update("INSERT INTO thesis_submissions(id,thesis_id,step_key,submitted_by,content) VALUES(?,?,?,?,?)",
                    UUID.randomUUID().toString(), thesis.getId(), key, actor.getId(), content);
        } else if ("REVIEW".equals(step.get("kind"))) {
            jdbc.update("INSERT INTO thesis_feedback(id,thesis_id,step_key,reviewer_id,approved,comment) VALUES(?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), thesis.getId(), key, actor.getId(), approved, comment);
        }
        if ("confirmGuidance".equals(key)) {
            thesis.setGuidanceApproved(approved);
            thesis.setGuidanceRespondedAt(LocalDateTime.now());
            thesis.setGuidanceComment(comment);
        }
        if ("scheduleDefense".equals(key)) {
            var defenseAt = OffsetDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                    .plusDays("past".equals(plan.get("round_slot")) ? -150 : 14)
                    .withHour(8).withMinute(30).withSecond(0).withNano(0);
            jdbc.update("INSERT INTO defense_schedules(id,thesis_id,defense_at,room,council_name,notes,published_by) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), thesis.getId(), defenseAt, "Phòng B.203",
                    "Hội đồng ĐATN - Công nghệ Thông tin",
                    "Lịch minh họa: trình bày 15 phút, hỏi đáp 10 phút. Chuẩn bị slide và bản chạy thử.", actor.getId());
        }
        tasks.setAssignee(task.getId(), actor.getId());
        tasks.complete(task.getId(), Map.of("approved", approved, "comment", comment, "content", content));
        audit.record(thesis.getProcessInstanceId(), actor, "DEMO_COMPLETE_TASK", key,
                Map.of("demo", true, "approved", approved, "comment", comment));
    }

    private String submissionContent(String key, Thesis thesis) {
        String detail = switch (key) {
            case "midtermReport" -> "Tiến độ 60%: hoàn tất khảo sát, thiết kế dữ liệu và chức năng cốt lõi. " +
                    "Đã kiểm thử luồng chính. Công việc tiếp theo: hoàn thiện giao diện, kiểm thử tích hợp và đo hiệu năng.";
            case "finalReport" -> "Đã hoàn thành các chức năng theo đề cương, kiểm thử phân quyền và xử lý lỗi. " +
                    "Báo cáo gồm cơ sở lý thuyết, phân tích thiết kế, triển khai, đánh giá và hướng phát triển.";
            case "submitCouncil" -> "Hồ sơ gửi Hội đồng gồm nội dung báo cáo cuối kỳ, tóm tắt kết quả và kế hoạch trình bày. " +
                    "GVHD đã xem xét và đồng ý cho chuyển hồ sơ.";
            case "reviseProposal" -> "Đã bổ sung phạm vi dữ liệu, tiêu chí đánh giá, phân công công việc và kế hoạch theo tuần.";
            case "submitSignedProposal" -> "Đề cương đã được GVHD xác nhận, gửi Khoa xem xét và duyệt chính thức.";
            default -> "Mục tiêu: xây dựng sản phẩm có thể chạy thử và đánh giá được. " +
                    "Kế hoạch: khảo sát 2 tuần, thiết kế 2 tuần, triển khai 6 tuần, kiểm thử và viết báo cáo 2 tuần.";
        };
        return thesis.getTitle() + "\n\n" + thesis.getDescription() + "\n\n" + detail;
    }

    private User user(String username) {
        return users.findByUsername(username).orElseThrow(() -> new IllegalStateException("Missing demo account: " + username));
    }
}

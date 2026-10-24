package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CoreWorkflowService {
    private final JdbcTemplate jdbc;
    private final RepositoryService repositoryService;
    private final TaskService taskService;
    private final CurrentUserService currentUserService;

    public List<Map<String,Object>> years() {
        return jdbc.queryForList("SELECT * FROM academic_years ORDER BY start_year DESC");
    }
    @Transactional
    public void createYear(String code, int startYear, LocalDate startsOn, LocalDate endsOn) {
        if (code == null || startYear < 2020 || startYear > 2100 || !code.equals(startYear + "-" + (startYear + 1))) throw new IllegalArgumentException("Mã năm học không hợp lệ");
        validateYearDates(startYear, startsOn, endsOn);
        validateYearOverlap(null, startsOn, endsOn);
        jdbc.update("INSERT INTO academic_years(id,code,start_year,end_year,starts_on,ends_on) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), code, startYear, startYear + 1, startsOn, endsOn);
    }
    public List<Map<String,Object>> semesters() {
        return jdbc.queryForList("SELECT s.*,y.code AS academic_year_code FROM semesters s JOIN academic_years y ON y.id=s.academic_year_id ORDER BY y.start_year DESC,s.number");
    }
    @Transactional
    public void createSemester(String yearId, int number, LocalDate startsOn, LocalDate endsOn) {
        if (number < 1 || number > 3) throw new IllegalArgumentException("Học kỳ phải từ 1 đến 3");
        validateSemesterDates(yearId, null, startsOn, endsOn);
        jdbc.update("INSERT INTO semesters(id,academic_year_id,number,starts_on,ends_on) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(), yearId, number, startsOn, endsOn);
    }
    @Transactional
    public void updateYear(String id, String code, int startYear, LocalDate startsOn, LocalDate endsOn) {
        if (code == null || startYear < 2020 || startYear > 2100 || !code.equals(startYear + "-" + (startYear + 1)))
            throw new IllegalArgumentException("Mã năm học không hợp lệ");
        validateYearDates(startYear, startsOn, endsOn);
        validateYearOverlap(id, startsOn, endsOn);
        var semesters = jdbc.queryForList("SELECT starts_on,ends_on FROM semesters WHERE academic_year_id=? FOR UPDATE", id);
        for (var semester : semesters) {
            LocalDate first = asDate(semester.get("starts_on"));
            LocalDate last = asDate(semester.get("ends_on"));
            if (first != null && last != null && (first.isBefore(startsOn) || last.isAfter(endsOn)))
                throw new IllegalArgumentException("Thời gian năm học phải bao gồm tất cả học kỳ hiện có");
        }
        if (jdbc.update("UPDATE academic_years SET code=?,start_year=?,end_year=?,starts_on=?,ends_on=? WHERE id=?", code,startYear,startYear+1,startsOn,endsOn,id) == 0)
            throw new IllegalArgumentException("Không tìm thấy năm học");
    }
    @Transactional
    public void updateSemester(String id, String yearId, int number, LocalDate startsOn, LocalDate endsOn) {
        if (number < 1 || number > 3) throw new IllegalArgumentException("Học kỳ phải từ 1 đến 3");
        validateSemesterDates(yearId, id, startsOn, endsOn);
        for (var range : jdbc.queryForList("SELECT registration_opens_at AS opens_at,registration_closes_at AS closes_at FROM thesis_rounds WHERE semester_id=? " +
                "UNION ALL SELECT w.opens_at,w.closes_at FROM round_step_windows w JOIN thesis_rounds r ON r.id=w.round_id WHERE r.semester_id=?",id,id)) {
            CalendarDateRange.validate(RoundReminderService.asOffset(range.get("opens_at")),RoundReminderService.asOffset(range.get("closes_at")),
                    startsOn,endsOn,"Học kỳ phải bao gồm thời gian đăng ký và các mốc của đợt hiện có");
        }
        // Keep the academic year and every referencing round stable during an edit.
        if (jdbc.update("UPDATE semesters SET number=?,starts_on=?,ends_on=? WHERE id=? AND academic_year_id=?", number,startsOn,endsOn,id,yearId) == 0)
            throw new IllegalArgumentException("Không tìm thấy học kỳ thuộc năm học đã chọn");
    }
    private LocalDate asDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return value == null ? null : LocalDate.parse(value.toString());
    }
    private void validateYearDates(int startYear, LocalDate startsOn, LocalDate endsOn) {
        if (startsOn == null || endsOn == null || !startsOn.isBefore(endsOn)
                || startsOn.getYear() != startYear || endsOn.getYear() != startYear + 1)
            throw new IllegalArgumentException("Thời gian năm học phải bắt đầu trong năm đầu, kết thúc trong năm kế tiếp và ngày bắt đầu trước ngày kết thúc");
    }
    private void validateYearOverlap(String editedId, LocalDate startsOn, LocalDate endsOn) {
        var existing = jdbc.queryForList("SELECT id,starts_on,ends_on FROM academic_years FOR UPDATE");
        for (var year : existing) {
            if (year.get("id").equals(editedId)) continue;
            LocalDate first = asDate(year.get("starts_on"));
            LocalDate last = asDate(year.get("ends_on"));
            if (first != null && last != null && !endsOn.isBefore(first) && !startsOn.isAfter(last))
                throw new IllegalArgumentException("Thời gian năm học chồng chéo với năm học khác");
        }
    }
    private void validateSemesterDates(String yearId, String editedId, LocalDate startsOn, LocalDate endsOn) {
        if (startsOn == null || endsOn == null || !startsOn.isBefore(endsOn))
            throw new IllegalArgumentException("Ngày bắt đầu học kỳ phải trước ngày kết thúc");
        var years = jdbc.queryForList("SELECT starts_on,ends_on FROM academic_years WHERE id=? FOR UPDATE", yearId);
        if (years.isEmpty()) throw new IllegalArgumentException("Không tìm thấy năm học");
        LocalDate yearStart = asDate(years.getFirst().get("starts_on"));
        LocalDate yearEnd = asDate(years.getFirst().get("ends_on"));
        if (yearStart == null || yearEnd == null || startsOn.isBefore(yearStart) || endsOn.isAfter(yearEnd))
            throw new IllegalArgumentException("Thời gian học kỳ phải nằm trong thời gian năm học");
        var existing = jdbc.queryForList("SELECT id,starts_on,ends_on FROM semesters WHERE academic_year_id=? FOR UPDATE", yearId);
        for (var semester : existing) {
            if (semester.get("id").equals(editedId)) continue;
            LocalDate first = asDate(semester.get("starts_on"));
            LocalDate last = asDate(semester.get("ends_on"));
            if (first != null && last != null && !endsOn.isBefore(first) && !startsOn.isAfter(last))
                throw new IllegalArgumentException("Thời gian học kỳ chồng chéo với học kỳ khác trong năm học");
        }
    }
    public List<Map<String,Object>> rounds() {
        return jdbc.queryForList("SELECT r.id,r.code,r.name,r.active,r.semester_id,r.registration_opens_at,r.registration_closes_at,r.workflow_definition_id,s.number AS semester_number,s.starts_on AS semester_starts_on,s.ends_on AS semester_ends_on,y.code AS academic_year_code FROM thesis_rounds r LEFT JOIN semesters s ON s.id=r.semester_id LEFT JOIN academic_years y ON y.id=s.academic_year_id ORDER BY y.start_year DESC,s.number,r.code");
    }
    public List<Map<String,Object>> overdueTasks() {
        var actor=currentUserService.getCurrentUser().orElseThrow();
        return taskService.createTaskQuery().active().taskDueBefore(java.util.Date.from(java.time.Instant.now())).list().stream()
            .map(task -> {
                var matches=jdbc.queryForList("SELECT t.id,t.title,t.phase_id,s.full_name AS student_name,l.full_name AS lecturer_name FROM theses t JOIN users s ON s.id=t.student_id JOIN users l ON l.id=t.lecturer_id WHERE t.process_instance_id=?",task.getProcessInstanceId());
                if (matches.isEmpty()) return null;
                Map<String,Object> row=new java.util.HashMap<>(matches.getFirst());
                if (!"ADMIN".equals(actor.getUserType())) {
                    Integer allowed=jdbc.queryForObject("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code='FACULTY_STAFF' AND (ur.thesis_round_id IS NULL OR ur.thesis_round_id=?)",Integer.class,actor.getId(),row.get("phase_id"));
                    if (allowed==null || allowed==0) return null;
                }
                row.put("task_id",task.getId()); row.put("task_name",task.getName()); row.put("due_date",task.getDueDate());
                return row;
            }).filter(java.util.Objects::nonNull).toList();
    }
    public List<Map<String,Object>> windows(String roundId) {
        return jdbc.queryForList("SELECT * FROM round_step_windows WHERE round_id=? ORDER BY step_key",roundId);
    }
    @Transactional
    public void setWindow(String roundId,String stepKey,OffsetDateTime opens,OffsetDateTime closes) {
        if (stepKey==null || opens==null || closes==null || !opens.isBefore(closes))
            throw new IllegalArgumentException("Mốc mở/đóng bước không hợp lệ");
        Integer valid=jdbc.queryForObject("SELECT count(*) FROM thesis_rounds r JOIN workflow_templates wt ON wt.process_definition_id=r.workflow_definition_id JOIN workflow_steps ws ON ws.template_id=wt.id WHERE r.id=? AND ws.step_key=?",Integer.class,roundId,stepKey);
        if (valid==null || valid==0) throw new IllegalArgumentException("Bước thực hiện không thuộc quy trình của đợt");
        String semesterId=jdbc.queryForObject("SELECT semester_id FROM thesis_rounds WHERE id=?",String.class,roundId);
        validateWithinSemester(semesterId,opens,closes,"Mốc thời gian phải nằm trong học kỳ của đợt");
        jdbc.update("INSERT INTO round_step_windows(id,round_id,step_key,opens_at,closes_at) VALUES(?,?,?,?,?) ON CONFLICT(round_id,step_key) DO UPDATE SET opens_at=EXCLUDED.opens_at,closes_at=EXCLUDED.closes_at",
                UUID.randomUUID().toString(),roundId,stepKey,opens,closes);
    }
    @Transactional
    public void createRound(String code, String name, String semesterId, OffsetDateTime opens, OffsetDateTime closes) {
        createRound(code, name, semesterId, opens, closes, true);
    }
    @Transactional
    public void createRound(String code, String name, String semesterId, OffsetDateTime opens, OffsetDateTime closes, boolean active) {
        validateRound(code,name,semesterId);
        validateDates(opens,closes);
        requireAvailableSemester(semesterId, null);
        validateWithinSemester(semesterId,opens,closes,"Thời gian đăng ký đợt phải nằm trong học kỳ đã chọn");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM thesis_rounds WHERE code=?)", Boolean.class, code)))
            throw new IllegalArgumentException("Mã đợt ĐATN đã tồn tại");
        jdbc.update("INSERT INTO thesis_rounds(id,code,name,active,semester_id,registration_opens_at,registration_closes_at,created_date,last_modified_date) VALUES(?,?,?,?,?,?,?,now(),now())",
                UUID.randomUUID().toString(),code,name,active,semesterId,opens,closes);
    }
    @Transactional
    public void updateRound(String id, String name, boolean active, String semesterId, OffsetDateTime opens, OffsetDateTime closes) {
        validateRound("existing",name,semesterId);
        validateDates(opens,closes);
        var existing = jdbc.queryForList("SELECT semester_id FROM thesis_rounds WHERE id=? FOR UPDATE", id);
        if (existing.isEmpty()) throw new IllegalArgumentException("Không tìm thấy đợt ĐATN");
        if (!semesterId.equals(existing.getFirst().get("semester_id"))
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM theses WHERE phase_id=?)", Boolean.class, id)))
            throw new IllegalArgumentException("Đợt đã có sinh viên đăng ký, không được chuyển sang học kỳ khác");
        requireAvailableSemester(semesterId, id);
        validateWithinSemester(semesterId,opens,closes,"Thời gian đăng ký đợt phải nằm trong học kỳ đã chọn");
        for (var slot : jdbc.queryForList("SELECT opens_at,closes_at FROM round_step_windows WHERE round_id=?",id))
            validateWithinSemester(semesterId,RoundReminderService.asOffset(slot.get("opens_at")),RoundReminderService.asOffset(slot.get("closes_at")),
                    "Học kỳ mới phải bao gồm tất cả mốc thời gian đã thiết lập");
        if (jdbc.update("UPDATE thesis_rounds SET name=?,active=?,semester_id=?,registration_opens_at=?,registration_closes_at=?,last_modified_date=now() WHERE id=?",
                name,active,semesterId,opens,closes,id)==0) throw new IllegalArgumentException("Không tìm thấy đợt ĐATN");
    }
    private void requireAvailableSemester(String semesterId, String roundId) {
        if (jdbc.queryForList("SELECT id FROM semesters WHERE id=? FOR UPDATE", semesterId).isEmpty())
            throw new IllegalArgumentException("Không tìm thấy học kỳ");
        Integer count = jdbc.queryForObject("SELECT count(*) FROM thesis_rounds WHERE semester_id=? AND (CAST(? AS varchar) IS NULL OR id<>?)",
                Integer.class, semesterId, roundId, roundId);
        if (count != null && count > 0)
            throw new IllegalArgumentException("Học kỳ này đã có đợt ĐATN. Mỗi học kỳ chỉ được có một đợt, kể cả đợt đã đóng.");
    }
    private void validateDates(OffsetDateTime opens, OffsetDateTime closes) {
        if (opens == null || closes == null || !opens.isBefore(closes))
            throw new IllegalArgumentException("Mốc mở/đóng đăng ký không hợp lệ");
    }
    private void validateWithinSemester(String semesterId,OffsetDateTime opens,OffsetDateTime closes,String message) {
        var semesters=jdbc.queryForList("SELECT starts_on,ends_on FROM semesters WHERE id=? FOR UPDATE",semesterId);
        if (semesters.isEmpty()) throw new IllegalArgumentException("Không tìm thấy học kỳ");
        var semester=semesters.getFirst();
        CalendarDateRange.validate(opens,closes,asDate(semester.get("starts_on")),asDate(semester.get("ends_on")),message);
    }
    private void validateRound(String code,String name,String semesterId) {
        if (code==null || code.isBlank() || name==null || name.isBlank() || semesterId==null || semesterId.isBlank())
            throw new IllegalArgumentException("Cần mã đợt, tên đợt và học kỳ");
        if (code.length() > 100 || name.length() > 150) throw new IllegalArgumentException("Mã đợt tối đa 100 ký tự, tên đợt tối đa 150 ký tự");
    }
    public List<Map<String,Object>> lecturers(String roundId) {
        return jdbc.queryForList("SELECT rl.id,rl.round_id,rl.lecturer_id,u.full_name,u.email,u.phone,rl.orientation,rl.active FROM round_lecturers rl JOIN users u ON u.id=rl.lecturer_id WHERE rl.round_id=? ORDER BY u.full_name",roundId);
    }
    public List<Map<String,Object>> lecturerCandidates() {
        return jdbc.queryForList("SELECT id,full_name,email FROM users WHERE user_type='LECTURER' AND status='ACTIVE' ORDER BY full_name");
    }
    public List<Map<String,Object>> studentCandidates(String roundId) {
        String current=currentUserService.getCurrentUser().orElseThrow().getId();
        return jdbc.queryForList("SELECT u.id,u.full_name,u.username FROM users u WHERE u.user_type='STUDENT' AND u.status='ACTIVE' AND u.id<>? AND NOT EXISTS(SELECT 1 FROM members m WHERE m.user_id=u.id AND m.thesis_round_id=?) ORDER BY u.full_name",current,roundId);
    }
    @Transactional
    public void addLecturer(String roundId, String lecturerId, String orientation) {
        Integer valid = jdbc.queryForObject("SELECT count(*) FROM users WHERE id=? AND user_type='LECTURER' AND status='ACTIVE'",Integer.class,lecturerId);
        if (valid == null || valid == 0) throw new IllegalArgumentException("Tài khoản không phải giảng viên đang hoạt động");
        jdbc.update("INSERT INTO round_lecturers(id,round_id,lecturer_id,orientation,active) VALUES(?,?,?,?,true) ON CONFLICT(round_id,lecturer_id) DO UPDATE SET orientation=EXCLUDED.orientation,active=true",
                UUID.randomUUID().toString(),roundId,lecturerId,orientation);
    }
    @Transactional
    public void removeLecturer(String roundId, String lecturerId) {
        Integer used = jdbc.queryForObject("SELECT count(*) FROM theses WHERE phase_id=? AND lecturer_id=?",Integer.class,roundId,lecturerId);
        if (used != null && used > 0) throw new IllegalArgumentException("Giảng viên đã có hồ sơ trong đợt này");
        jdbc.update("DELETE FROM round_lecturers WHERE round_id=? AND lecturer_id=?",roundId,lecturerId);
    }
    public List<Map<String,Object>> templates() {
        return jdbc.queryForList("SELECT * FROM workflow_templates ORDER BY created_at DESC");
    }
    public List<Map<String,Object>> steps(String templateId) {
        return jdbc.queryForList("SELECT * FROM workflow_steps WHERE template_id=? ORDER BY sort_order",templateId);
    }
    public List<Map<String,Object>> roundSteps(String roundId) {
        return jdbc.queryForList("SELECT ws.step_key,ws.label,ws.sort_order,ws.kind,ws.assignee_role,ws.due_days,ws.next_key,ws.reject_key,ws.email_enabled " +
                "FROM thesis_rounds r JOIN workflow_templates wt ON wt.process_definition_id=r.workflow_definition_id " +
                "JOIN workflow_steps ws ON ws.template_id=wt.id WHERE r.id=? ORDER BY ws.sort_order",roundId);
    }
    @Transactional
    public String createDraft(String name, String sourceId) {
        if (name==null || name.isBlank()) throw new IllegalArgumentException("Cần tên phiên bản quy trình");
        String id=UUID.randomUUID().toString();
        if (sourceId!=null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM workflow_templates WHERE id=?)",Boolean.class,sourceId)))
            throw new IllegalArgumentException("Không tìm thấy quy trình để sao chép");
        int version=jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM workflow_templates",Integer.class);
        jdbc.update("INSERT INTO workflow_templates(id,name,version,status) VALUES(?,?,?,'DRAFT')",id,name,version);
        if (sourceId!=null) jdbc.update("INSERT INTO workflow_steps(id,template_id,step_key,label,sort_order,kind,assignee_role,due_days,next_key,reject_key,email_enabled) SELECT gen_random_uuid()::text,?,step_key,label,sort_order,kind,assignee_role,due_days,next_key,reject_key,email_enabled FROM workflow_steps WHERE template_id=?",id,sourceId);
        return id;
    }
    @Transactional
    public void replaceSteps(String templateId, List<StepInput> steps) {
        String status=jdbc.queryForObject("SELECT status FROM workflow_templates WHERE id=? FOR UPDATE",String.class,templateId);
        if (!"DRAFT".equals(status)) throw new IllegalArgumentException("Chỉ được sửa bản nháp; hãy sao chép phiên bản đã công bố");
        if (steps == null || steps.stream().anyMatch(java.util.Objects::isNull) || steps.size()<2 || steps.size()>30 || !"submitProposal".equals(steps.getFirst().key())
                || !"SUBMIT".equals(steps.getFirst().kind()) || !"STUDENT".equals(steps.getFirst().role()))
            throw new IllegalArgumentException("Quy trình phải bắt đầu bằng bước sinh viên nộp đề cương");
        if (steps.stream().anyMatch(s -> s.key()==null || s.label()==null || s.kind()==null || s.role()==null))
            throw new IllegalArgumentException("Bước quy trình thiếu thông tin bắt buộc");
        var keys = steps.stream().map(StepInput::key).toList();
        if (keys.stream().distinct().count() != keys.size()) throw new IllegalArgumentException("Mã bước bị trùng");
        if (steps.stream().anyMatch(s -> "start".equals(s.key()) || "end".equals(s.key()) || s.key().startsWith("gate_") || s.key().startsWith("f_")))
            throw new IllegalArgumentException("Mã bước trùng mã hệ thống Flowable");
        if (steps.stream().anyMatch(s -> "scheduleDefense".equals(s.key()) && (!"FACULTY_STAFF".equals(s.role()) || !"TASK".equals(s.kind()))))
            throw new IllegalArgumentException("Bước lập lịch bảo vệ phải do Khoa xử lý");
        if (steps.stream().anyMatch(s -> List.of("confirmGuidance", "advisorSignature").contains(s.key()) && (!"LECTURER".equals(s.role()) || !"REVIEW".equals(s.kind()))))
            throw new IllegalArgumentException("Bước xác nhận hướng dẫn phải do giảng viên xử lý");
        if (steps.stream().anyMatch(s -> List.of("reviseProposal", "submitSignedProposal").contains(s.key())
                && (!"SUBMIT".equals(s.kind()) || !"STUDENT".equals(s.role()))
                || "registerThesis".equals(s.key()) && (!"TASK".equals(s.kind()) || !"STUDENT".equals(s.role()))))
            throw new IllegalArgumentException("Bước nghiệp vụ sinh viên phải giữ đúng loại và vai trò xử lý");
        jdbc.update("DELETE FROM workflow_steps WHERE template_id=?",templateId);
        for (int i=0;i<steps.size();i++) {
            StepInput s=steps.get(i);
            if (!s.key().matches("[A-Za-z][A-Za-z0-9_]{1,59}") || s.label().isBlank()
                    || !List.of("SUBMIT","REVIEW","TASK").contains(s.kind())
                    || !List.of("STUDENT","LECTURER","FACULTY_STAFF","COMMITTEE").contains(s.role())
                    || s.dueDays()!=null && s.dueDays()<0) throw new IllegalArgumentException("Bước quy trình không hợp lệ");
            if (s.nextKey()!=null && !s.nextKey().isBlank() && !"end".equals(s.nextKey()) && !keys.contains(s.nextKey()))
                throw new IllegalArgumentException("Bước tiếp theo không tồn tại: "+s.nextKey());
            if ("REVIEW".equals(s.kind()) && (s.rejectKey()==null || !keys.contains(s.rejectKey())))
                throw new IllegalArgumentException("Bước duyệt cần chọn bước quay lại khi yêu cầu sửa");
            if (!"REVIEW".equals(s.kind()) && s.rejectKey()!=null && !s.rejectKey().isBlank())
                throw new IllegalArgumentException("Chỉ bước duyệt mới có nhánh yêu cầu sửa");
            jdbc.update("INSERT INTO workflow_steps(id,template_id,step_key,label,sort_order,kind,assignee_role,due_days,next_key,reject_key,email_enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),templateId,s.key(),s.label(),i+1,s.kind(),s.role(),s.dueDays(),s.nextKey(),s.rejectKey(),!Boolean.FALSE.equals(s.emailEnabled()));
        }
    }
    @Transactional
    public String publish(String templateId) {
        String status=jdbc.queryForObject("SELECT status FROM workflow_templates WHERE id=? FOR UPDATE",String.class,templateId);
        if (!"DRAFT".equals(status)) throw new IllegalArgumentException("Phiên bản đã công bố");
        List<Map<String,Object>> steps=steps(templateId);
        if (steps.size()<2) throw new IllegalArgumentException("Quy trình chưa có đủ bước");
        validateGraph(steps);
        String key="graduation_"+templateId.replace("-","");
        String xml=buildBpmn(key,steps);
        var deployment=repositoryService.createDeployment().name("DATN "+templateId).addString(key+".bpmn20.xml",xml).deploy();
        ProcessDefinition definition=repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        jdbc.update("UPDATE workflow_templates SET status='PUBLISHED',process_definition_id=?,published_at=now() WHERE id=?",definition.getId(),templateId);
        return definition.getId();
    }
    @Transactional
    public void assignTemplate(String roundId,String templateId) {
        if (jdbc.queryForList("SELECT id FROM thesis_rounds WHERE id=? FOR UPDATE",roundId).isEmpty())
            throw new IllegalArgumentException("Không tìm thấy đợt ĐATN");
        String definition=jdbc.queryForObject("SELECT process_definition_id FROM workflow_templates WHERE id=? AND status='PUBLISHED'",String.class,templateId);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM theses WHERE phase_id=?)",Boolean.class,roundId)))
            throw new IllegalArgumentException("Đợt đã có hồ sơ; không thể đổi quy trình đang áp dụng");
        if (jdbc.update("UPDATE thesis_rounds SET workflow_definition_id=? WHERE id=?",definition,roundId)==0)
            throw new IllegalArgumentException("Không tìm thấy đợt ĐATN");
    }
    private String buildBpmn(String key,List<Map<String,Object>> steps) {
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"http://eduportal.vn/datn\"><process id=\"").append(key).append("\" name=\"DATN\" isExecutable=\"true\"><startEvent id=\"start\"/><endEvent id=\"end\"/>");
        for (Map<String,Object> step:steps) {
            String id=(String)step.get("step_key"), role=(String)step.get("assignee_role");
            xml.append("<userTask id=\"").append(id).append("\" name=\"").append(escape((String)step.get("label"))).append("\"");
            if (role.equals("STUDENT")) xml.append(" flowable:candidateUsers=\"${studentId}\"");
            else if (role.equals("LECTURER")) xml.append(" flowable:assignee=\"${lecturerId}\"");
            else xml.append(" flowable:candidateGroups=\"").append(role).append("\"");
            xml.append("><extensionElements><flowable:taskListener event=\"create\" delegateExpression=\"${coreTaskDeadlineListener}\"/></extensionElements></userTask>");
            if (step.get("kind").equals("REVIEW")) xml.append("<exclusiveGateway id=\"gate_").append(id).append("\"/>");
        }
        String first=(String)steps.getFirst().get("step_key");
        xml.append(flow("start",first,null));
        for (int i=0;i<steps.size();i++) {
            Map<String,Object> step=steps.get(i);
            String id=(String)step.get("step_key");
            String next=step.get("next_key") instanceof String configured && !configured.isBlank()
                    ? configured : i+1<steps.size()?(String)steps.get(i+1).get("step_key"):"end";
            if (step.get("kind").equals("REVIEW")) {
                xml.append(flow(id,"gate_"+id,null));
                xml.append(flow("gate_"+id,next,"${approved == true}","approved"));
                String back=step.get("reject_key") instanceof String rejected && !rejected.isBlank()
                        ? rejected : first;
                xml.append(flow("gate_"+id,back,"${approved == false}","rejected"));
            } else xml.append(flow(id,next,null));
        }
        return xml.append("</process></definitions>").toString();
    }
    private void validateGraph(List<Map<String,Object>> steps) {
        if (!"submitProposal".equals(steps.getFirst().get("step_key"))
                || !"SUBMIT".equals(steps.getFirst().get("kind"))
                || !"STUDENT".equals(steps.getFirst().get("assignee_role")))
            throw new IllegalArgumentException("Quy trình phải bắt đầu bằng bước sinh viên nộp đề cương");
        var byKey = new java.util.HashMap<String,Map<String,Object>>();
        for (var step : steps) byKey.put((String)step.get("step_key"),step);
        if (!byKey.containsKey("submitProposal")) throw new IllegalArgumentException("Thiếu bước nộp đề cương");
        var visited = new java.util.HashSet<String>();
        var queue = new java.util.ArrayDeque<String>();
        queue.add("submitProposal");
        boolean reachesEnd = false;
        while (!queue.isEmpty()) {
            String key = queue.removeFirst();
            if ("end".equals(key)) { reachesEnd = true; continue; }
            if (!visited.add(key)) continue;
            var step = byKey.get(key);
            if (step == null) throw new IllegalArgumentException("Nhánh trỏ tới bước không tồn tại: "+key);
            int index = steps.indexOf(step);
            String next = step.get("next_key") instanceof String explicit && !explicit.isBlank()
                    ? explicit : index+1<steps.size()?(String)steps.get(index+1).get("step_key"):"end";
            queue.add(next);
            if ("REVIEW".equals(step.get("kind"))) {
                String rejected = (String)step.get("reject_key");
                if (rejected==null || rejected.isBlank()) throw new IllegalArgumentException("Bước duyệt thiếu nhánh yêu cầu sửa: "+key);
                queue.add(rejected);
            }
        }
        if (!reachesEnd || visited.size()!=steps.size()) throw new IllegalArgumentException("Quy trình có bước không thể tới hoặc không có đường kết thúc");
        var canFinish = new java.util.HashSet<String>();
        canFinish.add("end");
        boolean changed;
        do {
            changed = false;
            for (int i=0;i<steps.size();i++) {
                var step=steps.get(i);
                String next=step.get("next_key") instanceof String explicit && !explicit.isBlank()
                        ? explicit : i+1<steps.size()?(String)steps.get(i+1).get("step_key"):"end";
                if (canFinish.contains(next) || "REVIEW".equals(step.get("kind")) && canFinish.contains(step.get("reject_key")))
                    changed |= canFinish.add((String)step.get("step_key"));
            }
        } while (changed);
        if (!canFinish.containsAll(byKey.keySet()))
            throw new IllegalArgumentException("Quy trình có nhánh mắc kẹt, không có đường tới kết thúc");
    }
    private String flow(String from,String to,String condition) { return flow(from,to,condition,null); }
    private String flow(String from,String to,String condition,String suffix) {
        // suffix keeps IDs unique when approve and reject branches target the same step
        String id="f_"+from+"_"+to+(suffix==null?"":"_"+suffix);
        return "<sequenceFlow id=\""+id+"\" sourceRef=\""+from+"\" targetRef=\""+to+"\">"+
                (condition==null?"":"<conditionExpression xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"tFormalExpression\"><![CDATA["+condition+"]]></conditionExpression>")+"</sequenceFlow>";
    }
    private String escape(String value) { return value.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;"); }
    public record StepInput(String key,String label,String kind,String role,Integer dueDays,String nextKey,String rejectKey,Boolean emailEnabled) {}
}

package com.example.graduationprocessbe.service.impl;

import com.example.graduationprocessbe.dto.request.CreateThesisRequest;
import com.example.graduationprocessbe.dto.response.TaskResponse;
import com.example.graduationprocessbe.dto.response.ThesisResponse;
import com.example.graduationprocessbe.entity.Thesis;
import com.example.graduationprocessbe.entity.User;
import com.example.graduationprocessbe.exception.ResourceNotFoundException;
import com.example.graduationprocessbe.mapper.ThesisMapper;
import com.example.graduationprocessbe.repository.ThesisRepository;
import com.example.graduationprocessbe.repository.UserRepository;
import com.example.graduationprocessbe.service.AuditLogService;
import com.example.graduationprocessbe.service.CurrentUserService;
import com.example.graduationprocessbe.service.ThesisProcessService;
import com.example.graduationprocessbe.service.WorkflowPresentationService;
import com.example.graduationprocessbe.service.WorkflowMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThesisProcessServiceImpl implements ThesisProcessService {

    private static final String STATUS_COMPLETED = "COMPLETED";

    private final ThesisRepository thesisRepository;
    private final UserRepository userRepository;
    private final ThesisMapper thesisMapper;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final AuditLogService auditLogService;
    private final CurrentUserService currentUserService;
    private final JdbcTemplate jdbc;
    private final com.example.graduationprocessbe.service.PermissionGuard permissionGuard;
    private final WorkflowPresentationService presentation;
    private final WorkflowMailService workflowMail;

    @Override
    @Transactional
    public ThesisResponse createThesis(CreateThesisRequest request) {
        permissionGuard.require("THESIS_CREATE",request.getPhaseId());
        User actor=currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        if (!"STUDENT".equals(actor.getUserType()) || !actor.getId().equals(request.getStudentId()))
            throw new AccessDeniedException("Chỉ sinh viên được đăng ký đề tài của mình");
        if (request.getProposalContent()==null || request.getProposalContent().isBlank())
            throw new IllegalArgumentException("Cần nội dung hoặc liên kết đề cương");
        User student = findUser(request.getStudentId());
        User lecturer = findUser(request.getLecturerId());
        if (!"LECTURER".equals(lecturer.getUserType())) throw new IllegalArgumentException("GVHD phải là giảng viên");
        List<Map<String,Object>> rounds=jdbc.queryForList("SELECT registration_opens_at,registration_closes_at FROM thesis_rounds WHERE id=? AND active=true FOR UPDATE",request.getPhaseId());
        if (rounds.isEmpty()) throw new IllegalArgumentException("Đợt ĐATN không hoạt động");
        Map<String,Object> round=rounds.getFirst();
        OffsetDateTime now=OffsetDateTime.now();
        if (round.get("registration_opens_at")==null || round.get("registration_closes_at")==null)
            throw new IllegalArgumentException("Đợt chưa có đủ mốc đăng ký");
        OffsetDateTime opens=asOffset(round.get("registration_opens_at"));
        OffsetDateTime closes=asOffset(round.get("registration_closes_at"));
        if (now.isBefore(opens) || now.isAfter(closes))
            throw new IllegalArgumentException("Ngoài thời gian đăng ký đề tài");
        Integer registered=jdbc.queryForObject("SELECT count(*) FROM round_lecturers WHERE round_id=? AND lecturer_id=? AND active=true",Integer.class,request.getPhaseId(),lecturer.getId());
        if (registered==null || registered==0) throw new IllegalArgumentException("Giảng viên chưa tham gia đợt này");
        Integer existing=jdbc.queryForObject("SELECT count(*) FROM theses WHERE phase_id=? AND student_id=?",Integer.class,request.getPhaseId(),student.getId());
        if (existing!=null && existing>0) throw new IllegalArgumentException("Sinh viên đã đăng ký trong đợt này");
        Integer memberExisting=jdbc.queryForObject("SELECT count(*) FROM members WHERE thesis_round_id=? AND user_id=?",Integer.class,request.getPhaseId(),student.getId());
        if (memberExisting!=null && memberExisting>0) throw new IllegalArgumentException("Sinh viên đã tham gia nhóm khác trong đợt này");
        User partner=null;
        if (request.getPartnerStudentId()!=null && !request.getPartnerStudentId().isBlank()) {
            partner=findUser(request.getPartnerStudentId());
            if (partner.getId().equals(student.getId()) || !"STUDENT".equals(partner.getUserType()) || !"ACTIVE".equals(partner.getStatus()))
                throw new IllegalArgumentException("Sinh viên cùng nhóm không hợp lệ");
            Integer partnerExisting=jdbc.queryForObject("SELECT count(*) FROM members WHERE thesis_round_id=? AND user_id=?",Integer.class,request.getPhaseId(),partner.getId());
            if (partnerExisting!=null && partnerExisting>0) throw new IllegalArgumentException("Sinh viên cùng nhóm đã có đề tài trong đợt");
        }

        Thesis thesis = new Thesis();
        thesis.setTitle(request.getTitle());
        thesis.setTitleEn(request.getTitleEn().trim());
        thesis.setStudent(student);
        thesis.setLecturer(lecturer);
        thesis.setPhaseId(request.getPhaseId());
        thesis.setDescription(request.getProposalContent().trim());
        thesis = thesisRepository.saveAndFlush(thesis);
        jdbc.update("INSERT INTO members(thesis_id,user_id,thesis_round_id) VALUES(?,?,?)",thesis.getId(),student.getId(),request.getPhaseId());
        if (partner!=null) jdbc.update("INSERT INTO members(thesis_id,user_id,thesis_round_id) VALUES(?,?,?)",thesis.getId(),partner.getId(),request.getPhaseId());
        var definitions = jdbc.queryForList("SELECT workflow_definition_id FROM thesis_rounds WHERE id=?",request.getPhaseId());
        if (definitions.isEmpty() || definitions.getFirst().get("workflow_definition_id")==null)
            throw new IllegalArgumentException("Đợt chưa được gán quy trình đã công bố");
        String definition=(String)definitions.getFirst().get("workflow_definition_id");
        List<String> students=jdbc.queryForList("SELECT user_id FROM members WHERE thesis_id=? ORDER BY user_id",String.class,thesis.getId());
        Map<String,Object> initial=new HashMap<>();
        initial.put("thesisId",thesis.getId()); initial.put("studentId",student.getId());
        initial.put("studentIds",String.join(",",students)); initial.put("studentEmail",student.getEmail());
        initial.put("lecturerId",lecturer.getId());
        ProcessInstance instance=runtimeService.startProcessInstanceById(definition,thesis.getId(),initial);
        thesis.setProcessInstanceId(instance.getId());
        Task first=taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        if (first==null || !"submitProposal".equals(first.getTaskDefinitionKey()))
            throw new IllegalArgumentException("Quy trình phải bắt đầu bằng bước nộp đề cương");
        jdbc.update("INSERT INTO thesis_submissions(id,thesis_id,step_key,submitted_by,content) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(),thesis.getId(),"submitProposal",student.getId(),request.getProposalContent().trim());
        taskService.complete(first.getId(),Map.of("content",request.getProposalContent().trim()));
        refreshStatus(thesis);
        thesis=thesisRepository.saveAndFlush(thesis);
        enqueueNextTask(thesis);
        auditLogService.record(instance.getId(),actor,"SUBMIT_PROPOSAL","submitProposal",Map.of("thesisId",thesis.getId()));
        return response(thesis);
    }

    @Override
    @Transactional
    public ThesisResponse submitProposal(String thesisId, String content) {
        Thesis thesis=findThesis(thesisId);
        authorizeThesis(thesis);
        if (thesis.getProcessInstanceId()==null) throw new IllegalArgumentException("Hồ sơ chưa có quy trình");
        Task task=taskService.createTaskQuery().processInstanceId(thesis.getProcessInstanceId()).taskDefinitionKey("submitProposal").singleResult();
        if (task==null) throw new IllegalArgumentException("Hồ sơ hiện không chờ nộp đề cương");
        return completeTask(task.getId(),Map.of("content",content));
    }

    @Override
    @Transactional
    public ThesisResponse confirmGuidance(String thesisId, boolean approved, String comment) {
        jdbc.queryForObject("SELECT id FROM theses WHERE id=? FOR UPDATE", String.class, thesisId);
        Thesis thesis = findThesis(thesisId);
        User actor = currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        if (!"LECTURER".equals(actor.getUserType()) || !thesis.getLecturer().getId().equals(actor.getId()))
            throw new AccessDeniedException("Chỉ giảng viên được chọn mới được xác nhận hướng dẫn");
        Task task=thesis.getProcessInstanceId()==null ? null : taskService.createTaskQuery()
                .processInstanceId(thesis.getProcessInstanceId()).taskDefinitionKey("confirmGuidance").singleResult();
        if (task==null) throw new IllegalArgumentException("Hồ sơ hiện chưa đến bước xác nhận hướng dẫn");
        permissionGuard.require("TASKS_APPROVE",thesis.getPhaseId());
        if (task.getAssignee()==null) taskService.claim(task.getId(),actor.getId());
        String note = comment == null ? "" : comment.trim();
        if (note.length() > 2000) throw new IllegalArgumentException("Nhận xét tối đa 2000 ký tự");
        if (!approved && note.isBlank()) throw new IllegalArgumentException("Cần ghi lý do từ chối hướng dẫn");
        thesis.setGuidanceApproved(approved);
        thesis.setGuidanceRespondedAt(java.time.LocalDateTime.now());
        thesis.setGuidanceComment(note);
        thesisRepository.saveAndFlush(thesis);
        return completeTask(task.getId(),Map.of("approved",approved,"comment",note));
    }

    @Override
    @Transactional
    public List<Map<String,Object>> signProposal(String thesisId, String signature) {
        Thesis thesis=findThesis(thesisId);
        permissionGuard.require("TASKS_UPDATE",thesis.getPhaseId());
        User actor=currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        if (!"submitSignedProposal".equals(thesis.getCurrentStatus())) throw new IllegalArgumentException("Đề cương chưa ở bước ký xác nhận");
        if (!signerOrder(thesis).contains(actor.getId())) throw new AccessDeniedException("Bạn không thuộc những sinh viên cần ký đề cương này");
        if (!(signature!=null && signature.startsWith("data:image/png;base64,") && signature.length()<=400_000)) throw new IllegalArgumentException("Chữ ký không hợp lệ");
        storeProposalSignature(thesisId,actor.getId(),signature);
        return proposalSignatures(thesisId);
    }

    @Override
    public List<Map<String,Object>> proposalSignatures(String thesisId) {
        Thesis thesis=findThesis(thesisId);
        User actor=currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        List<Map<String,Object>> result=new java.util.ArrayList<>();
        for (String userId:signerOrder(thesis)) {
            String name=jdbc.queryForObject("SELECT full_name FROM users WHERE id=?",String.class,userId);
            String stored=proposalSignatureOf(thesisId,userId);
            Map<String,Object> row=new HashMap<>();
            row.put("userId",userId); row.put("fullName",name);
            row.put("signed",stored!=null);
            row.put("signature",userId.equals(actor.getId()) ? stored : null);
            result.add(row);
        }
        return result;
    }

    /**
     * Who has to sign the approved proposal: the student who registered the thesis, and also the other student of the group
     * when there is an explanation of changes (that sheet carries both signatures; the proposal itself only the first).
     */
    private List<String> signerOrder(Thesis thesis) {
        List<String> ids=new java.util.ArrayList<>(jdbc.queryForList("SELECT user_id FROM members WHERE thesis_id=? ORDER BY user_id",String.class,thesis.getId()));
        ids.remove(thesis.getStudent().getId());
        boolean explained=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM thesis_submissions WHERE thesis_id=? AND explanation IS NOT NULL AND explanation<>'')",Boolean.class,thesis.getId()));
        List<String> result=new java.util.ArrayList<>();
        result.add(thesis.getStudent().getId());
        if (explained && !ids.isEmpty()) result.add(ids.getFirst());
        return result;
    }
    private void storeProposalSignature(String thesisId,String userId,String signature) {
        jdbc.update("INSERT INTO thesis_proposal_signatures(thesis_id,user_id,signature) VALUES(?,?,?) ON CONFLICT(thesis_id,user_id) DO UPDATE SET signature=EXCLUDED.signature,signed_at=now()",thesisId,userId,signature);
    }
    private String proposalSignatureOf(String thesisId,String userId) {
        List<String> rows=jdbc.queryForList("SELECT signature FROM thesis_proposal_signatures WHERE thesis_id=? AND user_id=?",String.class,thesisId,userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    @Transactional
    public ThesisResponse resubmitRegistration(String thesisId) {
        permissionGuard.require("TASKS_UPDATE",findThesis(thesisId).getPhaseId());
        jdbc.queryForObject("SELECT id FROM theses WHERE id=? FOR UPDATE", String.class, thesisId);
        Thesis thesis = findThesis(thesisId);
        User actor = currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        if (!"STUDENT".equals(actor.getUserType()) || !actor.getId().equals(thesis.getStudent().getId()))
            throw new AccessDeniedException("Chỉ sinh viên đăng ký được gửi lại đăng ký của nhóm");
        Task task=thesis.getProcessInstanceId()==null ? null : taskService.createTaskQuery()
                .processInstanceId(thesis.getProcessInstanceId()).taskDefinitionKey("registerThesis").singleResult();
        if (task==null) throw new IllegalArgumentException("Hồ sơ hiện không chờ gửi lại đăng ký");
        if (task.getAssignee()==null) taskService.claim(task.getId(),actor.getId());
        checkStepWindow(thesis.getPhaseId(),"registerThesis");
        // The student only confirms: title and supervisor stay as already approved.
        User lecturer = thesis.getLecturer();
        String lecturerId = lecturer.getId();
        Integer available = jdbc.queryForObject("SELECT count(*) FROM round_lecturers WHERE round_id=? AND lecturer_id=? AND active=true", Integer.class, thesis.getPhaseId(), lecturerId);
        if (!"LECTURER".equals(lecturer.getUserType()) || !"ACTIVE".equals(lecturer.getStatus()) || available == null || available == 0)
            throw new IllegalArgumentException("Giảng viên chưa tham gia đợt này hoặc đã ngừng hoạt động");
        thesis.setGuidanceApproved(null);
        thesis.setGuidanceRespondedAt(null);
        thesis.setGuidanceComment(null);
        runtimeService.setVariable(thesis.getProcessInstanceId(),"lecturerId",lecturer.getId());
        taskService.complete(task.getId());
        refreshStatus(thesis);
        enqueueNextTask(thesis);
        return response(thesisRepository.saveAndFlush(thesis));
    }

    @Override
    @Transactional(readOnly = true)
    public ThesisResponse getThesis(String thesisId) {
        Thesis thesis=findThesis(thesisId);
        authorizeThesis(thesis);
        return response(thesis);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> getThesisTasks(String thesisId) {
        Thesis thesis = findThesis(thesisId);
        authorizeThesis(thesis);
        if (thesis.getProcessInstanceId() == null) {
            return List.of();
        }
        return taskService.createTaskQuery()
                .processInstanceId(thesis.getProcessInstanceId())
                .active()
                .orderByTaskCreateTime().asc()
                .list()
                .stream().map(this::toTaskResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> findTasks(String assignee, String candidateGroup) {
        User actor=currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        if (!"ADMIN".equals(actor.getUserType())) {
            if (assignee!=null && !assignee.equals(actor.getId())) throw new AccessDeniedException("Không được xem tác vụ của người khác");
            if (candidateGroup!=null && !roles(actor.getId()).contains(candidateGroup)) throw new AccessDeniedException("Không thuộc nhóm xử lý");
            if (assignee==null && candidateGroup==null) return java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(
                    taskService.createTaskQuery().taskAssignee(actor.getId()).active().list().stream(),
                    groupTasks(actor)),
                java.util.stream.Stream.concat(
                    taskService.createTaskQuery().taskCandidateGroupIn(roles(actor.getId())).active().list().stream(),
                    taskService.createTaskQuery().taskCandidateUser(actor.getId()).active().list().stream()))
                .filter(distinctById()).filter(task -> canAccessTask(task,actor)).map(this::toTaskResponse).toList();
        }
        if (candidateGroup != null && assignee == null && !"ADMIN".equals(actor.getUserType())) return java.util.stream.Stream.concat(
                taskService.createTaskQuery().taskCandidateGroup(candidateGroup).active().list().stream(),
                taskService.createTaskQuery().taskAssignee(actor.getId()).active().list().stream())
                .filter(distinctById()).filter(task -> task.getAssignee() == null || taskService.getIdentityLinksForTask(task.getId()).stream()
                        .anyMatch(link -> candidateGroup.equals(link.getGroupId())))
                .filter(task -> canAccessTask(task, actor)).map(this::toTaskResponse).toList();
        TaskQuery query = taskService.createTaskQuery().active();
        if (assignee != null) {
            query.taskAssignee(assignee);
        }
        if (candidateGroup != null) {
            query.taskCandidateGroup(candidateGroup);
        }
        return query.orderByTaskCreateTime().asc().list()
                .stream().filter(task -> canAccessTask(task,actor)).map(this::toTaskResponse).toList();
    }

    /** Flowable tasks from different queries are different objects even for the same row, so de-duplicate by id. */
    private static java.util.function.Predicate<Task> distinctById() {
        java.util.Set<String> seen=new java.util.HashSet<>();
        return task -> seen.add(task.getId());
    }

    /** Tasks a groupmate holds are still the whole group's: every unlocked member sees them. */
    private java.util.stream.Stream<Task> groupTasks(User actor) {
        if (!"STUDENT".equals(actor.getUserType())) return java.util.stream.Stream.empty();
        List<String> processIds=groupProcessIds(actor.getId());
        return processIds.isEmpty() ? java.util.stream.Stream.empty() : taskService.createTaskQuery().processInstanceIdIn(processIds).active().list().stream();
    }

    @Override
    @Transactional(readOnly = true)
    public TaskResponse getTask(String taskId) {
        Task task=findTask(taskId);
        authorizeTask(task,currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập")));
        return toTaskResponse(task);
    }

    @Override
    @Transactional
    public TaskResponse claimTask(String taskId, String userId) {
        Task task=findTask(taskId);
        User actor=currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        if (!actor.getId().equals(userId)) throw new AccessDeniedException("Chỉ được nhận việc cho bản thân");
        authorizeTask(task,actor);
        if (isLockedMember(task,actor.getId())) throw new AccessDeniedException("Trưởng nhóm đã khóa quyền nộp của bạn. Bạn vẫn xem được hồ sơ.");
        permissionGuard.require("TASKS_UPDATE",taskRound(task));
        findUser(userId);
        // Students of one group share their tasks: a member can take over a task another member claimed.
        if (task.getAssignee()!=null && !task.getAssignee().equals(userId) && isStudentGroupTask(task) && isGroupMember(task,actor)) taskService.setAssignee(taskId, userId);
        else taskService.claim(taskId, userId);
        return toTaskResponse(findTask(taskId));
    }

    @Override
    @Transactional
    public ThesisResponse completeTask(String taskId, Map<String, Object> variables) {
        Task task = findTask(taskId);
        User actor=currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        authorizeTask(task,actor);
        if (isLockedMember(task,actor.getId())) throw new AccessDeniedException("Trưởng nhóm đã khóa quyền nộp của bạn. Bạn vẫn xem được hồ sơ.");
        if (!actor.getId().equals(task.getAssignee())) {
            boolean sharedGroupTask = task.getAssignee()!=null && isStudentGroupTask(task) && isGroupMember(task,actor);
            if (!sharedGroupTask) throw new AccessDeniedException("Cần nhận tác vụ trước khi hoàn thành");
            taskService.setAssignee(task.getId(), actor.getId());
        }
        Thesis thesis = thesisRepository.findByProcessInstanceId(task.getProcessInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Thesis not found for process instance: " + task.getProcessInstanceId()));
        permissionGuard.require("TASKS_UPDATE",thesis.getPhaseId());
        Map<String,Object> vars=new HashMap<>();
        if (variables!=null) for (String key:List.of("content","attachmentUrl","title","titleEn","explanation","signature","approved","comment","defenseAt","room","councilName","notes")) {
            if (variables.containsKey(key)) vars.put(key,variables.get(key));
        }
        List<Map<String,Object>> step=jdbc.queryForList(    "SELECT ws.kind FROM workflow_steps ws JOIN workflow_templates wt ON wt.id=ws.template_id WHERE wt.process_definition_id=? AND ws.step_key=?",task.getProcessDefinitionId(),task.getTaskDefinitionKey());
        if (!step.isEmpty()) {
            String kind=(String)step.getFirst().get("kind");
            if (kind.equals("SUBMIT")) {
                // submitProposal tasks are revision requests after faculty feedback;
                // the initial proposal window only governs first registration.
                if (!"submitProposal".equals(task.getTaskDefinitionKey())) checkStepWindow(thesis.getPhaseId(),task.getTaskDefinitionKey());
                Object content=vars.get("content");
                if (!(content instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Cần nội dung hoặc liên kết hồ sơ nộp");
                boolean signedStep="submitSignedProposal".equals(task.getTaskDefinitionKey());
                Object studentSignature=vars.get("signature");
                Object secondSignature=null;
                if (signedStep) {
                    // The lecturer already approved this text: it can no longer change, the student only signs and sends it.
                    List<String> approvedText=jdbc.queryForList("SELECT content FROM thesis_submissions WHERE thesis_id=? AND step_key IN ('submitProposal','reviseProposal') ORDER BY submitted_at DESC LIMIT 1",String.class,thesis.getId());
                    if (approvedText.isEmpty() || !approvedText.getFirst().equals(content))
                        throw new IllegalArgumentException("Đề cương đã được giảng viên duyệt, không được thay đổi nội dung");
                    if (vars.get("title") instanceof String requested && !requested.trim().equals(thesis.getTitle()))
                        throw new IllegalArgumentException("Đề cương đã được giảng viên duyệt, không được đổi tên đề tài");
                    if (vars.get("titleEn") instanceof String requestedEn && !requestedEn.trim().equals(java.util.Objects.toString(thesis.getTitleEn(),"")))
                        throw new IllegalArgumentException("Đề cương đã được giảng viên duyệt, không được đổi tên đề tài");
                    // Every required signer (see signerOrder) must have signed from their own account; the sender may sign right here.
                    List<String> required=signerOrder(thesis);
                    if (studentSignature instanceof String sig && sig.startsWith("data:image/png;base64,") && sig.length()<=400_000 && required.contains(actor.getId()))
                        storeProposalSignature(thesis.getId(),actor.getId(),sig);
                    String signedThesisId=thesis.getId();
                    List<String> unsigned=required.stream().filter(id -> proposalSignatureOf(signedThesisId,id)==null)
                            .map(id -> jdbc.queryForObject("SELECT full_name FROM users WHERE id=?",String.class,id)).toList();
                    if (!unsigned.isEmpty()) throw new IllegalArgumentException("Chưa thể gửi: "+String.join(", ",unsigned)+" chưa ký xác nhận");
                    List<String> signers=signerOrder(thesis);
                    studentSignature=proposalSignatureOf(thesis.getId(),signers.get(0));
                    secondSignature=signers.size()>1 ? proposalSignatureOf(thesis.getId(),signers.get(1)) : null;
                }
                boolean proposalStep=List.of("submitProposal","reviseProposal","submitSignedProposal").contains(task.getTaskDefinitionKey());
                if (!proposalStep && !(vars.get("attachmentUrl") instanceof String file && !file.isBlank()))
                    throw new IllegalArgumentException("Cần đính kèm file PDF báo cáo");
                if (vars.get("attachmentUrl") instanceof String url && url.startsWith("/api/theses/")
                        && !url.startsWith("/api/theses/"+thesis.getId()+"/attachments/"))
                    throw new IllegalArgumentException("File đính kèm không thuộc đề tài này");
                Object explanation=vars.get("explanation");
                boolean revision=List.of("submitProposal","reviseProposal").contains(task.getTaskDefinitionKey());
                if (revision && (!(explanation instanceof String why) || why.isBlank())) throw new IllegalArgumentException("Cần giải trình nội dung đã chỉnh sửa");
                jdbc.update("INSERT INTO thesis_submissions(id,thesis_id,step_key,submitted_by,content,attachment_url,explanation,signature,signature2) VALUES(?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),thesis.getId(),task.getTaskDefinitionKey(),actor.getId(),content,vars.get("attachmentUrl"),explanation instanceof String reason && !reason.isBlank() ? reason : null,signedStep ? studentSignature : null,signedStep && groupStudentIds(task).size()>=2 ? secondSignature : null);
                // The title may be narrowed while the proposal is being revised (e.g. when it was too broad).
                if (vars.get("title") instanceof String newTitle && List.of("submitProposal","reviseProposal","submitSignedProposal").contains(task.getTaskDefinitionKey())) {
                    String trimmed=newTitle.trim();
                    if (trimmed.isEmpty() || trimmed.length()>255) throw new IllegalArgumentException("Tên đề tài không hợp lệ");
                    if (!trimmed.equals(thesis.getTitle())) {
                        vars.put("previousTitle",thesis.getTitle());
                        thesis.setTitle(trimmed);
                    }
                }
                // The English title is required: it can be corrected here but never cleared.
                if (vars.get("titleEn") instanceof String newTitleEn && List.of("submitProposal","reviseProposal","submitSignedProposal").contains(task.getTaskDefinitionKey())) {
                    String trimmedEn=newTitleEn.trim();
                    if (trimmedEn.isEmpty()) throw new IllegalArgumentException("Cần nhập tên đề tài tiếng Anh");
                    if (trimmedEn.length()>255) throw new IllegalArgumentException("Tên đề tài tiếng Anh tối đa 255 ký tự");
                    thesis.setTitleEn(trimmedEn);
                }
                // Theses registered before the English title existed must provide it when they send the proposal again.
                if (List.of("submitProposal","reviseProposal").contains(task.getTaskDefinitionKey()) && (thesis.getTitleEn()==null || thesis.getTitleEn().isBlank()))
                    throw new IllegalArgumentException("Cần nhập tên đề tài tiếng Anh");
            } else if (kind.equals("REVIEW")) {
                permissionGuard.require("TASKS_APPROVE",thesis.getPhaseId());
                if (!(vars.get("approved") instanceof Boolean approved)) throw new IllegalArgumentException("Cần chọn duyệt hoặc yêu cầu sửa");
                String comment=String.valueOf(vars.getOrDefault("comment",""));
                if (!approved && comment.isBlank()) throw new IllegalArgumentException("Cần ghi rõ nội dung yêu cầu sửa");
                // The supervising lecturer signs when confirming a proposal: a PNG drawn on the signature pad.
                Object drawn=vars.get("signature");
                boolean needsSignature="advisorSignature".equals(task.getTaskDefinitionKey()) && approved;
                if (needsSignature && !(drawn instanceof String img && img.startsWith("data:image/png;base64,")))
                    throw new IllegalArgumentException("Cần chữ ký xác nhận của giảng viên");
                if (drawn instanceof String img && img.length()>400_000) throw new IllegalArgumentException("Chữ ký quá lớn");
                // A new approval of the text means the students have to sign it again.
                if (needsSignature) jdbc.update("DELETE FROM thesis_proposal_signatures WHERE thesis_id=?",thesis.getId());
                jdbc.update("INSERT INTO thesis_feedback(id,thesis_id,step_key,reviewer_id,approved,comment,signature) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),thesis.getId(),task.getTaskDefinitionKey(),actor.getId(),approved,comment,needsSignature ? drawn : null);
            }
        }

        if ("confirmGuidance".equals(task.getTaskDefinitionKey())) {
            Boolean approved=(Boolean)vars.get("approved");
            thesis.setGuidanceApproved(approved);
            thesis.setGuidanceRespondedAt(java.time.LocalDateTime.now());
            thesis.setGuidanceComment(String.valueOf(vars.getOrDefault("comment","")));
        }

        if ("scheduleDefense".equals(task.getTaskDefinitionKey())) {
            Object when=vars.get("defenseAt"), room=vars.get("room"), council=vars.get("councilName");
            if (!(when instanceof String date) || !(room instanceof String place) || place.isBlank()
                    || !(council instanceof String name) || name.isBlank())
                throw new IllegalArgumentException("Cần ngày giờ, phòng và tên Hội đồng bảo vệ");
            OffsetDateTime scheduled;
            try { scheduled=OffsetDateTime.parse(date); }
            catch (java.time.format.DateTimeParseException ex) { throw new IllegalArgumentException("Ngày giờ bảo vệ không hợp lệ"); }
            if (!scheduled.isAfter(OffsetDateTime.now())) throw new IllegalArgumentException("Lịch bảo vệ phải ở tương lai");
            jdbc.update("INSERT INTO defense_schedules(id,thesis_id,defense_at,room,council_name,notes,published_by) VALUES(?,?,?,?,?,?,?) ON CONFLICT(thesis_id) DO UPDATE SET defense_at=EXCLUDED.defense_at,room=EXCLUDED.room,council_name=EXCLUDED.council_name,notes=EXCLUDED.notes,published_by=EXCLUDED.published_by,published_at=now()",
                    UUID.randomUUID().toString(),thesis.getId(),scheduled,place.trim(),name.trim(),vars.get("notes"),actor.getId());
        }
        if ("registerThesis".equals(task.getTaskDefinitionKey())) {
            if (!"STUDENT".equals(actor.getUserType()))
                throw new AccessDeniedException("Chỉ sinh viên được xác nhận đăng ký ĐATN");
            checkStepWindow(thesis.getPhaseId(),"registerThesis");
        }
        Map<String,Object> auditVars=new HashMap<>(vars);
        auditVars.computeIfPresent("signature", (ignoredKey, ignoredValue) -> "[chữ ký]");
        Map<String,Object> processVars=new HashMap<>(vars);
        processVars.remove("title"); processVars.remove("titleEn"); processVars.remove("previousTitle"); processVars.remove("explanation"); processVars.remove("signature");
        taskService.complete(taskId, processVars);
        // Routing belongs to the published definition selected for this round.

        refreshStatus(thesis);
        thesis = thesisRepository.save(thesis);
        if (!step.isEmpty() && "REVIEW".equals(step.getFirst().get("kind")))
            enqueueCaseFeedback(thesis, task, Boolean.TRUE.equals(vars.get("approved")), String.valueOf(vars.getOrDefault("comment", "")));
        if ("scheduleDefense".equals(task.getTaskDefinitionKey())) enqueueDefenseSchedule(thesis, task, vars);
        enqueueNextTask(thesis);

        auditLogService.record(task.getProcessInstanceId(), currentUserService.getCurrentUser().orElse(null),
                "COMPLETE_TASK", task.getTaskDefinitionKey(), auditVars);
        return response(thesis);
    }

    private void enqueueNextTask(Thesis thesis) {
        List<Task> active=taskService.createTaskQuery().processInstanceId(thesis.getProcessInstanceId()).active().list();
        if (active.isEmpty()) return;
        Task next=active.getFirst();
        var round=jdbc.queryForMap("SELECT name FROM thesis_rounds WHERE id=?",thesis.getPhaseId());
        String roundName=(String)round.get("name");
        String deadline=next.getDueDate()==null ? "Theo lịch của đợt" : next.getDueDate().toInstant()
                .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        List<String> recipients;
        if (next.getAssignee()!=null) {
            recipients=jdbc.queryForList("SELECT email FROM users WHERE id=? AND status='ACTIVE'",String.class,next.getAssignee());
        } else {
            var roles=jdbc.queryForList("SELECT assignee_role FROM workflow_steps ws JOIN workflow_templates wt ON wt.id=ws.template_id " +
                    "WHERE wt.process_definition_id=? AND ws.step_key=?",String.class,next.getProcessDefinitionId(),next.getTaskDefinitionKey());
            if (roles.isEmpty()) return;
            String role=roles.getFirst();
            if ("STUDENT".equals(role))
                recipients=jdbc.queryForList("SELECT DISTINCT u.email FROM members m JOIN users u ON u.id=m.user_id " +
                        "WHERE m.thesis_id=? AND u.status='ACTIVE'",String.class,thesis.getId());
            else recipients=jdbc.queryForList("SELECT DISTINCT u.email FROM user_roles ur JOIN roles r ON r.id=ur.role_id " +
                    "JOIN users u ON u.id=ur.user_id WHERE r.role_code=? AND u.status='ACTIVE' " +
                    "AND (ur.thesis_round_id IS NULL OR ur.thesis_round_id=?)",String.class,role,thesis.getPhaseId());
        }
        for (String recipient:recipients) workflowMail.enqueue(thesis.getPhaseId(),next.getTaskDefinitionKey(),
                "NEXT_"+next.getId(),recipient,"[ĐATN] Việc cần xử lý: "+next.getName(),
                "Hồ sơ “"+thesis.getTitle()+"” đã đến bước cần xử lý.",roundName,next.getName(),deadline);
    }

    private void enqueueCaseFeedback(Thesis thesis, Task task, boolean approved, String comment) {
        String roundName=jdbc.queryForObject("SELECT name FROM thesis_rounds WHERE id=?",String.class,thesis.getPhaseId());
        List<String> recipients=jdbc.queryForList("SELECT DISTINCT email FROM users WHERE id IN " +
                "(SELECT user_id FROM members WHERE thesis_id=? UNION SELECT lecturer_id FROM theses WHERE id=?) " +
                "AND status='ACTIVE'",String.class,thesis.getId(),thesis.getId());
        String message="Hồ sơ “"+thesis.getTitle()+"”: "+(approved ? "đã được duyệt." : "cần chỉnh sửa.") +
                (comment.isBlank() ? "" : "\nNhận xét: "+comment);
        for (String recipient:recipients) workflowMail.enqueue(thesis.getPhaseId(),task.getTaskDefinitionKey(),
                "DONE_"+task.getId(),recipient,"[ĐATN] Kết quả: "+task.getName(),message,
                roundName,task.getName(),"—");
    }

    private void enqueueDefenseSchedule(Thesis thesis, Task task, Map<String,Object> vars) {
        String roundName=jdbc.queryForObject("SELECT name FROM thesis_rounds WHERE id=?",String.class,thesis.getPhaseId());
        List<String> recipients=jdbc.queryForList("SELECT DISTINCT email FROM users WHERE id IN " +
                "(SELECT user_id FROM members WHERE thesis_id=? UNION SELECT lecturer_id FROM theses WHERE id=?) " +
                "AND status='ACTIVE'",String.class,thesis.getId(),thesis.getId());
        String deadline=OffsetDateTime.parse((String)vars.get("defenseAt"))
                .atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        String message="Lịch bảo vệ hồ sơ “"+thesis.getTitle()+"” đã được công bố. Phòng: "+vars.get("room")+
                ". Hội đồng: "+vars.get("councilName")+".";
        for (String recipient:recipients) workflowMail.enqueue(thesis.getPhaseId(),task.getTaskDefinitionKey(),
                "SCHEDULE_"+task.getId(),recipient,"[ĐATN] Lịch bảo vệ đồ án",message,
                roundName,task.getName(),deadline);
    }

    /** currentStatus = taskDefinitionKey của task đang chờ, hoặc COMPLETED khi process kết thúc. */
    private void refreshStatus(Thesis thesis) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(thesis.getProcessInstanceId())
                .active()
                .list();
        thesis.setCurrentStatus(tasks.isEmpty() ? STATUS_COMPLETED : tasks.get(0).getTaskDefinitionKey());
    }

    private void checkStepWindow(String roundId, String stepKey) {
        List<Map<String,Object>> windows=jdbc.queryForList("SELECT opens_at,closes_at FROM round_step_windows WHERE round_id=? AND step_key=?",roundId,stepKey);
        if (windows.isEmpty()) return;
        OffsetDateTime now=OffsetDateTime.now();
        Map<String,Object> window=windows.getFirst();
        if (now.isBefore(asOffset(window.get("opens_at"))) || now.isAfter(asOffset(window.get("closes_at"))))
            throw new IllegalArgumentException("Biểu mẫu bước này chưa mở hoặc đã hết hạn");
    }

    private Task findTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new ResourceNotFoundException("Task not found: " + taskId);
        }
        return task;
    }
    private List<String> roles(String userId) {
        return jdbc.queryForList("SELECT DISTINCT r.role_code FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?",String.class,userId);
    }
    private boolean hasRoleForRound(String userId,String role,String roundId) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code=? AND (ur.thesis_round_id IS NULL OR ur.thesis_round_id=?)",Integer.class,userId,role,roundId);
        return count!=null && count>0;
    }
    private String taskRound(Task task) {
        return jdbc.queryForObject("SELECT phase_id FROM theses WHERE process_instance_id=?",String.class,task.getProcessInstanceId());
    }
    /** Ids of the students of the thesis group (comma separated process variable set when the thesis is registered). */
    private List<String> groupStudentIds(Task task) {
        Object ids = runtimeService.getVariable(task.getProcessInstanceId(), "studentIds");
        return ids instanceof String text && !text.isBlank() ? List.of(text.split(",")) : List.of();
    }

    private boolean isGroupMember(Task task,User actor) {
        return groupStudentIds(task).contains(actor.getId());
    }

    /** The group leader can lock a member out of submitting (members.locked): they still see everything. */
    private boolean isLockedMember(Task task,String userId) {
        Integer locked=jdbc.queryForObject("SELECT count(*) FROM members m JOIN theses t ON t.id=m.thesis_id WHERE t.process_instance_id=? AND m.user_id=? AND m.locked=true",Integer.class,task.getProcessInstanceId(),userId);
        return locked!=null && locked>0;
    }

    /** Process instances of the theses the student leads or belongs to. */
    private List<String> groupProcessIds(String userId) {
        return jdbc.queryForList("SELECT t.process_instance_id FROM theses t WHERE t.process_instance_id IS NOT NULL AND (t.student_id=? OR EXISTS (SELECT 1 FROM members m WHERE m.thesis_id=t.id AND m.user_id=?))",String.class,userId,userId);
    }

    /** A student task is one held by, or offered to, the students of the group. */
    private boolean isStudentGroupTask(Task task) {
        List<String> students = groupStudentIds(task);
        if (students.isEmpty()) return false;
        if (task.getAssignee()!=null && students.contains(task.getAssignee())) return true;
        return taskService.getIdentityLinksForTask(task.getId()).stream().anyMatch(link -> link.getUserId()!=null && students.contains(link.getUserId()));
    }

    private boolean canAccessTask(Task task,User actor) {
        if (!permissionGuard.allowed("VIEW_TAC_VU_QUY_TRINH",taskRound(task))) return false;
        if ("ADMIN".equals(actor.getUserType())) return true;
        if (isGroupMember(task,actor) && isStudentGroupTask(task)) return true;
        if (task.getAssignee()!=null) return task.getAssignee().equals(actor.getId());
        String roundId=jdbc.queryForObject("SELECT phase_id FROM theses WHERE process_instance_id=?",String.class,task.getProcessInstanceId());
        return taskService.getIdentityLinksForTask(task.getId()).stream().anyMatch(link ->
                link.getUserId()!=null && link.getUserId().equals(actor.getId())
                || link.getGroupId()!=null && hasRoleForRound(actor.getId(),link.getGroupId(),roundId));
    }
    private OffsetDateTime asOffset(Object value) {
        if (value instanceof OffsetDateTime date) return date;
        return ((java.sql.Timestamp)value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
    private void authorizeThesis(Thesis thesis) {
        User actor=currentUserService.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Cần đăng nhập"));
        if ("ADMIN".equals(actor.getUserType()) || thesis.getStudent().getId().equals(actor.getId())
                || thesis.getLecturer().getId().equals(actor.getId()) || hasRoleForRound(actor.getId(),"FACULTY_STAFF",thesis.getPhaseId())
                || hasRoleForRound(actor.getId(),"COMMITTEE",thesis.getPhaseId())) return;
        Integer member=jdbc.queryForObject("SELECT count(*) FROM members WHERE thesis_id=? AND user_id=?",Integer.class,thesis.getId(),actor.getId());
        if (member!=null && member>0) return;
        throw new AccessDeniedException("Không được xem hồ sơ này");
    }
    private void authorizeTask(Task task,User actor) {
        if (!canAccessTask(task,actor)) throw new AccessDeniedException("Không thuộc nhóm được xử lý tác vụ");
    }

    private User findUser(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private Thesis findThesis(String id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thesis not found: " + id));
    }

    private TaskResponse toTaskResponse(Task task) {
        TaskResponse response = new TaskResponse(
                task.getId(),
                task.getName(),
                task.getTaskDefinitionKey(),
                task.getAssignee(),
                task.getProcessInstanceId(),
                task.getCreateTime() == null ? null
                        : task.getCreateTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        List<String> kinds=jdbc.queryForList("SELECT ws.kind FROM workflow_steps ws JOIN workflow_templates wt ON wt.id=ws.template_id WHERE wt.process_definition_id=? AND ws.step_key=?",String.class,task.getProcessDefinitionId(),task.getTaskDefinitionKey());
        if (!kinds.isEmpty()) response.setKind(kinds.getFirst());
        if (task.getDueDate()!=null) response.setDueDate(task.getDueDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        var cases = jdbc.queryForList("SELECT t.id,t.title,coalesce((SELECT string_agg(u.full_name, ', ' ORDER BY (u.id=t.student_id) DESC, u.full_name) FROM members m JOIN users u ON u.id=m.user_id WHERE m.thesis_id=t.id),s.full_name) AS student_name FROM theses t JOIN users s ON s.id=t.student_id WHERE t.process_instance_id=?", task.getProcessInstanceId());
        if (!cases.isEmpty()) {
            var item = cases.getFirst();
            response.setThesisId((String)item.get("id"));
            response.setThesisTitle((String)item.get("title"));
            response.setStudentName((String)item.get("student_name"));
        }
        if (task.getAssignee()!=null) {
            var names = jdbc.queryForList("SELECT full_name FROM users WHERE id=?", String.class, task.getAssignee());
            if (!names.isEmpty()) response.setAssigneeName(names.getFirst());
        }
        User actor=currentUserService.getCurrentUser().orElseThrow();
        // A locked groupmate keeps read access but cannot claim or complete.
        boolean locked=isLockedMember(task,actor.getId());
        boolean write=permissionGuard.allowed("TASKS_UPDATE",taskRound(task)) && !locked;
        if (locked) {
            List<String> leaders=jdbc.queryForList("SELECT u.full_name FROM theses t JOIN users u ON u.id=t.student_id WHERE t.process_instance_id=?",String.class,task.getProcessInstanceId());
            response.setLockedBy(leaders.isEmpty() ? "trưởng nhóm" : leaders.getFirst());
        }
        boolean groupMemberHoldingTask = task.getAssignee()!=null && !actor.getId().equals(task.getAssignee()) && isGroupMember(task,actor) && isStudentGroupTask(task);
        response.setCanClaim(write && (task.getAssignee()==null || groupMemberHoldingTask) && canAccessTask(task,actor));
        response.setCanComplete(write && (actor.getId().equals(task.getAssignee()) || groupMemberHoldingTask)
            && (!"REVIEW".equals(response.getKind()) || permissionGuard.allowed("TASKS_APPROVE",taskRound(task))));
        return response;
    }

    private ThesisResponse response(Thesis thesis) {
        return presentation.describe(thesis, thesisMapper.toResponse(thesis));
    }
}

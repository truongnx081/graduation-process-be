package com.example.graduationprocessbe.controller;

import com.example.graduationprocessbe.dto.ApiResponseWrapper;
import com.example.graduationprocessbe.dto.PageResponse;
import com.example.graduationprocessbe.dto.request.CreateThesisRequest;
import com.example.graduationprocessbe.dto.request.UpdateThesisRequest;
import com.example.graduationprocessbe.dto.response.ActivityHistoryResponse;
import com.example.graduationprocessbe.dto.response.AuditLogResponse;
import com.example.graduationprocessbe.dto.response.MemberResponse;
import com.example.graduationprocessbe.dto.response.TaskResponse;
import com.example.graduationprocessbe.dto.response.ThesisResponse;
import com.example.graduationprocessbe.service.AuditLogService;
import com.example.graduationprocessbe.service.ThesisProcessService;
import com.example.graduationprocessbe.service.ThesisService;
import com.example.graduationprocessbe.service.WorkflowPresentationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static com.example.graduationprocessbe.util.ApiResponses.ok;

@RestController
@RequestMapping("/api/theses")
@RequiredArgsConstructor
public class ThesisController {

    private final ThesisProcessService thesisProcessService;
    private final ThesisService thesisService;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbc;
    private final WorkflowPresentationService presentation;
    private final com.example.graduationprocessbe.service.CurrentUserService currentUserService;

    /** Đăng ký đề tài và nhóm trong mốc đăng ký. */
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<ThesisResponse>> createThesis(
            @RequestBody @Valid CreateThesisRequest request) {
        return ok(thesisProcessService.createThesis(request));
    }

    @PostMapping("/{id}/proposal")
    public ResponseEntity<ApiResponseWrapper<ThesisResponse>> submitProposal(
            @PathVariable String id, @RequestBody Map<String,String> request) {
        return ok(thesisProcessService.submitProposal(id, request.get("content")));
    }

    public record GuidanceDecision(@jakarta.validation.constraints.NotNull Boolean approved,
                                   @jakarta.validation.constraints.Size(max = 2000) String comment) {}
    public record RegistrationRetry(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String title,
                                    @jakarta.validation.constraints.NotBlank String lecturerId) {}

    @PostMapping("/{id}/guidance-confirmation")
    public ResponseEntity<ApiResponseWrapper<ThesisResponse>> confirmGuidance(
            @PathVariable String id, @Valid @RequestBody GuidanceDecision request) {
        return ok(thesisProcessService.confirmGuidance(id, request.approved(), request.comment()));
    }

    @PostMapping("/{id}/registration-retry")
    public ResponseEntity<ApiResponseWrapper<ThesisResponse>> retryRegistration(
            @PathVariable String id, @Valid @RequestBody RegistrationRetry request) {
        return ok(thesisProcessService.resubmitRegistration(id, request.title(), request.lecturerId()));
    }

    /** GET /api/theses?keyword=&studentId=&lecturerId=&status=&phaseId=&page=1&size=10&sortBy=createdDate&direction=desc */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<PageResponse<ThesisResponse>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String lecturerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String phaseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        var actor=currentUserService.getCurrentUser().orElseThrow();
        if ("STUDENT".equals(actor.getUserType())) studentId=actor.getId();
        else if ("LECTURER".equals(actor.getUserType()) && !actorHasFacultyRole(actor.getId(),phaseId)) lecturerId=actor.getId();
        return ok(thesisService.search(keyword, studentId, lecturerId, status, phaseId,
                page, size, sortBy, direction));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ThesisResponse>> getThesis(@PathVariable String id) {
        return ok(thesisProcessService.getThesis(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseWrapper<ThesisResponse>> update(
            @PathVariable String id, @RequestBody @Valid UpdateThesisRequest request) {
        return ok(thesisService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseWrapper<Void>> delete(@PathVariable String id) {
        thesisService.delete(id);
        return ok(null);
    }

    @GetMapping("/{id}/tasks")
    public ResponseEntity<ApiResponseWrapper<List<TaskResponse>>> getThesisTasks(@PathVariable String id) {
        return ok(thesisProcessService.getThesisTasks(id));
    }

    /** Các bước process đã/đang đi qua (theo Flowable). */
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponseWrapper<List<ActivityHistoryResponse>>> getHistory(@PathVariable String id) {
        thesisProcessService.getThesis(id);
        return ok(thesisService.getHistory(id));
    }

    /** Audit log của đề tài, phân trang. */
    @GetMapping("/{id}/audit-logs")
    public ResponseEntity<ApiResponseWrapper<PageResponse<AuditLogResponse>>> getAuditLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        String processInstanceId = thesisProcessService.getThesis(id).getProcessInstanceId();
        if (processInstanceId == null) {
            return ok(new PageResponse<>(List.of(),page,size,0,0,true,true));
        }
        return ok(auditLogService.search(processInstanceId, null, null, null, null,
                page, size, sortBy, direction));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<ApiResponseWrapper<List<MemberResponse>>> getMembers(@PathVariable String id) {
        thesisProcessService.getThesis(id);
        return ok(thesisService.getMembers(id));
    }

    @PostMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseWrapper<List<MemberResponse>>> addMember(
            @PathVariable String id, @PathVariable String userId) {
        return ok(thesisService.addMember(id, userId));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseWrapper<List<MemberResponse>>> removeMember(
            @PathVariable String id, @PathVariable String userId) {
        return ok(thesisService.removeMember(id, userId));
    }
    @GetMapping("/{id}/submissions")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> submissions(@PathVariable String id) {
        String processId = thesisProcessService.getThesis(id).getProcessInstanceId();
        var rows = jdbc.queryForList("SELECT s.id,s.step_key,s.content,s.attachment_url,s.submitted_at,u.full_name AS submitted_by FROM thesis_submissions s JOIN users u ON u.id=s.submitted_by WHERE s.thesis_id=? ORDER BY s.submitted_at",id);
        rows.forEach(row -> row.put("step_name", presentation.stepLabel(processId, (String)row.get("step_key"))));
        return ok(rows);
    }
    @GetMapping("/{id}/feedback")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> feedback(@PathVariable String id) {
        String processId = thesisProcessService.getThesis(id).getProcessInstanceId();
        var rows = jdbc.queryForList("SELECT f.id,f.step_key,f.approved,f.comment,f.reviewed_at,u.full_name AS reviewer FROM thesis_feedback f JOIN users u ON u.id=f.reviewer_id WHERE f.thesis_id=? ORDER BY f.reviewed_at",id);
        rows.forEach(row -> row.put("step_name", presentation.stepLabel(processId, (String)row.get("step_key"))));
        return ok(rows);
    }
    @GetMapping("/{id}/defense-schedule")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> defenseSchedule(@PathVariable String id) {
        thesisProcessService.getThesis(id);
        return ok(jdbc.queryForList("SELECT defense_at,room,council_name,notes,published_at FROM defense_schedules WHERE thesis_id=?",id));
    }
    private boolean actorHasFacultyRole(String id,String roundId) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code='FACULTY_STAFF' AND (ur.thesis_round_id IS NULL OR ur.thesis_round_id=?)",Integer.class,id,roundId);
        return count!=null && count>0;
    }
}

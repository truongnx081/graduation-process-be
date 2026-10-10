package com.example.graduationprocessbe.controller;

import com.example.graduationprocessbe.dto.ApiResponseWrapper;
import com.example.graduationprocessbe.service.CoreWorkflowService;
import com.example.graduationprocessbe.service.RoundMailManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static com.example.graduationprocessbe.util.ApiResponses.ok;

@RestController
@RequestMapping("/api/core")
@RequiredArgsConstructor
public class CoreWorkflowController {
    private final CoreWorkflowService core;
    private final RoundMailManagementService roundMail;

    public record YearInput(String code,int startYear) {}
    public record SemesterInput(String academicYearId,int number) {}
    public record RoundInput(String code,String name,String semesterId,Boolean active,
                             OffsetDateTime registrationOpensAt,OffsetDateTime registrationClosesAt) {}
    public record LecturerInput(String lecturerId,String orientation) {}
    public record DraftInput(String name,String sourceId) {}
    public record TemplateInput(String templateId) {}
    public record WindowInput(String stepKey,OffsetDateTime opensAt,OffsetDateTime closesAt) {}
    public record MailSettingsInput(int remindBeforeHours,int remindAfterHours) {}
    public record CampaignInput(String subject,String message,OffsetDateTime scheduledAt) {}

    @GetMapping("/rounds/{id}/mail-settings") @PreAuthorize("@permissionGuard.allowed('VIEW_MOC_NOP_THONG_BAO', #id)")
    public ResponseEntity<ApiResponseWrapper<Map<String,Object>>> mailSettings(@PathVariable String id) { return ok(roundMail.settings(id)); }
    @PutMapping("/rounds/{id}/mail-settings") @PreAuthorize("@permissionGuard.allowed('SCHEDULE_UPDATE', #id)")
    public ResponseEntity<ApiResponseWrapper<Void>> saveMailSettings(@PathVariable String id,@RequestBody MailSettingsInput input) {
        roundMail.saveSettings(id,input.remindBeforeHours(),input.remindAfterHours()); return ok(null);
    }
    @GetMapping("/rounds/{id}/mail-campaigns") @PreAuthorize("@permissionGuard.allowed('VIEW_MOC_NOP_THONG_BAO', #id)")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> mailCampaigns(@PathVariable String id) { return ok(roundMail.campaigns(id)); }
    @PostMapping("/rounds/{id}/mail-campaigns") @PreAuthorize("@permissionGuard.allowed('SCHEDULE_CREATE', #id)")
    public ResponseEntity<ApiResponseWrapper<String>> scheduleMail(@PathVariable String id,@RequestBody CampaignInput input) {
        return ok(roundMail.schedule(id,input.subject(),input.message(),input.scheduledAt()));
    }
    @GetMapping("/rounds/{id}/mail-deliveries") @PreAuthorize("@permissionGuard.allowed('VIEW_MOC_NOP_THONG_BAO', #id)")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> mailDeliveries(@PathVariable String id) { return ok(roundMail.deliveries(id)); }

    @GetMapping("/years") public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> years() { return ok(core.years()); }
    @GetMapping("/semesters") public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> semesters() { return ok(core.semesters()); }
    @GetMapping("/rounds") public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> rounds() { return ok(core.rounds()); }
    @GetMapping("/rounds/{id}/steps") public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> roundSteps(@PathVariable String id) { return ok(core.roundSteps(id)); }
    @GetMapping("/overdue") @PreAuthorize("hasAnyRole('ADMIN','FACULTY_STAFF')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> overdue() { return ok(core.overdueTasks()); }
    @GetMapping("/rounds/{id}/lecturers") public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> lecturers(@PathVariable String id) { return ok(core.lecturers(id)); }
    @GetMapping("/rounds/{id}/windows") public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> windows(@PathVariable String id) { return ok(core.windows(id)); }
    @GetMapping("/lecturer-candidates") @PreAuthorize("hasAuthority('VIEW_DS_DOT_DO_AN')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> lecturerCandidates() { return ok(core.lecturerCandidates()); }
    @GetMapping("/rounds/{id}/student-candidates") @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> studentCandidates(@PathVariable String id) { return ok(core.studentCandidates(id)); }
    @GetMapping("/templates") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_QUY_TRINH')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> templates() { return ok(core.templates()); }
    @GetMapping("/templates/{id}/steps") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_QUY_TRINH')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> steps(@PathVariable String id) { return ok(core.steps(id)); }

    @PostMapping("/years") @PreAuthorize("@permissionGuard.allowed('CALENDAR_CREATE', null)")
    public ResponseEntity<ApiResponseWrapper<Void>> year(@RequestBody YearInput input) { core.createYear(input.code(),input.startYear()); return ok(null); }
    @PostMapping("/semesters") @PreAuthorize("@permissionGuard.allowed('CALENDAR_CREATE', null)")
    public ResponseEntity<ApiResponseWrapper<Void>> semester(@RequestBody SemesterInput input) { core.createSemester(input.academicYearId(),input.number()); return ok(null); }
    @PutMapping("/years/{id}") @PreAuthorize("@permissionGuard.allowed('CALENDAR_UPDATE', null)")
    public ResponseEntity<ApiResponseWrapper<Void>> updateYear(@PathVariable String id,@RequestBody YearInput input) {
        core.updateYear(id,input.code(),input.startYear()); return ok(null);
    }
    @PutMapping("/semesters/{id}") @PreAuthorize("@permissionGuard.allowed('CALENDAR_UPDATE', null)")
    public ResponseEntity<ApiResponseWrapper<Void>> updateSemester(@PathVariable String id,@RequestBody SemesterInput input) {
        core.updateSemester(id,input.academicYearId(),input.number()); return ok(null);
    }
    @PostMapping("/rounds") @PreAuthorize("@permissionGuard.allowed('ROUNDS_CREATE', null)")
    public ResponseEntity<ApiResponseWrapper<Void>> round(@RequestBody RoundInput input) {
        core.createRound(input.code(),input.name(),input.semesterId(),input.registrationOpensAt(),input.registrationClosesAt(), input.active() == null || input.active()); return ok(null);
    }
    @PutMapping("/rounds/{id}") @PreAuthorize("@permissionGuard.allowed('ROUNDS_UPDATE', #id)")
    public ResponseEntity<ApiResponseWrapper<Void>> updateRound(@PathVariable String id,@RequestBody RoundInput input) {
        if (input.active() == null) throw new IllegalArgumentException("Cần chọn trạng thái Mở hoặc Đóng");
        core.updateRound(id,input.name(),input.active(),input.semesterId(),input.registrationOpensAt(),input.registrationClosesAt()); return ok(null);
    }
    @PostMapping("/rounds/{id}/lecturers") @PreAuthorize("@permissionGuard.allowed('ROUNDS_CREATE', #id)")
    public ResponseEntity<ApiResponseWrapper<Void>> addLecturer(@PathVariable String id,@RequestBody LecturerInput input) {
        core.addLecturer(id,input.lecturerId(),input.orientation()); return ok(null);
    }
    @DeleteMapping("/rounds/{id}/lecturers/{lecturerId}") @PreAuthorize("@permissionGuard.allowed('ROUNDS_DELETE', #id)")
    public ResponseEntity<ApiResponseWrapper<Void>> removeLecturer(@PathVariable String id,@PathVariable String lecturerId) {
        core.removeLecturer(id,lecturerId); return ok(null);
    }
    @PutMapping("/rounds/{id}/windows") @PreAuthorize("@permissionGuard.allowed('SCHEDULE_UPDATE', #id)")
    public ResponseEntity<ApiResponseWrapper<Void>> window(@PathVariable String id,@RequestBody WindowInput input) {
        core.setWindow(id,input.stepKey(),input.opensAt(),input.closesAt()); return ok(null);
    }
    @PostMapping("/templates") @PreAuthorize("@permissionGuard.allowed('WORKFLOW_CREATE', null)")
    public ResponseEntity<ApiResponseWrapper<String>> draft(@RequestBody DraftInput input) { return ok(core.createDraft(input.name(),input.sourceId())); }
    @PutMapping("/templates/{id}/steps") @PreAuthorize("@permissionGuard.allowed('WORKFLOW_UPDATE', null)")
    public ResponseEntity<ApiResponseWrapper<Void>> replaceSteps(@PathVariable String id,@RequestBody List<CoreWorkflowService.StepInput> input) {
        core.replaceSteps(id,input); return ok(null);
    }
    @PostMapping("/templates/{id}/publish") @PreAuthorize("@permissionGuard.allowed('WORKFLOW_APPROVE', null)")
    public ResponseEntity<ApiResponseWrapper<String>> publish(@PathVariable String id) { return ok(core.publish(id)); }
    @PutMapping("/rounds/{id}/workflow") @PreAuthorize("@permissionGuard.allowed('ROUNDS_UPDATE', #id)")
    public ResponseEntity<ApiResponseWrapper<Void>> assign(@PathVariable String id,@RequestBody TemplateInput input) {
        core.assignTemplate(id,input.templateId()); return ok(null);
    }
}

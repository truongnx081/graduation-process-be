package com.example.graduationprocessbe.controller;

import com.example.graduationprocessbe.dto.ApiResponseWrapper;
import com.example.graduationprocessbe.dto.PageResponse;
import com.example.graduationprocessbe.dto.request.CreateAuditLogRequest;
import com.example.graduationprocessbe.dto.response.AuditLogResponse;
import com.example.graduationprocessbe.service.AuditLogService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

import static com.example.graduationprocessbe.util.ApiResponses.ok;

@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasAuthority('VIEW_NHAT_KY_THAO_TAC')")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /** Ghi log thủ công. payload (nếu có) phải là JSON hợp lệ. */
    @PreAuthorize("hasAuthority('AUDIT_CREATE')")
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<AuditLogResponse>> create(
            @RequestBody @Valid CreateAuditLogRequest request) {
        return ok(auditLogService.create(request));
    }

    /** GET /api/audit-logs?processInstanceId=&actorId=&actionName=&from=2026-01-01T00:00:00&to=...&page=1&size=10 */
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<PageResponse<AuditLogResponse>>> search(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String actionName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return ok(auditLogService.search(processInstanceId, actorId, actionName, from, to,
                page, size, sortBy, direction));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<AuditLogResponse>> getById(@PathVariable String id) {
        return ok(auditLogService.getById(id));
    }
}

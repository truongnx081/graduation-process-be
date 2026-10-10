package com.example.graduationprocessbe.service.impl;

import com.example.graduationprocessbe.dto.PageResponse;
import com.example.graduationprocessbe.dto.request.CreateAuditLogRequest;
import com.example.graduationprocessbe.dto.response.AuditLogResponse;
import com.example.graduationprocessbe.entity.AuditLog;
import com.example.graduationprocessbe.entity.User;
import com.example.graduationprocessbe.exception.ApplicationException;
import com.example.graduationprocessbe.exception.ResourceNotFoundException;
import com.example.graduationprocessbe.mapper.UserMapper;
import com.example.graduationprocessbe.repository.AuditLogRepository;
import com.example.graduationprocessbe.repository.UserRepository;
import com.example.graduationprocessbe.service.AuditLogService;
import com.example.graduationprocessbe.util.PageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private static final Set<String> SORT_FIELDS = Set.of("executionTime", "actionName", "stepName", "createdDate");

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final JsonMapper jsonMapper;
    private final com.example.graduationprocessbe.service.CurrentUserService currentUserService;

    @Override
    @Transactional
    public AuditLogResponse create(CreateAuditLogRequest request) {
        User actor = currentUserService.getCurrentUser().orElseThrow();
        Map<String, Object> payload = null;
        if (request.getPayload() != null && !request.getPayload().isBlank()) {
            try {
                payload = jsonMapper.readValue(request.getPayload(), Map.class);
            } catch (RuntimeException e) {
                throw new ApplicationException("INVALID_PAYLOAD", "Payload must be a valid JSON object",
                        HttpStatus.BAD_REQUEST);
            }
        }
        return toResponse(save(request.getProcessInstanceId(), actor, request.getActionName(),
                request.getStepName(), payload));
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public void record(String processInstanceId, User actor, String actionName, String stepName, Object payload) {
        Map<String, Object> map = payload == null ? null
                : payload instanceof Map<?, ?> m ? (Map<String, Object>) m
                : jsonMapper.convertValue(payload, Map.class);
        save(processInstanceId, actor, actionName, stepName, map);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogResponse getById(String id) {
        return toResponse(auditLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Audit log not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(String processInstanceId, String actorId, String actionName,
                                                 LocalDateTime from, LocalDateTime to,
                                                 int page, int size, String sortBy, String direction) {
        Specification<AuditLog> spec = (root, query, cb) -> cb.conjunction();
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("processInstanceId"), processInstanceId));
        }
        if (actorId != null && !actorId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("actor").get("id"), actorId));
        }
        if (actionName != null && !actionName.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("actionName"), actionName));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("executionTime"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("executionTime"), to));
        }
        Page<AuditLog> result = auditLogRepository.findAll(spec,
                PageUtil.of(page, size, sortBy, direction, SORT_FIELDS, "executionTime"));
        return PageResponse.from(result, this::toResponse);
    }

    private AuditLog save(String processInstanceId, User actor, String actionName, String stepName,
                          Map<String, Object> payload) {
        AuditLog log = new AuditLog();
        log.setProcessInstanceId(processInstanceId);
        log.setActor(actor);
        log.setActionName(actionName);
        log.setStepName(stepName);
        log.setPayload(payload);
        log.setExecutionTime(LocalDateTime.now());
        return auditLogRepository.save(log);
    }

    private AuditLogResponse toResponse(AuditLog log) {
        AuditLogResponse response = new AuditLogResponse();
        response.setId(log.getId());
        response.setProcessInstanceId(log.getProcessInstanceId());
        response.setActor(log.getActor() == null ? null : userMapper.toResponse(log.getActor()));
        response.setActionName(log.getActionName());
        response.setStepName(log.getStepName());
        response.setPayload(log.getPayload() == null ? null : jsonMapper.writeValueAsString(log.getPayload()));
        response.setExecutionTime(log.getExecutionTime());
        response.setCreatedDate(log.getCreatedDate());
        response.setLastModifiedDate(log.getLastModifiedDate());
        return response;
    }
}

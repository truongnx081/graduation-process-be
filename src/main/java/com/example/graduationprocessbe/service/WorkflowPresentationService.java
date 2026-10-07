package com.example.graduationprocessbe.service;

import com.example.graduationprocessbe.dto.response.ThesisResponse;
import com.example.graduationprocessbe.entity.Thesis;
import lombok.RequiredArgsConstructor;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowPresentationService {
    private final HistoryService historyService;
    private final TaskService taskService;
    private final JdbcTemplate jdbc;

    public ThesisResponse describe(Thesis thesis, ThesisResponse response) {
        response.setCurrentStatusLabel(statusLabel(thesis.getProcessInstanceId(), thesis.getCurrentStatus()));
        return response;
    }

    public String statusLabel(String processInstanceId, String status) {
        if (status == null || status.isBlank()) return "Chưa bắt đầu";
        if ("COMPLETED".equals(status)) return "Đã hoàn thành";
        if ("PENDING_SUPERVISOR".equals(status) || "REGISTERED".equals(status)) return "Chờ giảng viên xác nhận";
        if ("GUIDANCE_REJECTED".equals(status)) return "Giảng viên từ chối hướng dẫn";
        String label = stepLabel(processInstanceId, status);
        if (label != null) return label;
        if (processInstanceId != null) {
            var tasks = taskService.createTaskQuery().processInstanceId(processInstanceId).active().list();
            for (var task : tasks) if (status.equals(task.getTaskDefinitionKey())) return task.getName();
        }
        return "Đang xử lý hồ sơ";
    }

    public String stepLabel(String processInstanceId, String stepKey) {
        if ("confirmGuidance".equals(stepKey)) return "Giảng viên xác nhận hướng dẫn";
        if (processInstanceId == null || stepKey == null) return null;
        var process = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (process == null) return null;
        List<String> labels = jdbc.queryForList("SELECT ws.label FROM workflow_steps ws JOIN workflow_templates wt ON wt.id=ws.template_id WHERE wt.process_definition_id=? AND ws.step_key=?",
                String.class, process.getProcessDefinitionId(), stepKey);
        return labels.isEmpty() ? null : labels.getFirst();
    }
}

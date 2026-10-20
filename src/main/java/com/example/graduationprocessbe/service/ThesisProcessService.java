package com.example.graduationprocessbe.service;

import com.example.graduationprocessbe.dto.request.CreateThesisRequest;
import com.example.graduationprocessbe.dto.response.TaskResponse;
import com.example.graduationprocessbe.dto.response.ThesisResponse;

import java.util.List;
import java.util.Map;

public interface ThesisProcessService {

    /** Đăng ký đề tài trong mốc đăng ký, chờ GVHD xác nhận. */
    ThesisResponse createThesis(CreateThesisRequest request);

    ThesisResponse submitProposal(String thesisId, String content);
    ThesisResponse confirmGuidance(String thesisId, boolean approved, String comment);

    ThesisResponse resubmitRegistration(String thesisId);

    ThesisResponse getThesis(String thesisId);

    /** A student of the group signs the lecturer-approved proposal; returns who has signed so far. */
    List<java.util.Map<String,Object>> signProposal(String thesisId, String signature);

    /** Who of the group has signed the approved proposal (the image only on the caller's own row). */
    List<java.util.Map<String,Object>> proposalSignatures(String thesisId);

    /** Các task đang chờ của một Thesis. */
    List<TaskResponse> getThesisTasks(String thesisId);

    /** Tìm task theo assignee và/hoặc candidateGroup; để null để bỏ qua điều kiện. */
    List<TaskResponse> findTasks(String assignee, String candidateGroup);

    TaskResponse getTask(String taskId);

    /** Nhận task nhóm về cho một user (đặt assignee). */
    TaskResponse claimTask(String taskId, String userId);

    /** Hoàn tất task, truyền biến vào process, cập nhật currentStatus của Thesis và ghi audit log. */
    ThesisResponse completeTask(String taskId, Map<String, Object> variables);
}

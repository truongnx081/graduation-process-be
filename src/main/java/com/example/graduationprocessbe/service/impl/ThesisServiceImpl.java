package com.example.graduationprocessbe.service.impl;

import com.example.graduationprocessbe.dto.PageResponse;
import com.example.graduationprocessbe.dto.request.UpdateThesisRequest;
import com.example.graduationprocessbe.dto.response.ActivityHistoryResponse;
import com.example.graduationprocessbe.dto.response.MemberResponse;
import com.example.graduationprocessbe.dto.response.ThesisResponse;
import com.example.graduationprocessbe.entity.Member;
import com.example.graduationprocessbe.entity.MemberId;
import com.example.graduationprocessbe.entity.Thesis;
import com.example.graduationprocessbe.entity.User;
import com.example.graduationprocessbe.exception.ApplicationException;
import com.example.graduationprocessbe.exception.ResourceNotFoundException;
import com.example.graduationprocessbe.exception.ResponseDetails;
import com.example.graduationprocessbe.mapper.ThesisMapper;
import com.example.graduationprocessbe.mapper.UserMapper;
import com.example.graduationprocessbe.repository.MemberRepository;
import com.example.graduationprocessbe.repository.ThesisRepository;
import com.example.graduationprocessbe.repository.UserRepository;
import com.example.graduationprocessbe.service.ThesisService;
import com.example.graduationprocessbe.service.WorkflowPresentationService;
import com.example.graduationprocessbe.util.PageUtil;
import lombok.RequiredArgsConstructor;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ThesisServiceImpl implements ThesisService {

    private static final Set<String> SORT_FIELDS = Set.of("title", "currentStatus", "phaseId", "createdDate");

    private final ThesisRepository thesisRepository;
    private final UserRepository userRepository;
    private final MemberRepository memberRepository;
    private final ThesisMapper thesisMapper;
    private final UserMapper userMapper;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final WorkflowPresentationService presentation;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ThesisResponse> search(String keyword, String studentId, String lecturerId, String status,
                                               String phaseId, int page, int size, String sortBy, String direction) {
        Specification<Thesis> spec = (root, query, cb) -> cb.conjunction();
        if (keyword != null && !keyword.isBlank()) {
            String pattern = PageUtil.likePattern(keyword);
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\')));
        }
        if (studentId != null && !studentId.isBlank()) {
            List<String> groupThesisIds=memberRepository.findByUserId(studentId).stream().map(Member::getThesisId).toList();
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.equal(root.get("student").get("id"), studentId),root.get("id").in(groupThesisIds)));
        }
        if (lecturerId != null && !lecturerId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("lecturer").get("id"), lecturerId));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("currentStatus"), status));
        }
        if (phaseId != null && !phaseId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("phaseId"), phaseId));
        }
        Page<Thesis> result = thesisRepository.findAll(spec,
                PageUtil.of(page, size, sortBy, direction, SORT_FIELDS, "createdDate"));
        return PageResponse.from(result, thesis -> presentation.describe(thesis, thesisMapper.toResponse(thesis)));
    }

    @Override
    @Transactional
    public ThesisResponse update(String id, UpdateThesisRequest request) {
        Thesis thesis = find(id);
        if (request.getTitle() != null) {
            thesis.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            thesis.setDescription(request.getDescription());
        }
        if (request.getPhaseId() != null) {
            if (!request.getPhaseId().equals(thesis.getPhaseId())) throw new IllegalArgumentException("Không được đổi đợt của hồ sơ đã đăng ký");
            thesis.setPhaseId(request.getPhaseId());
        }
        if (request.getLecturerId() != null) {
            if (thesis.getProcessInstanceId()!=null && !request.getLecturerId().equals(thesis.getLecturer().getId()))
                throw new IllegalArgumentException("Không được đổi GVHD sau khi quy trình đã bắt đầu");
            thesis.setLecturer(userRepository.findById(request.getLecturerId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "User not found: " + request.getLecturerId())));
        }
        thesis = thesisRepository.save(thesis);
        return presentation.describe(thesis, thesisMapper.toResponse(thesis));
    }

    @Override
    @Transactional
    public void delete(String id) {
        Thesis thesis = find(id);
        String processInstanceId = thesis.getProcessInstanceId();
        if (processInstanceId != null && runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() > 0) {
            runtimeService.deleteProcessInstance(processInstanceId, "Thesis deleted");
        }
        memberRepository.deleteByThesisId(id);
        thesisRepository.delete(thesis);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberResponse> getMembers(String thesisId) {
        find(thesisId);
        return membersOf(thesisId);
    }

    @Override
    @Transactional
    public List<MemberResponse> addMember(String thesisId, String userId) {
        Thesis thesis=find(thesisId);
        if (thesis.getProcessInstanceId()!=null) throw new IllegalArgumentException("Thành viên nhóm được chốt khi đăng ký đề tài");
        User student=userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (!"STUDENT".equals(student.getUserType())) throw new IllegalArgumentException("Chỉ sinh viên được tham gia nhóm");
        if (memberRepository.findByThesisId(thesisId).size()>=2) throw new IllegalArgumentException("Nhóm tối đa 2 sinh viên");
        if (memberRepository.findByUserId(userId).stream().anyMatch(m -> thesis.getPhaseId().equals(m.getThesisRoundId())))
            throw new IllegalArgumentException("Sinh viên đã thuộc nhóm khác trong đợt");
        if (memberRepository.existsById(new MemberId(userId, thesisId))) {
            throw new ApplicationException(ResponseDetails.DATA_EXISTED);
        }
        Member member=new Member(userId, thesisId); member.setThesisRoundId(thesis.getPhaseId()); memberRepository.save(member);
        return membersOf(thesisId);
    }

    @Override
    @Transactional
    public List<MemberResponse> removeMember(String thesisId, String userId) {
        Thesis thesis=find(thesisId);
        if (thesis.getProcessInstanceId()!=null) throw new IllegalArgumentException("Thành viên nhóm được chốt khi đăng ký đề tài");
        if (thesis.getStudent().getId().equals(userId)) throw new IllegalArgumentException("Không được bỏ sinh viên đăng ký đề tài");
        MemberId key = new MemberId(userId, thesisId);
        if (!memberRepository.existsById(key)) {
            throw new ResourceNotFoundException("User " + userId + " is not a member of thesis " + thesisId);
        }
        memberRepository.deleteById(key);
        return membersOf(thesisId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityHistoryResponse> getHistory(String thesisId) {
        Thesis thesis = find(thesisId);
        if (thesis.getProcessInstanceId() == null) {
            return List.of();
        }
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(thesis.getProcessInstanceId())
                .orderByHistoricActivityInstanceStartTime().asc()
                .list().stream()
                .filter(a -> !"sequenceFlow".equals(a.getActivityType()))
                .map(a -> new ActivityHistoryResponse(
                        a.getActivityId(), a.getActivityName(), a.getActivityType(), a.getAssignee(),
                        toLocal(a.getStartTime()), toLocal(a.getEndTime())))
                .toList();
    }

    private List<MemberResponse> membersOf(String thesisId) {
        return memberRepository.findByThesisId(thesisId).stream()
                .map(m -> new MemberResponse(m.getUserId(), m.getThesisId(),
                        userRepository.findById(m.getUserId()).map(userMapper::toResponse).orElse(null)))
                .toList();
    }

    private Thesis find(String id) {
        return thesisRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thesis not found: " + id));
    }

    private static java.time.LocalDateTime toLocal(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}

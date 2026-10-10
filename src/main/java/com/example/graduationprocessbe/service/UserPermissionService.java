package com.example.graduationprocessbe.service;

import com.example.graduationprocessbe.entity.UserPermissionDeny;
import com.example.graduationprocessbe.exception.ApplicationException;
import com.example.graduationprocessbe.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class UserPermissionService {
    private final UserPermissionDenyRepository denies;
    private final PermissionRepository permissions;
    private final MenuRepository menus;
    private final UserRepository users;
    private final ThesisRoundRepository rounds;
    private final EffectivePermissionService effective;
    private final RbacAuditService audit;
    private final JdbcTemplate jdbc;

    public record Snapshot(long version, Set<String> inheritedCodes, Set<String> effectiveCodes,
        List<String> deniedPermissionIds, List<String> globalDeniedPermissionIds, boolean protectedAdmin) {}

    @Transactional(readOnly=true)
    public Snapshot snapshot(String userId, String roundId) {
        validate(userId, roundId);
        var rows = denies.findByUserId(userId);
        Long version = jdbc.queryForObject("SELECT permissions_version FROM users WHERE id=?", Long.class, userId);
        return new Snapshot(version, effective.inheritedCodes(userId, roundId), effective.codes(userId, roundId),
            rows.stream().filter(d -> Objects.equals(roundId,d.getThesisRoundId())).map(UserPermissionDeny::getPermissionId).toList(),
            rows.stream().filter(d -> d.getThesisRoundId()==null).map(UserPermissionDeny::getPermissionId).toList(), effective.isGlobalAdmin(userId));
    }

    @Transactional
    public Snapshot update(String userId, String roundId, List<String> requested, long expectedVersion) {
        validate(userId, roundId);
        long version=jdbc.queryForObject("SELECT permissions_version FROM users WHERE id=? FOR UPDATE",Long.class,userId);
        if (effective.isGlobalAdmin(userId)) throw new IllegalArgumentException("Không giới hạn quyền của ADMIN hệ thống");
        if (version!=expectedVersion) throw new ApplicationException("STALE_PERMISSIONS","Quyền đã thay đổi, hãy tải lại trước khi lưu",HttpStatus.CONFLICT);
        Set<String> ids=new LinkedHashSet<>(Objects.requireNonNull(requested));
        var menuCatalogue=menus.findAll();
        Set<String> parentIds=menuCatalogue.stream().map(m -> m.getParentId()).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> screenCodes=menuCatalogue.stream()
            .filter(m -> !parentIds.contains(m.getId()) && m.getPath()!=null && m.getPath().startsWith("/"))
            .map(m -> m.getCode()).collect(Collectors.toSet());
        Set<String> supported=permissions.findAll().stream().filter(p -> Boolean.TRUE.equals(p.getEnabled())
            && p.getAction()!=null && screenCodes.contains(p.getMenuCode()))
            .map(p -> p.getId()).collect(Collectors.toSet());
        if (!supported.containsAll(ids)) throw new IllegalArgumentException("Chỉ cấu hình quyền trên menu màn hình có URL và không có menu con");
        var old=denies.findByUserId(userId).stream().filter(d -> Objects.equals(roundId,d.getThesisRoundId())).toList();
        var before=old.stream().map(UserPermissionDeny::getPermissionId).toList();
        denies.deleteAll(old); denies.flush();
        for (String id:ids) {
            UserPermissionDeny row=new UserPermissionDeny(); row.setUserId(userId); row.setPermissionId(id); row.setThesisRoundId(roundId);
            denies.save(row);
        }
        denies.flush();
        jdbc.update("UPDATE users SET permissions_version=permissions_version+1 WHERE id=?",userId);
        audit.record("USER_PERMISSION_DENIES_UPDATED",userId,Map.of("scope",roundId==null?"GLOBAL":roundId,"denies",before),ids);
        return snapshot(userId,roundId);
    }
    private void validate(String userId,String roundId) {
        if (!users.existsById(userId)) throw new IllegalArgumentException("Tài khoản không tồn tại");
        if (roundId!=null && !rounds.existsById(roundId)) throw new IllegalArgumentException("Đợt không tồn tại");
    }
}

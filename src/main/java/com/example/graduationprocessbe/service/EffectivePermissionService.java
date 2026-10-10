package com.example.graduationprocessbe.service;

import com.example.graduationprocessbe.entity.Permission;
import com.example.graduationprocessbe.repository.PermissionRepository;
import com.example.graduationprocessbe.repository.UserRoleRepository;
import com.example.graduationprocessbe.repository.UserPermissionDenyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EffectivePermissionService {
    private final UserRoleRepository memberships;
    private final PermissionRepository permissions;
    private final UserPermissionDenyRepository denies;

    public boolean isGlobalAdmin(String userId) {
        return memberships.findRoleCodesByUserIdAndRoundId(userId, null).contains("ADMIN");
    }

    public Set<String> codes(String userId, String roundId) {
        Set<String> effective = inheritedCodes(userId, roundId);
        if (isGlobalAdmin(userId)) return effective;
        Set<String> deniedIds = denies.findByUserId(userId).stream()
            .filter(d -> d.getThesisRoundId() == null || Objects.equals(d.getThesisRoundId(), roundId))
            .map(com.example.graduationprocessbe.entity.UserPermissionDeny::getPermissionId).collect(Collectors.toSet());
        return applyDenies(effective, deniedIds, permissions.findAll());
    }

    public Set<String> inheritedCodes(String userId, String roundId) {
        return isGlobalAdmin(userId)
            ? permissions.findAll().stream().filter(p -> Boolean.TRUE.equals(p.getEnabled())).map(Permission::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new))
            : new LinkedHashSet<>(memberships.findPermissionCodesByUserIdAndRoundId(userId, roundId));
    }

    public static Set<String> applyDenies(Set<String> inherited, Set<String> deniedIds, List<Permission> catalogue) {
        Set<String> effective = new LinkedHashSet<>(inherited);
        catalogue.stream().filter(p -> deniedIds.contains(p.getId())).map(Permission::getCode).forEach(effective::remove);
        Set<String> accessibleMenus = catalogue.stream().filter(p -> "VIEW".equals(p.getAction()) && effective.contains(p.getCode()))
            .map(Permission::getMenuCode).filter(Objects::nonNull).collect(Collectors.toSet());
        catalogue.stream().filter(p -> p.getMenuCode()!=null && p.getAction()!=null && !"VIEW".equals(p.getAction())
            && !accessibleMenus.contains(p.getMenuCode())).map(Permission::getCode).forEach(effective::remove);
        return effective;
    }

    public static String primaryRole(List<String> roles) {
        return List.of("ADMIN","FACULTY_STAFF","COMMITTEE","LECTURER","STUDENT").stream()
            .filter(roles::contains).findFirst().orElse(roles.isEmpty() ? null : roles.getFirst());
    }
}

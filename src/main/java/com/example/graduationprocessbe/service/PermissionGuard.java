package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;

@Component @RequiredArgsConstructor
public class PermissionGuard {
    private final CurrentUserService current;
    private final EffectivePermissionService permissions;
    public boolean allowed(String code, String roundId) {
        return current.getCurrentUser().map(u -> permissions.codes(u.getId(),roundId).contains(code)).orElse(false);
    }
    public void require(String code, String roundId) {
        if (!allowed(code,roundId)) throw new AccessDeniedException("Không có quyền thực hiện thao tác này");
    }
}

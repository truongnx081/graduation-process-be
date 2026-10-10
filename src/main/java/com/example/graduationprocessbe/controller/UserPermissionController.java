package com.example.graduationprocessbe.controller;

import com.example.graduationprocessbe.dto.ApiResponseWrapper;
import com.example.graduationprocessbe.exception.ResponseDetails;
import com.example.graduationprocessbe.service.UserPermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/rbac/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserPermissionController {
    private final UserPermissionService service;
    public record UpdateRequest(@NotNull List<String> permissionIds, String thesisRoundId, @NotNull Long expectedVersion) {}
    @GetMapping("/{userId}/permissions")
    public ApiResponseWrapper<UserPermissionService.Snapshot> get(@PathVariable String userId,
            @RequestParam(required=false) String thesisRoundId) {
        return new ApiResponseWrapper<>(ResponseDetails.API_SUCCESSFULLY, service.snapshot(userId, thesisRoundId));
    }
    @PutMapping("/{userId}/permissions")
    public ApiResponseWrapper<UserPermissionService.Snapshot> update(@PathVariable String userId, @Valid @RequestBody UpdateRequest request) {
        return new ApiResponseWrapper<>(ResponseDetails.API_SUCCESSFULLY,
            service.update(userId, request.thesisRoundId(), request.permissionIds(), request.expectedVersion()));
    }
}

package com.example.graduationprocessbe.controller;

import com.example.graduationprocessbe.dto.ApiResponseWrapper;
import com.example.graduationprocessbe.dto.request.AssignRoleRequest;
import com.example.graduationprocessbe.dto.request.CreateUserRequest;
import com.example.graduationprocessbe.dto.response.PageResponse;
import com.example.graduationprocessbe.dto.response.UserResponse;
import com.example.graduationprocessbe.exception.ResponseDetails;
import com.example.graduationprocessbe.service.RbacSetupService;
import com.example.graduationprocessbe.service.UserImportService;
import com.example.graduationprocessbe.service.UserService;
import com.example.graduationprocessbe.util.PaginationUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final RbacSetupService rbacSetupService;
    private final UserImportService userImportService;

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_USERS')")
    public ResponseEntity<ApiResponseWrapper<?>> getAllUsers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String userType) {
        List<UserResponse> users = userService.getAllUsers();
        String keyword = PaginationUtils.normalize(search);
        String type = PaginationUtils.normalize(userType);

        List<UserResponse> filtered = users.stream()
                .filter(user -> keyword.isBlank()
                        || PaginationUtils.normalize(user.getFullName()).contains(keyword)
                        || PaginationUtils.normalize(user.getEmail()).contains(keyword)
                        || PaginationUtils.normalize(user.getUsername()).contains(keyword))
                .filter(user -> type.isBlank()
                        || "all".equals(type)
                        || PaginationUtils.normalize(user.getUserType()).equals(type))
                .toList();

        Object data = page == null && size == null
                ? filtered
                : PaginationUtils.page(filtered, page, size);

        return ResponseEntity
                .status(ResponseDetails.API_SUCCESSFULLY.getHttpStatus())
                .body(new ApiResponseWrapper<>(ResponseDetails.API_SUCCESSFULLY, data));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('VIEW_USERS') and hasAuthority('USERS_CREATE') and (#request.roleId == null or #request.roleId == '' or hasAuthority('USERS_ASSIGN_ROLE'))")
    public ResponseEntity<ApiResponseWrapper<UserResponse>> createUser(@RequestBody @Valid CreateUserRequest request) {
        return ResponseEntity
                .status(ResponseDetails.API_SUCCESSFULLY.getHttpStatus())
                .body(new ApiResponseWrapper<>(
                        ResponseDetails.API_SUCCESSFULLY,
                        userService.createUser(request)));
    }

    @GetMapping("/import/template")
    @PreAuthorize("hasAuthority('VIEW_USERS') and hasAuthority('USERS_CREATE')")
    public ResponseEntity<byte[]> importTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("mau-nhap-tai-khoan.xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(userImportService.template());
    }

    /** dryRun=true only validates and returns per-row results; dryRun=false creates the valid rows. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('VIEW_USERS') and hasAuthority('USERS_CREATE')")
    public ApiResponseWrapper<UserImportService.ImportResult> importUsers(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return new ApiResponseWrapper<>(ResponseDetails.API_SUCCESSFULLY, userImportService.importUsers(file, dryRun));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('VIEW_USERS') and hasAuthority('USERS_UPDATE')")
    public ApiResponseWrapper<UserResponse> updateUser(@PathVariable String userId,
        @Valid @RequestBody com.example.graduationprocessbe.dto.request.UpdateUserRequest request) {
        return new ApiResponseWrapper<>(ResponseDetails.API_SUCCESSFULLY,userService.updateUser(userId,request));
    }

    @PostMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('VIEW_USERS') and hasAuthority('USERS_ASSIGN_ROLE')")
    public ResponseEntity<ApiResponseWrapper<Void>> assignRoleToUser(
            @PathVariable String userId,
            @RequestBody @Valid AssignRoleRequest request) {
        rbacSetupService.addMember(userId, request.getRoleId(), request.getThesisRoundId());
        return ResponseEntity
                .status(ResponseDetails.API_SUCCESSFULLY.getHttpStatus())
                .body(new ApiResponseWrapper<>(ResponseDetails.API_SUCCESSFULLY, null));
    }

    @DeleteMapping("/{userId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('VIEW_USERS') and hasAuthority('USERS_ASSIGN_ROLE')")
    public ResponseEntity<ApiResponseWrapper<Void>> removeRoleFromUser(
            @PathVariable String userId,
            @PathVariable String roleId) {
        rbacSetupService.removeMembers(userId, roleId);
        return ResponseEntity
                .status(ResponseDetails.API_SUCCESSFULLY.getHttpStatus())
                .body(new ApiResponseWrapper<>(ResponseDetails.API_SUCCESSFULLY, null));
    }
}

package com.example.graduationprocessbe.service.impl;
import com.example.graduationprocessbe.dto.request.CreateRoleRequest;
import com.example.graduationprocessbe.dto.response.RoleResponse;
import com.example.graduationprocessbe.entity.*;
import com.example.graduationprocessbe.exception.ApplicationException;
import com.example.graduationprocessbe.repository.*;
import com.example.graduationprocessbe.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RoleServiceImpl implements RoleService {
    private final RoleRepository roles;
    private final RolePermissionRepository grants;
    private final RoleAllowedPermissionRepository allowed;
    private final PermissionRepository permissions;
    private final MenuRepository menus;
    private final RbacAuditService audit;
    public List<RoleResponse> getAllRoles() {
        var catalogue=permissions.findAll().stream().filter(p -> Boolean.TRUE.equals(p.getEnabled()))
            .collect(Collectors.toMap(Permission::getId,p -> p));
        var allGrants=grants.findAll(); var allAllowed=allowed.findAll();
        return roles.findAll().stream().sorted(Comparator.comparing(Role::getRoleCode)).map(role -> {
            List<String> limits=allAllowed.stream().filter(a -> role.getId().equals(a.getRoleId()))
                .map(RoleAllowedPermission::getPermissionId).filter(catalogue::containsKey).toList();
            List<String> ids="ADMIN".equals(role.getRoleCode()) ? new ArrayList<>(catalogue.keySet())
                : allGrants.stream().filter(g -> role.getId().equals(g.getRoleId())).map(RolePermission::getPermissionId)
                    .filter(limits::contains).toList();
            return new RoleResponse(role.getId(),role.getRoleCode(),role.getRoleName(),ids.size(),
                ids.stream().map(id -> catalogue.get(id).getCode()).toList(),ids,
                "ADMIN".equals(role.getRoleCode()) ? ids : limits,role.getPermissionsVersion());
        }).toList();
    }
    public List<String> getRolePermissionIds(String roleId) {
        return getAllRoles().stream().filter(r -> roleId.equals(r.getId())).findFirst()
            .orElseThrow(() -> error("NOT_FOUND","Vai trò không tồn tại",HttpStatus.NOT_FOUND)).getPermissionIds();
    }
    public RoleResponse createRole(CreateRoleRequest request) { throw fixed(); }
    public RoleResponse updateRole(String id,CreateRoleRequest request) { throw fixed(); }
    public void deleteRole(String id) { throw fixed(); }
    private ApplicationException fixed() { return error("FIXED_ROLES","Hệ thống sử dụng năm vai trò cố định",HttpStatus.BAD_REQUEST); }
    @Transactional
    public void updateRolePermissions(String roleId,List<String> requested,long expectedVersion) {
        Role role=roles.lockById(roleId).orElseThrow(() -> error("NOT_FOUND","Vai trò không tồn tại",HttpStatus.NOT_FOUND));
        if ("ADMIN".equals(role.getRoleCode())) throw fixed();
        if (role.getPermissionsVersion()!=expectedVersion)
            throw error("STALE_PERMISSIONS","Phân quyền đã thay đổi. Tải lại dữ liệu trước khi lưu.",HttpStatus.CONFLICT);
        var catalogue=permissions.findAll();
        if (requested==null) throw new IllegalArgumentException("Cần danh sách quyền");
        var selected=catalogue.stream().filter(p -> requested.contains(p.getId()) && "VIEW".equals(p.getAction()))
            .map(Permission::getMenuCode).collect(Collectors.toSet());
        var roleLimits=allowed.findByRoleId(roleId).stream().map(RoleAllowedPermission::getPermissionId).collect(Collectors.toSet());
        // Selecting a screen grants its entire supported bundle. User restrictions are stored separately.
        var expanded=catalogue.stream().filter(p -> Boolean.TRUE.equals(p.getEnabled()) && roleLimits.contains(p.getId())
            && (selected.contains(p.getMenuCode()) || (p.getAction()==null && requested.contains(p.getId())))).map(Permission::getId).toList();
        List<String> ids=PermissionPolicy.normalize(expanded,catalogue,menus.findAll());
        if (!new HashSet<>(expanded).containsAll(requested)) throw new IllegalArgumentException("Chọn quyền truy cập màn hình hợp lệ");
        Set<String> limits=allowed.findByRoleId(roleId).stream().map(RoleAllowedPermission::getPermissionId).collect(Collectors.toSet());
        if (!limits.containsAll(ids)) throw error("ROLE_SCOPE","Quyền không thuộc phạm vi của vai trò này",HttpStatus.BAD_REQUEST);
        List<String> before=grants.findByRoleId(roleId).stream().map(RolePermission::getPermissionId).toList();
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if (auth!=null && auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            Set<String> own=auth.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toSet());
            Set<String> changed=new HashSet<>(before); changed.addAll(ids);
            changed.removeIf(id -> before.contains(id) && ids.contains(id));
            if (permissions.findAllById(changed).stream().anyMatch(p -> !own.contains(p.getCode())))
                throw error("FORBIDDEN","Không được cấp hoặc thu hồi quyền ngoài phạm vi của bạn",HttpStatus.FORBIDDEN);
        }
        grants.deleteByRoleId(roleId); grants.flush();
        grants.saveAll(ids.stream().map(id -> { RolePermission g=new RolePermission();g.setRoleId(roleId);g.setPermissionId(id);return g; }).toList());
        role.setPermissionsVersion(role.getPermissionsVersion()+1); roles.save(role);
        audit.record("ROLE_PERMISSIONS_UPDATED",roleId,before,ids);
    }
    private ApplicationException error(String code,String text,HttpStatus status) { return new ApplicationException(code,text,status); }
}

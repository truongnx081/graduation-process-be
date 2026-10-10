package com.example.graduationprocessbe.service.impl;

import com.example.graduationprocessbe.dto.request.CreateMenuRequest;
import com.example.graduationprocessbe.dto.request.UpdateMenuRequest;
import com.example.graduationprocessbe.dto.response.MenuResponse;
import com.example.graduationprocessbe.entity.Menu;
import com.example.graduationprocessbe.exception.ApplicationException;
import com.example.graduationprocessbe.exception.ResponseDetails;
import com.example.graduationprocessbe.mapper.MenuMapper;
import com.example.graduationprocessbe.repository.MenuRepository;
import com.example.graduationprocessbe.repository.UserRoleRepository;
import com.example.graduationprocessbe.repository.PermissionRepository;
import com.example.graduationprocessbe.repository.RolePermissionRepository;
import com.example.graduationprocessbe.entity.Permission;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import com.example.graduationprocessbe.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.graduationprocessbe.repository.UserRepository;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private final MenuRepository menuRepository;
    private final UserRoleRepository userRoleRepository;
    private final MenuMapper menuMapper;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final com.example.graduationprocessbe.service.EffectivePermissionService effectivePermissions;
    private final com.example.graduationprocessbe.service.RbacAuditService audit;
    private final com.example.graduationprocessbe.repository.RoleAllowedPermissionRepository allowed;
    private final com.example.graduationprocessbe.repository.RoleRepository roles;
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    @Override
    @Transactional
    public MenuResponse createMenu(CreateMenuRequest request) {
        if (menuRepository.existsByCode(request.getCode())) {
            throw new ApplicationException(ResponseDetails.DATA_EXISTED);
        }

        Menu menu = menuMapper.toEntity(request);
        validateHierarchy(menu.getId(), menu.getParentId(), menu.getPath());
        menu.setPermissionCode(null);
        menuRepository.saveAndFlush(menu);
        menu.setPermissionCode(accessPermission(menu.getCode(), menu.getLabel()));
        Menu saved = menuRepository.save(menu);
        audit.record("MENU_CREATED",saved.getId(),"",saved.getCode());
        return menuMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public MenuResponse updateMenu(String id, UpdateMenuRequest request) {
        Menu menu = menuRepository.findById(id)
                .orElseThrow(() -> new ApplicationException(ResponseDetails.NOT_FOUND));

        validateHierarchy(id, request.getParentId(), request.getPath());
        if (Set.of("users","roles","permissions","menus","nguoi-dung-phan-quyen","user-permissions").contains(menu.getCode())
            && (Boolean.FALSE.equals(request.getActive()) || !Objects.equals(menu.getParentId(),request.getParentId())
                || !Objects.equals(menu.getPath(),request.getPath()))) throw invalid("Không được ẩn hoặc đổi đường dẫn/nhóm của menu quản trị cốt lõi");
        request.setPermissionCode(menu.getPermissionCode());
        menuMapper.updateEntityFromRequest(request, menu);
        menu.setParentId(request.getParentId());
        boolean hasChildren = menuRepository.findAll().stream().anyMatch(m -> id.equals(m.getParentId()));
        if (!hasChildren) menu.setPermissionCode(accessPermission(menu.getCode(), menu.getLabel()));
        Menu saved = menuRepository.save(menu);
        audit.record("MENU_UPDATED",id,"",saved.getLabel());
        return menuMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteMenu(String id) {
        if (!menuRepository.existsById(id)) {
            throw new ApplicationException(ResponseDetails.NOT_FOUND);
        }
        if (menuRepository.findAll().stream().anyMatch(m -> id.equals(m.getParentId()))) {
            throw invalid("Hãy chuyển hoặc xóa menu con trước khi xóa menu cha");
        }
        Menu menu = menuRepository.findById(id).orElseThrow();
        if (Set.of("users","roles","permissions","menus","nguoi-dung-phan-quyen").contains(menu.getCode())) throw invalid("Không được xóa menu quản trị cốt lõi");
        List<Permission> permissions = permissionRepository.findAll().stream()
                .filter(p -> menu.getCode().equals(p.getMenuCode())).toList();
        Set<String> ids = new HashSet<>(permissions.stream().map(Permission::getId).toList());
        rolePermissionRepository.deleteAll(rolePermissionRepository.findAll().stream()
                .filter(rp -> ids.contains(rp.getPermissionId())).toList());
        rolePermissionRepository.flush();
        for (String permissionId : ids) allowed.deleteAll(allowed.findByPermissionId(permissionId));
        allowed.flush();
        menuRepository.deleteById(id); menuRepository.flush();
        permissionRepository.deleteAll(permissions);
        audit.record("MENU_DELETED",id,menu.getCode(),"");
    }

    @Override
    public List<MenuResponse> getUserMenus(String userId, String roundId) {
        Set<String> userPermissions = effectivePermissions.codes(userId, roundId);

        List<Menu> allActiveMenus = menuRepository.findByActiveTrueOrderBySortOrderAsc();

        // Build hierarchical tree
        List<MenuResponse> rootNodes = buildMenuTree(allActiveMenus);

        // Menu visibility follows assigned permissions for every role.
        // The master tree remains available separately for RBAC administration.
        // Filter tree recursively based on permissionCode:
        // A leaf node is visible if permissionCode is null/empty or in userPermissions
        // A parent node is visible if it has at least one visible child!
        List<MenuResponse> permitted = filterByPermissions(rootNodes, userPermissions);
        return permitted;
    }

    @Override
    public List<MenuResponse> getMasterMenuTree() {
        List<Menu> allActiveMenus = menuRepository.findByActiveTrueOrderBySortOrderAsc();
        return buildMenuTree(allActiveMenus);
    }

    private List<MenuResponse> buildMenuTree(List<Menu> menus) {
        Map<String, MenuResponse> responseMap = new LinkedHashMap<>();
        for (Menu m : menus) {
            responseMap.put(m.getId(), MenuResponse.builder()
                    .id(m.getId())
                    .parentId(m.getParentId())
                    .code(m.getCode())
                    .label(m.getLabel())
                    .icon(m.getIcon())
                    .path(m.getPath())
                    .sortOrder(m.getSortOrder())
                    .permissionCode(m.getPermissionCode())
                    .active(m.getActive())
                    .children(new ArrayList<>())
                    .build());
        }

        List<MenuResponse> roots = new ArrayList<>();
        for (MenuResponse node : responseMap.values()) {
            if (node.getParentId() == null) {
                roots.add(node);
            } else if (responseMap.containsKey(node.getParentId())) {
                responseMap.get(node.getParentId()).getChildren().add(node);
            }
        }
        return roots;
    }

    private List<MenuResponse> filterByPermissions(List<MenuResponse> nodes, Set<String> permissions) {
        List<MenuResponse> result = new ArrayList<>();
        for (MenuResponse node : nodes) {
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                List<MenuResponse> filteredChildren = filterByPermissions(node.getChildren(), permissions);
                // "Menu cha không cần permission riêng, hiện khi có ít nhất một menu con hiện"
                if (!filteredChildren.isEmpty()) {
                    node.setChildren(filteredChildren);
                    result.add(node);
                }
            } else {
                // Leaf node
                if (node.getPermissionCode() != null && permissions.contains(node.getPermissionCode())) {
                    result.add(node);
                }
            }
        }
        return result;
    }

    private String accessPermission(String menuCode, String label) {
        String code = "VIEW_" + menuCode.toUpperCase(Locale.ROOT).replace('-', '_');
        permissionRepository.findByCode(code).ifPresent(p -> {
            if (p.getMenuCode()!=null && !menuCode.equals(p.getMenuCode())) throw invalid("Mã menu tạo quyền trùng với menu khác");
        });
        if (code.length() > 100) throw invalid("Mã menu quá dài để tạo quyền truy cập");
        Permission permission = permissionRepository.findByCode(code).orElseGet(Permission::new);
        permission.setCode(code); permission.setName("Truy cập / Xem: " + label);
        permission.setModule("MENU"); permission.setMenuCode(menuCode); permission.setAction("VIEW"); permission.setEnabled(true);
        permissionRepository.save(permission);
        for (var role : roles.findAll()) {
            if (!allowed.existsByRoleIdAndPermissionId(role.getId(),permission.getId())) {
                var access = new com.example.graduationprocessbe.entity.RoleAllowedPermission();
                access.setRoleId(role.getId()); access.setPermissionId(permission.getId()); allowed.save(access);
            }
        }
        return code;
    }

    private void validateHierarchy(String id, String parentId, String path) {
        if (path != null && !path.startsWith("/")) throw invalid("URL phải bắt đầu bằng /");
        Set<String> seen = new HashSet<>();
        if (id != null) seen.add(id);
        while (parentId != null) {
            if (!seen.add(parentId)) throw invalid("Menu cha không được là chính nó hoặc menu con của nó");
            Menu parent = menuRepository.findById(parentId).orElseThrow(() -> invalid("Menu cha không tồn tại"));
            if (Set.of("users","roles","menus").contains(parent.getCode()))
                throw invalid("Không thêm menu con vào màn hình quản trị chức năng");
            parentId = parent.getParentId();
        }
    }

    private ApplicationException invalid(String message) {
        return new ApplicationException("INVALID_MENU", message, HttpStatus.BAD_REQUEST);
    }
}

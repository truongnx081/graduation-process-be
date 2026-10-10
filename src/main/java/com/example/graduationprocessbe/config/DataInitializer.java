package com.example.graduationprocessbe.config;

import com.example.graduationprocessbe.entity.*;
import com.example.graduationprocessbe.repository.*;
import com.example.graduationprocessbe.service.CoreWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    @org.springframework.beans.factory.annotation.Value("${app.bootstrap.password:}")
    private String bootstrapPassword;
    @org.springframework.beans.factory.annotation.Value("${app.seed-demo:false}")
    private boolean seedDemo;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final DemoDataSeeder demoDataSeeder;
    private final CoreWorkflowService workflowService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        // One file contains the baseline, permission migration, and demo seed data.
        // PostgreSQL executes the complete script in this transaction.
        String seed = new ClassPathResource("seed_rbac_and_menus.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        // Pass only a hash to SQL; its local-development fallback applies when unset.
        if (bootstrapPassword != null && !bootstrapPassword.isEmpty() && bootstrapPassword.length() < 6) {
            throw new IllegalStateException("APP_BOOTSTRAP_PASSWORD phải có ít nhất 6 ký tự");
        }
        jdbcTemplate.queryForObject("SELECT set_config('app.bootstrap.password_hash', ?, true)", String.class,
                bootstrapPassword == null || bootstrapPassword.isEmpty()
                        ? "" : passwordEncoder.encode(bootstrapPassword));
        jdbcTemplate.execute(seed);
        String baselineDefinition = jdbcTemplate.queryForObject(
                "SELECT process_definition_id FROM workflow_templates WHERE id='core-template-v2'",String.class);
        if (baselineDefinition == null) workflowService.publish("core-template-v2");

        ensureUser("admin", "admin@graduation.local", "Quản trị viên Nguyễn Văn An", role("ADMIN"), "ADMIN");
        if (seedDemo) {
            demoDataSeeder.seed();

        }
        if (userRoleRepository.countActiveGlobalAdmins()==0)
            throw new IllegalStateException("Cần ít nhất một ADMIN toàn hệ thống đang hoạt động");
        log.info("Versioned RBAC/menu initialization completed.");
    }

    private Role role(String code) {
        return roleRepository.findByRoleCode(code).orElseThrow();
    }

    private void ensureUser(String username, String email, String fullName, Role role, String userType) {
        // Never reset an existing account's password or role assignments on startup.
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }
        if (bootstrapPassword == null || bootstrapPassword.length()<6)
            throw new IllegalStateException("Cần cấu hình APP_BOOTSTRAP_PASSWORD ít nhất 6 ký tự để tạo tài khoản mới");
        String encodedPassword = passwordEncoder.encode(bootstrapPassword);
        if (userRepository.existsByEmail(email)) throw new IllegalStateException("Email bootstrap đã thuộc tài khoản khác");
        User user = new User(); user.setEmail(email); user.setFullName(fullName);
        user.setUsername(username); user.setPasswordHash(encodedPassword);
        user.setUserType(userType); user.setStatus("ACTIVE");
        User savedUser = userRepository.save(user);

        ensureUserRole(savedUser.getId(), role);
        if (Set.of("FACULTY_STAFF", "COMMITTEE").contains(role.getRoleCode())) {
            ensureUserRole(savedUser.getId(), role("LECTURER"));
        }
    }

    private void ensureUserRole(String userId, Role role) {
        if (!userRoleRepository.existsByUserIdAndRoleId(userId, role.getId())) {
            UserRole userRole = new UserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(role.getId());
            userRole.setThesisRoundId(null); // Global role
            userRoleRepository.save(userRole);
        }
    }
}

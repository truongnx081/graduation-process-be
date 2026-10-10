package com.example.graduationprocessbe.repository;

import com.example.graduationprocessbe.entity.UserPermissionDeny;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserPermissionDenyRepository extends JpaRepository<UserPermissionDeny, String> {
    List<UserPermissionDeny> findByUserId(String userId);
}

package com.example.graduationprocessbe.repository;

import com.example.graduationprocessbe.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DepartmentRepository extends JpaRepository<Department, String>, JpaSpecificationExecutor<Department> {
    boolean existsByDeptCode(String deptCode);

    java.util.Optional<Department> findByDeptCodeIgnoreCase(String deptCode);
}

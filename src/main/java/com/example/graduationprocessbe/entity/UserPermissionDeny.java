package com.example.graduationprocessbe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_permission_denies")
@Getter @Setter @NoArgsConstructor
public class UserPermissionDeny extends BaseEntity {
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "permission_id", nullable = false, length = 36)
    private String permissionId;
    @Column(name = "thesis_round_id", length = 36)
    private String thesisRoundId;
}

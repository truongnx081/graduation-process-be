package com.example.graduationprocessbe.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ThesisResponse {
    private String id;
    private String title;
    private String description;
    private UserResponse student;
    private UserResponse lecturer;
    private String phaseId;
    private String processInstanceId;
    private String currentStatus;
    private String currentStatusLabel;
    private Boolean guidanceApproved;
    private LocalDateTime guidanceRespondedAt;
    private String guidanceComment;
    private LocalDateTime createdDate;
    private LocalDateTime lastModifiedDate;
}

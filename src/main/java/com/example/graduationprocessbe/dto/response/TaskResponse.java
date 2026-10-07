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
public class TaskResponse {
    private String id;
    private String name;
    private String taskDefinitionKey;
    private String assignee;
    private String assigneeName;
    private String thesisId;
    private String thesisTitle;
    private String studentName;
    private String processInstanceId;
    private LocalDateTime createTime;
    private String kind;
    private LocalDateTime dueDate;

    public TaskResponse(String id,String name,String taskDefinitionKey,String assignee,String processInstanceId,LocalDateTime createTime) {
        this.id=id; this.name=name; this.taskDefinitionKey=taskDefinitionKey; this.assignee=assignee;
        this.processInstanceId=processInstanceId; this.createTime=createTime;
    }
}

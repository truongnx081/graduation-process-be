package com.example.graduationprocessbe.service;

import lombok.RequiredArgsConstructor;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.TaskService;
import org.flowable.engine.migration.ActivityMigrationMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/** One-time upgrade requested for the reference round; keeps active cases and their history. */
@Service
@RequiredArgsConstructor
public class FacultyWorkflowUpgrade {
    private final JdbcTemplate jdbc;
    private final CoreWorkflowService workflows;
    private final ProcessEngine engine;
    private final TaskService tasks;

    @Transactional
    public void apply() {
        jdbc.execute("SELECT pg_advisory_xact_lock(20261024, 5)");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_seed_versions WHERE version='faculty-report-workflow-v1')",Boolean.class))) return;
        String template="core-template-faculty-v4";
        String definition=jdbc.queryForObject("SELECT process_definition_id FROM workflow_templates WHERE id=?",String.class,template);
        if (definition==null) definition=workflows.publish(template);
        Map<String,String> renamed=Map.of("submitCouncil","submitFaculty","councilAccept","plagiarismCheck");
        String round="52794314-3c72-49e7-9089-8b92cc23c766";
        for (var thesis:jdbc.queryForList("SELECT id,process_instance_id,current_status FROM theses WHERE phase_id=?",round)) {
            String process=(String)thesis.get("process_instance_id");
            if (process!=null && engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(process).count()>0) {
                var migration=engine.getProcessMigrationService().createProcessInstanceMigrationBuilder().migrateToProcessDefinition(definition);
                for(var task:tasks.createTaskQuery().processInstanceId(process).list()) {
                    String next=renamed.get(task.getTaskDefinitionKey());
                    if(next!=null) migration.addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor(task.getTaskDefinitionKey(),next));
                }
                migration.migrate(process);
                for(var task:tasks.createTaskQuery().processInstanceId(process).list()) {
                    // A migrated legacy council task must be handled by faculty, not its previous claimant.
                    if("plagiarismCheck".equals(task.getTaskDefinitionKey())) {
                        task.setAssignee(null); tasks.saveTask(task);
                        for(var link:tasks.getIdentityLinksForTask(task.getId()))
                            if("COMMITTEE".equals(link.getGroupId())) tasks.deleteCandidateGroup(task.getId(),"COMMITTEE");
                        tasks.addCandidateGroup(task.getId(),"FACULTY_STAFF");
                    }
                }
            }
            String status=renamed.get(thesis.get("current_status"));
            if(status!=null) jdbc.update("UPDATE theses SET current_status=? WHERE id=?",status,thesis.get("id"));
        }
        for(var entry:renamed.entrySet())
            jdbc.update("UPDATE round_step_windows SET step_key=? WHERE round_id=? AND step_key=?",entry.getValue(),round,entry.getKey());
        jdbc.update("INSERT INTO round_step_windows(id,round_id,step_key,opens_at,closes_at,before_open_hours,before_close_hours,after_close_hours) " +
                "SELECT gen_random_uuid()::text,round_id,'reviseFinalReport',opens_at,closes_at,before_open_hours,before_close_hours,after_close_hours " +
                "FROM round_step_windows WHERE round_id=? AND step_key='plagiarismCheck' ON CONFLICT(round_id,step_key) DO NOTHING",round);
        jdbc.update("UPDATE thesis_rounds SET workflow_definition_id=? WHERE id=?",definition,round);
        jdbc.update("INSERT INTO app_seed_versions(version) VALUES('faculty-report-workflow-v1')");
    }
}

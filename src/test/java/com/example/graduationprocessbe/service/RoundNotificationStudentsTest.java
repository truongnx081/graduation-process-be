package com.example.graduationprocessbe.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoundNotificationStudentsTest {
    @Test void studentWithoutApplicationCanBeSelected() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("round"))).thenReturn(1);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("student"))).thenReturn(true);
        var service=new RoundMailManagementService(jdbc,mock(NotificationService.class),mock(WorkflowMailService.class));
        assertDoesNotThrow(() -> service.saveRecipientStudents("round",List.of("student")));
        verify(jdbc).batchUpdate(eq("INSERT INTO round_notification_students(round_id,user_id) VALUES(?,?)"),anyList());
        verify(jdbc,never()).update(contains("members"),any(Object[].class));
    }

    @Test void invalidUserCannotReplaceSavedAudience() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("round"))).thenReturn(1);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("lecturer"))).thenReturn(false);
        var service=new RoundMailManagementService(jdbc,mock(NotificationService.class),mock(WorkflowMailService.class));
        assertThrows(IllegalArgumentException.class,() -> service.saveRecipientStudents("round",List.of("lecturer")));
        verify(jdbc,never()).update(eq("DELETE FROM round_notification_students WHERE round_id=?"),eq("round"));
    }
}

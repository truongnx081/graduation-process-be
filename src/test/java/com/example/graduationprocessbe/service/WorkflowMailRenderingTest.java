package com.example.graduationprocessbe.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowMailRenderingTest {
    @Test void preservesEditorFormattingAndRemovesUnsafeHtml() {
        String html = WorkflowMailService.renderMessage("<h2>Kế hoạch</h2><p><strong>Nộp báo cáo</strong></p><table><tr><td onclick='alert(1)'>01/01</td></tr></table><script>alert(1)</script><a href='javascript:alert(1)'>Link</a>");
        assertTrue(html.contains("<h2>Kế hoạch</h2>"));
        assertTrue(html.contains("<strong>Nộp báo cáo</strong>"));
        assertTrue(html.contains("border-collapse"));
        assertFalse(html.contains("onclick"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("javascript:"));
    }

    @Test void escapesRecipientVariablesAndKeepsNotificationReadable() {
        String body = NotificationService.renderBody("<p>Chào {{tenNguoiNhan}}</p>", java.util.Map.of("tenNguoiNhan", "<b>A & B</b>"));
        assertTrue(body.contains("&lt;b&gt;A &amp; B&lt;/b&gt;"));
        assertEquals("Chào <b>A & B</b>", WorkflowMailService.plainMessage(body));
    }

    @Test void rendersPlanTableAndEscapesUserContent() {
        String html=WorkflowMailService.renderMessage("Kế hoạch\n| Mốc | Ghi chú |\n| --- | --- |\n| Đề cương | <script>alert(1)</script> |\nTrân trọng");
        assertTrue(html.contains("<table"));
        assertTrue(html.contains("<th"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("---"));
        assertTrue(html.contains("</table>Trân trọng"));
    }
}

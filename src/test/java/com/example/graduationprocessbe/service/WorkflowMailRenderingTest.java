package com.example.graduationprocessbe.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowMailRenderingTest {
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

package com.example.graduationprocessbe.controller;

import com.example.graduationprocessbe.dto.ApiResponseWrapper;
import com.example.graduationprocessbe.entity.User;
import com.example.graduationprocessbe.service.CurrentUserService;
import com.example.graduationprocessbe.service.NotificationCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.example.graduationprocessbe.util.ApiResponses.ok;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationCenterService center;
    private final CurrentUserService currentUser;

    @GetMapping
    public ResponseEntity<ApiResponseWrapper<Map<String,Object>>> inbox(@RequestParam(defaultValue="false") boolean unreadOnly,
                                                                        @RequestParam(defaultValue="0") int page,
                                                                        @RequestParam(defaultValue="20") int size) {
        return ok(center.inbox(me().getId(),unreadOnly,page,size));
    }
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponseWrapper<Integer>> unreadCount() { return ok(center.unreadCount(me().getId())); }
    @PutMapping("/{id}/read")
    public ResponseEntity<ApiResponseWrapper<Void>> read(@PathVariable String id) { center.markRead(me().getId(),id); return ok(null); }
    @PutMapping("/read-all")
    public ResponseEntity<ApiResponseWrapper<Void>> readAll() { center.markAllRead(me().getId()); return ok(null); }

    @GetMapping("/admin/templates") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> templates() { return ok(center.templates()); }
    @GetMapping("/admin/variables") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<List<String>>> variables() { return ok(center.variables()); }
    @PutMapping("/admin/templates/{code}") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<Void>> saveTemplate(@PathVariable String code,@RequestBody NotificationCenterService.TemplateInput input) {
        center.saveTemplate(code,input,me().getId()); return ok(null);
    }
    @PostMapping("/admin/templates/{code}/reset") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<Void>> resetTemplate(@PathVariable String code) {
        center.resetTemplate(code,me().getId()); return ok(null);
    }
    @PostMapping("/admin/templates/{code}/test") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<Void>> testTemplate(@PathVariable String code) {
        center.sendTest(code,me().getEmail()); return ok(null);
    }
    @PostMapping("/admin/preview") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<Map<String,String>>> preview(@RequestBody NotificationCenterService.PreviewInput input) {
        return ok(center.preview(input));
    }
    @GetMapping("/admin/deliveries") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String,Object>>>> deliveries(@RequestParam(required=false) String status,
                                                                                   @RequestParam(required=false) String q,
                                                                                   @RequestParam(required=false) String roundId) {
        return ok(center.deliveries(status,q,roundId));
    }
    @GetMapping("/admin/deliveries/stats") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<Map<String,Object>>> deliveryStats() { return ok(center.deliveryStats()); }
    @PostMapping("/admin/deliveries/{id}/retry") @PreAuthorize("hasAuthority('VIEW_CAU_HINH_THONG_BAO')")
    public ResponseEntity<ApiResponseWrapper<Void>> retry(@PathVariable String id) { center.retry(id); return ok(null); }

    private User me() {
        return currentUser.getCurrentUser().orElseThrow(() -> new AccessDeniedException("Chưa đăng nhập"));
    }
}

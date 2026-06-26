package com.tongji.notification.api;

import com.tongji.auth.token.JwtService;
import com.tongji.notification.api.dto.NotificationPageResponse;
import com.tongji.notification.api.dto.NotificationUnreadCountResponse;
import com.tongji.notification.service.NotificationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final JwtService jwtService;

    public NotificationController(NotificationService notificationService, JwtService jwtService) {
        this.notificationService = notificationService;
        this.jwtService = jwtService;
    }

    @GetMapping
    public NotificationPageResponse list(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(value = "cursorCreatedAt", required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorCreatedAt,
                                         @RequestParam(value = "cursorId", required = false) Long cursorId,
                                         @RequestParam(value = "limit", defaultValue = "20") int limit) {
        long userId = jwtService.extractUserId(jwt);
        return notificationService.page(userId, cursorCreatedAt, cursorId, limit);
    }

    @GetMapping("/unread-count")
    public NotificationUnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return notificationService.unreadCount(userId);
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable("notificationId") long notificationId) {
        long userId = jwtService.extractUserId(jwt);
        notificationService.markRead(userId, notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        notificationService.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }
}

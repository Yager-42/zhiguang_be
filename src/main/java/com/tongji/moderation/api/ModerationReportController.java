package com.tongji.moderation.api;

import com.tongji.auth.token.JwtService;
import com.tongji.moderation.api.dto.ModerationReportRequest;
import com.tongji.moderation.api.dto.ModerationReportResponse;
import com.tongji.moderation.service.ModerationReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/v1/moderation/reports")
public class ModerationReportController {
    private final ModerationReportService reportService;
    private final JwtService jwtService;

    public ModerationReportController(ModerationReportService reportService, JwtService jwtService) {
        this.reportService = reportService;
        this.jwtService = jwtService;
    }

    @PostMapping
    public ResponseEntity<ModerationReportResponse> report(@Valid @RequestBody ModerationReportRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(reportService.submitReport(userId, request));
    }
}

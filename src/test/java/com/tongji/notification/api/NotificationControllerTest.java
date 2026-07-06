package com.tongji.notification.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.notification.api.dto.NotificationItemResponse;
import com.tongji.notification.api.dto.NotificationPageResponse;
import com.tongji.notification.api.dto.NotificationUnreadCountResponse;
import com.tongji.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {

    private static final long USER_ID = 1001L;

    private NotificationService notificationService;
    private JwtService jwtService;
    private MockMvc mockMvc;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        notificationService = Mockito.mock(NotificationService.class);
        jwtService = Mockito.mock(JwtService.class);
        NotificationController controller = new NotificationController(notificationService, jwtService);
        jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("uid", USER_ID)
        );
        when(jwtService.extractUserId(jwt)).thenReturn(USER_ID);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter,
                                                  ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest,
                                                  WebDataBinderFactory binderFactory) {
                        return jwt;
                    }
                })
                .build();
    }

    @Test
    void listReturnsPage() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 26, 12, 0);
        when(notificationService.page(USER_ID, createdAt, 11L, 20))
                .thenReturn(new NotificationPageResponse(
                        List.of(new NotificationItemResponse("11", "like", false, createdAt, "8",
                                "knowpost", "100", null, null, 3, createdAt.minusMinutes(5), createdAt)),
                        createdAt,
                        "11",
                        true
                ));

        mockMvc.perform(get("/api/v1/notifications")
                        .queryParam("cursorCreatedAt", createdAt.toString())
                        .queryParam("cursorId", "11")
                        .queryParam("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("11"))
                .andExpect(jsonPath("$.items[0].type").value("like"))
                .andExpect(jsonPath("$.items[0].aggregateCount").value(3))
                .andExpect(jsonPath("$.hasMore").value(true));

        verify(notificationService).page(USER_ID, createdAt, 11L, 20);
    }

    @Test
    void unreadCountReturnsValue() throws Exception {
        when(notificationService.unreadCount(USER_ID)).thenReturn(new NotificationUnreadCountResponse(7));

        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(7));

        verify(notificationService).unreadCount(USER_ID);
    }

    @Test
    void markReadDelegates() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/12/read"))
                .andExpect(status().isNoContent());

        verify(notificationService).markRead(USER_ID, 12L);
    }

    @Test
    void markAllReadDelegates() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/read-all"))
                .andExpect(status().isNoContent());

        verify(notificationService).markAllRead(USER_ID);
    }
}

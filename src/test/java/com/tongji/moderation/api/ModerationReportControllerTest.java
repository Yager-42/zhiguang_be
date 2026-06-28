package com.tongji.moderation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.auth.token.JwtService;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.moderation.api.dto.ModerationReportRequest;
import com.tongji.moderation.api.dto.ModerationReportResponse;
import com.tongji.moderation.service.ModerationReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModerationReportControllerTest {

    private ModerationReportService reportService;
    private JwtService jwtService;
    private MockMvc mockMvc;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        reportService = Mockito.mock(ModerationReportService.class);
        jwtService = Mockito.mock(JwtService.class);
        ModerationReportController controller = new ModerationReportController(reportService, jwtService);
        jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("uid", 7L)
        );
        when(jwtService.extractUserId(jwt)).thenReturn(7L);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
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
    void reportEndpointReturnsAcceptedPendingStatus() throws Exception {
        when(reportService.submitReport(Mockito.eq(7L), any(ModerationReportRequest.class)))
                .thenReturn(new ModerationReportResponse(21L, "pending"));

        mockMvc.perform(post("/api/v1/moderation/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"post","targetId":101,"reason":"spam","description":"bad links"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reportId").value(21L))
                .andExpect(jsonPath("$.status").value("pending"));

        verify(jwtService).extractUserId(jwt);
        verify(reportService).submitReport(Mockito.eq(7L), any(ModerationReportRequest.class));
    }

    @Test
    void blankReasonIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/moderation/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"post","targetId":101,"reason":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}

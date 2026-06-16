package com.tongji.knowpost.api;

import com.tongji.auth.config.AuthConfiguration;
import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.manager.PublishManager;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.service.KnowPostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowPostControllerPublishTest {

    private static final long USER_ID = 1001L;
    private static final long POST_ID = 2002L;
    private static final long ATTEMPT_ID = 3003L;

    private PublishManager publishManager;
    private JwtService jwtService;
    private MockMvc mockMvc;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        KnowPostService knowPostService = Mockito.mock(KnowPostService.class);
        KnowPostFeedService knowPostFeedService = Mockito.mock(KnowPostFeedService.class);
        publishManager = Mockito.mock(PublishManager.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.getJwt().setIssuer("test-issuer");
        authProperties.getJwt().setPrivateKey(new ClassPathResource("keys/private.pem"));
        authProperties.getJwt().setPublicKey(new ClassPathResource("keys/public.pem"));
        AuthConfiguration authConfiguration = new AuthConfiguration(authProperties);
        jwtService = new JwtService(authConfiguration.jwtEncoder(), authConfiguration.jwtDecoder(), authProperties);

        KnowPostController controller = new KnowPostController(knowPostService, knowPostFeedService, jwtService, publishManager);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        HandlerMethodArgumentResolver jwtArgumentResolver = new HandlerMethodArgumentResolver() {
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
        };

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(jwtArgumentResolver)
                .setValidator(validator)
                .build();

        jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("sub", String.valueOf(USER_ID), "uid", USER_ID)
        );
    }

    @Test
    void publishReturnsAcceptedWithAttemptId() throws Exception {
        when(publishManager.acceptPublish(USER_ID, POST_ID, "idem-1"))
                .thenReturn(new PublishAcceptedResponse(String.valueOf(ATTEMPT_ID)));

        mockMvc.perform(post("/api/v1/knowposts/{id}/publish", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotentKey":"idem-1"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.publishAttemptId").value(String.valueOf(ATTEMPT_ID)));

        verify(publishManager).acceptPublish(USER_ID, POST_ID, "idem-1");
    }

    @Test
    void publishRejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/knowposts/{id}/publish", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("幂等键不能为空"));

        verify(publishManager, never()).acceptPublish(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void publishRejectsWhitespaceOnlyIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/knowposts/{id}/publish", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotentKey":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("幂等键不能为空"));

        verify(publishManager, never()).acceptPublish(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void statusReturnsManagerResult() throws Exception {
        when(publishManager.getPublishStatus(USER_ID, POST_ID, ATTEMPT_ID))
                .thenReturn(new PublishStatusResponse(String.valueOf(ATTEMPT_ID), "failed", "publish_failed", "OUTBOX", true));

        mockMvc.perform(get("/api/v1/knowposts/{id}/publish/status", POST_ID)
                        .queryParam("attemptId", String.valueOf(ATTEMPT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishAttemptId").value(String.valueOf(ATTEMPT_ID)))
                .andExpect(jsonPath("$.attemptStatus").value("failed"))
                .andExpect(jsonPath("$.postStatus").value("publish_failed"))
                .andExpect(jsonPath("$.failedStep").value("OUTBOX"))
                .andExpect(jsonPath("$.retryable").value(true));

        verify(publishManager).getPublishStatus(USER_ID, POST_ID, ATTEMPT_ID);
    }

    @Test
    void statusPropagatesBusinessExceptionThroughGlobalExceptionHandler() throws Exception {
        when(publishManager.getPublishStatus(USER_ID, POST_ID, ATTEMPT_ID))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "发布尝试不存在"));

        mockMvc.perform(get("/api/v1/knowposts/{id}/publish/status", POST_ID)
                        .queryParam("attemptId", String.valueOf(ATTEMPT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("发布尝试不存在"));

        verify(publishManager).getPublishStatus(USER_ID, POST_ID, ATTEMPT_ID);
    }

    @Test
    void retryReturnsAcceptedWithSameAttemptId() throws Exception {
        when(publishManager.retryPublish(USER_ID, POST_ID, ATTEMPT_ID))
                .thenReturn(new PublishAcceptedResponse(String.valueOf(ATTEMPT_ID)));

        mockMvc.perform(post("/api/v1/knowposts/{id}/publish/{attemptId}/retry", POST_ID, ATTEMPT_ID)
                        )
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.publishAttemptId").value(String.valueOf(ATTEMPT_ID)));

        verify(publishManager).retryPublish(USER_ID, POST_ID, ATTEMPT_ID);
    }
}

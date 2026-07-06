package com.tongji.relation.api;

import com.tongji.auth.config.AuthConfiguration;
import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.token.JwtService;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.profile.api.dto.ProfileResponse;
import com.tongji.relation.manager.RelationManager;
import com.tongji.relation.manager.RelationWriteResult;
import com.tongji.relation.service.RelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RelationManagerControllerTest {

    private static final long USER_ID = 501L;
    private static final long OTHER_USER_ID = 777L;

    private RelationManager relationManager;
    private RelationService relationService;
    private MockMvc mockMvc;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        relationManager = Mockito.mock(RelationManager.class);
        relationService = Mockito.mock(RelationService.class);
        JwtService jwtService = buildJwtService();

        RelationController controller = new RelationController(
                relationManager,
                relationService,
                jwtService,
                new org.springframework.data.redis.core.StringRedisTemplate(),
                Mockito.mock(com.tongji.counter.service.UserCounterRebuildAdapter.class),
                Mockito.mock(com.tongji.relation.mapper.RelationMapper.class),
                Mockito.mock(com.tongji.common.singleflight.DistributedSingleFlightService.class)
        );

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
    void followDelegatesToManager() throws Exception {
        when(relationManager.follow(USER_ID, OTHER_USER_ID))
                .thenReturn(RelationWriteResult.changed(true));

        mockMvc.perform(post("/api/v1/relation/follow")
                        .queryParam("toUserId", String.valueOf(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        verify(relationManager).follow(USER_ID, OTHER_USER_ID);
        verifyNoInteractions(relationService);
    }

    @Test
    void unfollowDelegatesToManager() throws Exception {
        when(relationManager.unfollow(USER_ID, OTHER_USER_ID))
                .thenReturn(RelationWriteResult.changed(false));

        mockMvc.perform(post("/api/v1/relation/unfollow")
                        .queryParam("toUserId", String.valueOf(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        verify(relationManager).unfollow(USER_ID, OTHER_USER_ID);
        verifyNoInteractions(relationService);
    }

    @Test
    void statusDelegatesToManager() throws Exception {
        when(relationManager.status(USER_ID, OTHER_USER_ID))
                .thenReturn(Map.of("following", true, "followedBy", false, "mutual", false));

        mockMvc.perform(get("/api/v1/relation/status")
                        .queryParam("toUserId", String.valueOf(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(true))
                .andExpect(jsonPath("$.followedBy").value(false))
                .andExpect(jsonPath("$.mutual").value(false));

        verify(relationManager).status(USER_ID, OTHER_USER_ID);
        verifyNoInteractions(relationService);
    }

    @Test
    void readEndpointsStayOnRelationService() throws Exception {
        when(relationService.followingProfiles(OTHER_USER_ID, 20, 0, null))
                .thenReturn(List.of(new ProfileResponse(OTHER_USER_ID, "nick", "avatar", "bio", "zg", "male", null, "school", "phone", "email", "[]")));

        mockMvc.perform(get("/api/v1/relation/following")
                        .queryParam("userId", String.valueOf(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(OTHER_USER_ID));

        verify(relationService).followingProfiles(OTHER_USER_ID, 20, 0, null);
    }

    private JwtService buildJwtService() {
        AuthProperties authProperties = new AuthProperties();
        authProperties.getJwt().setIssuer("test-issuer");
        authProperties.getJwt().setPrivateKey(new ClassPathResource("keys/private.pem"));
        authProperties.getJwt().setPublicKey(new ClassPathResource("keys/public.pem"));
        AuthConfiguration authConfiguration = new AuthConfiguration(authProperties);
        return new JwtService(authConfiguration.jwtEncoder(), authConfiguration.jwtDecoder(), authProperties);
    }
}

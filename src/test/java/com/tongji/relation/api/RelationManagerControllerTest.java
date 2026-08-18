package com.tongji.relation.api;

import com.tongji.auth.config.AuthConfiguration;
import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.token.JwtService;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.counter.service.UserCounterReader;
import com.tongji.counter.service.UserCounters;
import com.tongji.profile.api.dto.ProfileResponse;
import com.tongji.relation.command.RelationCommandService;
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

    private RelationCommandService relationCommandService;
    private RelationManager relationManager;
    private RelationService relationService;
    private UserCounterReader userCounterReader;
    private MockMvc mockMvc;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        relationCommandService = Mockito.mock(RelationCommandService.class);
        relationManager = Mockito.mock(RelationManager.class);
        relationService = Mockito.mock(RelationService.class);
        userCounterReader = Mockito.mock(UserCounterReader.class);
        JwtService jwtService = buildJwtService();

        RelationController controller = new RelationController(
                relationCommandService,
                relationManager,
                relationService,
                jwtService,
                userCounterReader
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
    void followDelegatesToCommandService() throws Exception {
        when(relationCommandService.follow(USER_ID, OTHER_USER_ID))
                .thenReturn(RelationWriteResult.changed(true));

        mockMvc.perform(post("/api/v1/relation/follow")
                        .queryParam("toUserId", String.valueOf(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        verify(relationCommandService).follow(USER_ID, OTHER_USER_ID);
        verifyNoInteractions(relationService);
    }

    @Test
    void unfollowDelegatesToCommandService() throws Exception {
        when(relationCommandService.unfollow(USER_ID, OTHER_USER_ID))
                .thenReturn(RelationWriteResult.changed(true));

        mockMvc.perform(post("/api/v1/relation/unfollow")
                        .queryParam("toUserId", String.valueOf(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));

        verify(relationCommandService).unfollow(USER_ID, OTHER_USER_ID);
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

    @Test
    void counterEndpointPreservesFiveFieldJsonContract() throws Exception {
        when(userCounterReader.getVerified(OTHER_USER_ID))
                .thenReturn(new UserCounters(1L, 2L, 3L, 4L, 5L));

        mockMvc.perform(get("/api/v1/relation/counter")
                        .queryParam("userId", String.valueOf(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followings").value(1L))
                .andExpect(jsonPath("$.followers").value(2L))
                .andExpect(jsonPath("$.posts").value(3L))
                .andExpect(jsonPath("$.likedPosts").value(4L))
                .andExpect(jsonPath("$.favedPosts").value(5L))
                .andExpect(jsonPath("$.length()").value(5));

        verify(userCounterReader).getVerified(OTHER_USER_ID);
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
package com.tongji.knowpost.api;

import com.tongji.auth.config.AuthConfiguration;
import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.manager.PublishManager;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.service.KnowPostService;
import com.tongji.recommendation.HomeFeedMixingService;
import com.tongji.recommendation.RecommendationEngine;
import com.tongji.recommendation.feed.FollowFeedService;
import com.tongji.recommendation.feed.TimelinePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
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
    private KnowPostService knowPostService;
    private KnowPostFeedService knowPostFeedService;
    private HomeFeedMixingService homeFeedMixingService;
    private FollowFeedService followFeedService;
    private RecommendationEngine recommendationEngine;
    private KnowPostMapper knowPostMapper;
    private JwtService jwtService;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        knowPostService = Mockito.mock(KnowPostService.class);
        knowPostFeedService = Mockito.mock(KnowPostFeedService.class);
        publishManager = Mockito.mock(PublishManager.class);
        followFeedService = Mockito.mock(FollowFeedService.class);
        recommendationEngine = Mockito.mock(RecommendationEngine.class);
        knowPostMapper = Mockito.mock(KnowPostMapper.class);
        homeFeedMixingService = new HomeFeedMixingService(
                followFeedService,
                recommendationEngine,
                knowPostMapper,
                knowPostFeedService,
                Mockito.mock(com.tongji.promotion.service.PromotionAllocationService.class)
        );
        AuthProperties authProperties = new AuthProperties();
        authProperties.getJwt().setIssuer("test-issuer");
        authProperties.getJwt().setPrivateKey(new ClassPathResource("keys/private.pem"));
        authProperties.getJwt().setPublicKey(new ClassPathResource("keys/public.pem"));
        AuthConfiguration authConfiguration = new AuthConfiguration(authProperties);
        jwtService = new JwtService(authConfiguration.jwtEncoder(), authConfiguration.jwtDecoder(), authProperties);

        jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("sub", String.valueOf(USER_ID), "uid", USER_ID)
        );
    }

    @Test
    void feedUsesMixedHomeFeedForAuthenticatedUserWhenFlagEnabled() throws Exception {
        MockMvc mockMvc = createMockMvc(true);
        when(followFeedService.getTimeline(USER_ID, null, 20))
                .thenReturn(new TimelinePage(java.util.List.of(), null));
        when(recommendationEngine.recommend(USER_ID, 40)).thenReturn(java.util.List.of());
        when(knowPostMapper.listFeedPublicIds(20, 0)).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/v1/knowposts/feed")
                        .queryParam("page", "99")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(followFeedService).getTimeline(USER_ID, null, 20);
        verify(knowPostFeedService, never()).getPublicFeed(Mockito.anyInt(), Mockito.anyInt(), Mockito.any());
    }

    @Test
    void feedUsesMixedHomeFeedFixedSizeRefillAcrossSourcesWhenFlagEnabled() throws Exception {
        MockMvc mockMvc = createMockMvc(true);
        List<Long> followIds = List.of(1L, 2L, 3L);
        List<Long> hotIds = java.util.stream.LongStream.rangeClosed(6, 21).boxed().toList();
        when(followFeedService.getTimeline(USER_ID, null, 20))
                .thenReturn(new TimelinePage(timelineItems(followIds, "2026-06-18T12:00:00Z"), null));
        when(knowPostFeedService.getFeedByIds(followIds, USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem("1"), feedItem("2")));
        when(recommendationEngine.recommend(USER_ID, 40))
                .thenReturn(List.of(
                        candidate(3L),
                        candidate(4L),
                        candidate(5L)
                ));
        when(knowPostFeedService.getFeedByIds(List.of(4L, 5L), USER_ID, KnowPostFeedService.FeedVisibilityScope.PUBLIC))
                .thenReturn(List.of(feedItem("4"), feedItem("5")));
        when(knowPostMapper.listFeedPublicIds(20, 0)).thenReturn(hotIds);
        when(knowPostFeedService.getFeedByIds(hotIds, USER_ID, KnowPostFeedService.FeedVisibilityScope.PUBLIC))
                .thenReturn(feedItems(hotIds));

        mockMvc.perform(get("/api/v1/knowposts/feed")
                        .queryParam("page", "99")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].id").value("1"))
                .andExpect(jsonPath("$.items[1].id").value("2"))
                .andExpect(jsonPath("$.items[2].id").value("4"))
                .andExpect(jsonPath("$.items[3].id").value("5"))
                .andExpect(jsonPath("$.items[19].id").value("21"))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(followFeedService).getTimeline(USER_ID, null, 20);
        verify(knowPostFeedService).getFeedByIds(followIds, USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
        verify(recommendationEngine).recommend(USER_ID, 40);
        verify(knowPostFeedService).getFeedByIds(List.of(4L, 5L), USER_ID, KnowPostFeedService.FeedVisibilityScope.PUBLIC);
        verify(knowPostMapper).listFeedPublicIds(20, 0);
        verify(knowPostFeedService).getFeedByIds(hotIds, USER_ID, KnowPostFeedService.FeedVisibilityScope.PUBLIC);
        verify(knowPostFeedService, never()).getPublicFeed(Mockito.anyInt(), Mockito.anyInt(), Mockito.any());
    }

    @Test
    void feedUsesPublicFallbackWhenFlagDisabled() throws Exception {
        MockMvc mockMvc = createMockMvc(false);
        FeedPageResponse publicFeed = new FeedPageResponse(java.util.List.of(), 3, 7, true);
        when(knowPostFeedService.getPublicFeed(3, 7, USER_ID)).thenReturn(publicFeed);

        mockMvc.perform(get("/api/v1/knowposts/feed")
                        .queryParam("page", "3")
                        .queryParam("size", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.size").value(7))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(knowPostFeedService).getPublicFeed(3, 7, USER_ID);
    }

    @Test
    void feedUsesPublicFallbackForAnonymousUserRegardlessOfFlag() throws Exception {
        jwt = null;
        MockMvc mockMvc = createMockMvc(true);
        FeedPageResponse publicFeed = new FeedPageResponse(java.util.List.of(), 4, 9, false);
        when(knowPostFeedService.getPublicFeed(4, 9, null)).thenReturn(publicFeed);

        mockMvc.perform(get("/api/v1/knowposts/feed")
                        .queryParam("page", "4")
                        .queryParam("size", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(4))
                .andExpect(jsonPath("$.size").value(9))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(knowPostFeedService).getPublicFeed(4, 9, null);
    }

    @Test
    void followFeedReturnsNextCursorFromService() throws Exception {
        MockMvc mockMvc = createMockMvc(true);
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList();
        when(followFeedService.getTimeline(USER_ID, "1718697600000:42", 20))
                .thenReturn(new TimelinePage(timelineItems(ids, "2026-06-18T12:00:00Z"), "1718697500000:41"));
        when(knowPostFeedService.getFeedByIds(ids, USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(feedItems(ids));

        mockMvc.perform(get("/api/v1/knowposts/feed/follow")
                        .queryParam("cursor", "1718697600000:42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].id").value("1"))
                .andExpect(jsonPath("$.nextCursor").value(String.valueOf(Instant.parse("2026-06-18T11:41:00Z").toEpochMilli()) + ":20"));

        verify(followFeedService).getTimeline(USER_ID, "1718697600000:42", 20);
        verify(knowPostFeedService).getFeedByIds(ids, USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
    }

    @Test
    void followFeedUsesReturnedCursorForNextPageRequest() throws Exception {
        MockMvc mockMvc = createMockMvc(true);
        List<Long> firstPageIds = java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList();
        String returnedCursor = String.valueOf(Instant.parse("2026-06-18T11:41:00Z").toEpochMilli()) + ":20";
        when(followFeedService.getTimeline(USER_ID, null, 20))
                .thenReturn(new TimelinePage(timelineItems(firstPageIds, "2026-06-18T12:00:00Z"), "1718697600000:42"));
        when(followFeedService.getTimeline(USER_ID, returnedCursor, 20))
                .thenReturn(new TimelinePage(List.of(
                        timelineItem(41L, "2026-06-18T11:59:00Z")
                ), null));
        when(knowPostFeedService.getFeedByIds(firstPageIds, USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(feedItems(firstPageIds));
        when(knowPostFeedService.getFeedByIds(List.of(41L), USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem("41")));

        MvcResult firstPage = mockMvc.perform(get("/api/v1/knowposts/feed/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].id").value("1"))
                .andExpect(jsonPath("$.nextCursor").value(returnedCursor))
                .andReturn();

        String nextCursor = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(firstPage.getResponse().getContentAsString())
                .get("nextCursor")
                .asText();

        mockMvc.perform(get("/api/v1/knowposts/feed/follow")
                        .queryParam("cursor", nextCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("41"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(followFeedService).getTimeline(USER_ID, null, 20);
        verify(followFeedService).getTimeline(USER_ID, returnedCursor, 20);
        verify(knowPostFeedService).getFeedByIds(firstPageIds, USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
        verify(knowPostFeedService).getFeedByIds(List.of(41L), USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
        verify(knowPostFeedService, never()).getPublicFeed(Mockito.anyInt(), Mockito.anyInt(), Mockito.any());
    }

    @Test
    void followFeedRefillsAcrossFilteredOutTimelinePages() throws Exception {
        MockMvc mockMvc = createMockMvc(true);
        TimelinePage firstTimelinePage = new TimelinePage(List.of(
                timelineItem(101L, "2026-06-18T12:00:00Z"),
                timelineItem(102L, "2026-06-18T11:59:00Z")
        ), "1718711940000:102");
        TimelinePage secondTimelinePage = new TimelinePage(List.of(
                timelineItem(103L, "2026-06-18T11:58:00Z"),
                timelineItem(104L, "2026-06-18T11:57:00Z")
        ), null);
        List<com.tongji.knowpost.api.dto.FeedItemResponse> hydratedSecondPage = List.of(
                feedItem("103"),
                feedItem("104")
        );
        when(followFeedService.getTimeline(USER_ID, null, 20)).thenReturn(firstTimelinePage);
        when(followFeedService.getTimeline(USER_ID, "1718711940000:102", 20)).thenReturn(secondTimelinePage);
        when(knowPostFeedService.getFeedByIds(List.of(101L, 102L), USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of());
        when(knowPostFeedService.getFeedByIds(List.of(103L, 104L), USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(hydratedSecondPage);

        mockMvc.perform(get("/api/v1/knowposts/feed/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("103"))
                .andExpect(jsonPath("$.items[1].id").value("104"))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(followFeedService).getTimeline(USER_ID, null, 20);
        verify(followFeedService).getTimeline(USER_ID, "1718711940000:102", 20);
        verify(knowPostFeedService).getFeedByIds(List.of(101L, 102L), USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
        verify(knowPostFeedService).getFeedByIds(List.of(103L, 104L), USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
    }

    @Test
    void followFeedKeepsCursorAtLastIncludedRawItemWhenPageFillsMidTimelinePage() throws Exception {
        MockMvc mockMvc = createMockMvc(true);
        List<Long> firstPageIds = java.util.stream.LongStream.rangeClosed(1, 19).boxed().toList();
        TimelinePage firstTimelinePage = new TimelinePage(
                timelineItems(firstPageIds, "2026-06-18T12:00:00Z"),
                "1718711940000:200"
        );
        TimelinePage secondTimelinePage = new TimelinePage(List.of(
                timelineItem(200L, "2026-06-18T11:59:00Z"),
                timelineItem(199L, "2026-06-18T11:58:00Z")
        ), null);
        when(followFeedService.getTimeline(USER_ID, null, 20)).thenReturn(firstTimelinePage);
        when(followFeedService.getTimeline(USER_ID, "1718711940000:200", 20)).thenReturn(secondTimelinePage);
        when(knowPostFeedService.getFeedByIds(firstPageIds, USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(feedItems(firstPageIds));
        when(knowPostFeedService.getFeedByIds(List.of(200L, 199L), USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
                .thenReturn(List.of(feedItem("200"), feedItem("199")));

        mockMvc.perform(get("/api/v1/knowposts/feed/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[19].id").value("200"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value(String.valueOf(Instant.parse("2026-06-18T11:59:00Z").toEpochMilli()) + ":200"));

        verify(followFeedService).getTimeline(USER_ID, null, 20);
        verify(followFeedService).getTimeline(USER_ID, "1718711940000:200", 20);
        verify(knowPostFeedService).getFeedByIds(firstPageIds, USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
        verify(knowPostFeedService).getFeedByIds(List.of(200L, 199L), USER_ID, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
    }

    @Test
    void followFeedRejectsInvalidCursorAsBadRequest() throws Exception {
        MockMvc mockMvc = createMockMvc(true);
        mockMvc.perform(get("/api/v1/knowposts/feed/follow")
                        .queryParam("cursor", "bad-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verify(followFeedService, never()).getTimeline(Mockito.anyLong(), Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    void followFeedRejectsBlankCursorAsBadRequest() throws Exception {
        MockMvc mockMvc = createMockMvc(true);
        mockMvc.perform(get("/api/v1/knowposts/feed/follow")
                        .queryParam("cursor", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verify(followFeedService, never()).getTimeline(Mockito.anyLong(), Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    void publishReturnsAcceptedWithAttemptId() throws Exception {
        MockMvc mockMvc = createMockMvc(false);
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
        MockMvc mockMvc = createMockMvc(false);
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
        MockMvc mockMvc = createMockMvc(false);
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
        MockMvc mockMvc = createMockMvc(false);
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
        MockMvc mockMvc = createMockMvc(false);
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
        MockMvc mockMvc = createMockMvc(false);
        when(publishManager.retryPublish(USER_ID, POST_ID, ATTEMPT_ID))
                .thenReturn(new PublishAcceptedResponse(String.valueOf(ATTEMPT_ID)));

        mockMvc.perform(post("/api/v1/knowposts/{id}/publish/{attemptId}/retry", POST_ID, ATTEMPT_ID)
                        )
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.publishAttemptId").value(String.valueOf(ATTEMPT_ID)));

        verify(publishManager).retryPublish(USER_ID, POST_ID, ATTEMPT_ID);
    }

    private MockMvc createMockMvc(boolean mixedFeedEnabled) {
        KnowPostController controller = new KnowPostController(
                knowPostService,
                knowPostFeedService,
                jwtService,
                publishManager,
                homeFeedMixingService,
                followFeedService,
                mixedFeedEnabled
        );

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

        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(jwtArgumentResolver)
                .setValidator(validator)
                .build();
    }

    private com.tongji.knowpost.api.dto.FeedItemResponse feedItem(String id) {
        return com.tongji.knowpost.api.dto.FeedItemResponse.organic(
                id, null, null, null, List.of(), null, null, null, null, null, null, null, null
        );
    }

    private com.tongji.recommendation.RecommendationCandidate candidate(long contentId) {
        return new com.tongji.recommendation.RecommendationCandidate(contentId, "test", 100.0);
    }

    private List<com.tongji.knowpost.api.dto.FeedItemResponse> feedItems(List<Long> ids) {
        return ids.stream().map(id -> feedItem(String.valueOf(id))).toList();
    }

    private com.tongji.recommendation.feed.TimelineItem timelineItem(long contentId, String publishTs) {
        return new com.tongji.recommendation.feed.TimelineItem(contentId, 2000L + contentId, Instant.parse(publishTs));
    }

    private List<com.tongji.recommendation.feed.TimelineItem> timelineItems(List<Long> ids, String newestPublishTs) {
        Instant newest = Instant.parse(newestPublishTs);
        return java.util.stream.IntStream.range(0, ids.size())
                .mapToObj(index -> new com.tongji.recommendation.feed.TimelineItem(
                        ids.get(index),
                        2000L + ids.get(index),
                        newest.minusSeconds(index * 60L)
                ))
                .toList();
    }
}

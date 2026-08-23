package com.tongji.comment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.auth.token.JwtService;
import com.tongji.comment.api.dto.CommentItemResponse;
import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentStatusResponse;
import com.tongji.comment.api.dto.CommentSubmitRequest;
import com.tongji.comment.api.dto.CommentSubmitResponse;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.event.CommentFeedbackProducer;
import com.tongji.comment.service.CommentService;
import com.tongji.comment.service.CommentSortOrder;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.counter.service.CounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommentControllerTest {

    private static final long USER_ID = 1001L;
    private static final long POST_ID = 2002L;
    private static final long COMMENT_ID = 3003L;
    private static final long REPLY_ID = 4004L;

    private CommentService commentService;
    private CounterService counterService;
    private JwtService jwtService;
    private CommentFeedbackProducer feedbackProducer;
    private MockMvc mockMvc;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        commentService = Mockito.mock(CommentService.class);
        counterService = Mockito.mock(CounterService.class);
        jwtService = Mockito.mock(JwtService.class);
        feedbackProducer = Mockito.mock(CommentFeedbackProducer.class);

        CommentController controller = new CommentController(commentService, counterService, jwtService, feedbackProducer);

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
                Map.of("uid", USER_ID)
        );
        when(jwtService.extractUserId(jwt)).thenReturn(USER_ID);
    }

    @Test
    void submitReturnsAcceptedWithClientRequestIdAndPendingCommentId() throws Exception {
        when(commentService.submit(eq(USER_ID), eq(POST_ID), any(CommentSubmitRequest.class)))
                .thenReturn(new CommentSubmitResponse("client-1", String.valueOf(COMMENT_ID), "pending"));

        mockMvc.perform(post("/api/v1/posts/{postId}/comments", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"client-1","body":"hello"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.clientRequestId").value("client-1"))
                .andExpect(jsonPath("$.pendingCommentId").value(COMMENT_ID))
                .andExpect(jsonPath("$.status").value("pending"));

        verify(jwtService).extractUserId(jwt);
        verify(commentService).submit(eq(USER_ID), eq(POST_ID), any(CommentSubmitRequest.class));
    }

    @Test
    void submitWithMalformedRelationIdsReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId":"client-1","body":"hello","rootId":"temporary-uuid","parentId":"temporary-uuid"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求参数格式错误"));

        verify(commentService, never()).submit(eq(USER_ID), eq(POST_ID), any(CommentSubmitRequest.class));
    }

    @Test
    void likeWithMalformedCommentIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/comments/{commentId}/like", "temporary-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求参数格式错误"));

        verify(counterService, never()).like(eq("comment"), eq("temporary-uuid"), eq(USER_ID));
    }

    @Test
    void statusReturnsServiceResult() throws Exception {
        when(commentService.status(COMMENT_ID))
                .thenReturn(new CommentStatusResponse(String.valueOf(COMMENT_ID), "client-1", "succeeded"));

        mockMvc.perform(get("/api/v1/comments/{pendingCommentId}/status", COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCommentId").value(COMMENT_ID))
                .andExpect(jsonPath("$.clientRequestId").value("client-1"))
                .andExpect(jsonPath("$.status").value("succeeded"));

        verify(commentService).status(COMMENT_ID);
    }

    @Test
    void topLevelPageReturnsServiceResult() throws Exception {
        LocalDateTime createTime = LocalDateTime.of(2026, 6, 17, 9, 30);
        CommentPageResponse response = new CommentPageResponse(
                List.of(new CommentItemResponse(String.valueOf(COMMENT_ID), String.valueOf(POST_ID), null, null,
                        String.valueOf(USER_ID), "评论作者", "/avatar/comment-author.png", "hello", 0, false,
                        2, 1, createTime, createTime, false)),
                createTime,
                String.valueOf(COMMENT_ID),
                true
        );
        when(commentService.pageComments(
                POST_ID, createTime, COMMENT_ID, 20, CommentSortOrder.EARLIEST, USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/v1/posts/{postId}/comments", POST_ID)
                        .queryParam("cursorCreateTime", createTime.toString())
                        .queryParam("cursorCommentId", String.valueOf(COMMENT_ID))
                        .queryParam("limit", "20")
                        .queryParam("sort", "earliest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].commentId").value(COMMENT_ID))
                .andExpect(jsonPath("$.items[0].creatorNickname").value("评论作者"))
                .andExpect(jsonPath("$.items[0].creatorAvatar").value("/avatar/comment-author.png"))
                .andExpect(jsonPath("$.items[0].body").value("hello"))
                .andExpect(jsonPath("$.hasMore").value(true));

        verify(commentService).pageComments(
                POST_ID, createTime, COMMENT_ID, 20, CommentSortOrder.EARLIEST, USER_ID);
    }

    @Test
    void repliesPageReturnsServiceResult() throws Exception {
        CommentPageResponse response = new CommentPageResponse(
                List.of(new CommentItemResponse(String.valueOf(REPLY_ID), String.valueOf(POST_ID),
                        String.valueOf(COMMENT_ID), String.valueOf(COMMENT_ID), String.valueOf(USER_ID),
                        "回复作者", "/avatar/reply-author.png", "reply", 0, false, 0, 0, null, null, false)),
                null,
                null,
                false
        );
        when(commentService.pageReplies(COMMENT_ID, null, null, 10)).thenReturn(response);

        mockMvc.perform(get("/api/v1/comments/{commentId}/replies", COMMENT_ID)
                        .queryParam("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].commentId").value(REPLY_ID))
                .andExpect(jsonPath("$.items[0].rootId").value(COMMENT_ID))
                .andExpect(jsonPath("$.items[0].creatorNickname").value("回复作者"))
                .andExpect(jsonPath("$.items[0].creatorAvatar").value("/avatar/reply-author.png"))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(commentService).pageReplies(COMMENT_ID, null, null, 10);
    }

    @Test
    void deleteCallsServiceAndPublishesFeedback() throws Exception {
        mockMvc.perform(delete("/api/v1/comments/{commentId}", COMMENT_ID))
                .andExpect(status().isNoContent());

        verify(jwtService).extractUserId(jwt);
        verify(commentService).delete(USER_ID, COMMENT_ID);
        ArgumentCaptor<CommentFeedbackEvent> eventCaptor = ArgumentCaptor.forClass(CommentFeedbackEvent.class);
        verify(feedbackProducer).publish(eventCaptor.capture());
        CommentFeedbackEvent event = eventCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.commentId()).isEqualTo(COMMENT_ID);
        org.assertj.core.api.Assertions.assertThat(event.creatorId()).isEqualTo(USER_ID);
        org.assertj.core.api.Assertions.assertThat(event.action()).isEqualTo(CommentFeedbackEvent.DELETE);
    }

    @Test
    void deleteReturnsNoContentWhenFeedbackPublishFails() throws Exception {
        useRealFeedbackProducerWithFailingKafka();

        mockMvc.perform(delete("/api/v1/comments/{commentId}", COMMENT_ID))
                .andExpect(status().isNoContent());

        verify(commentService).delete(USER_ID, COMMENT_ID);
    }

    @Test
    void likeCallsCounterAndPublishesFeedback() throws Exception {
        when(counterService.like("comment", String.valueOf(COMMENT_ID), USER_ID)).thenReturn(true);

        mockMvc.perform(post("/api/v1/comments/{commentId}/like", COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));

        verify(jwtService).extractUserId(jwt);
        verify(counterService).like("comment", String.valueOf(COMMENT_ID), USER_ID);
        ArgumentCaptor<CommentFeedbackEvent> eventCaptor = ArgumentCaptor.forClass(CommentFeedbackEvent.class);
        verify(feedbackProducer).publish(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue().action()).isEqualTo(CommentFeedbackEvent.LIKE);
    }

    @Test
    void likeReturnsOkWhenFeedbackPublishFailsAfterChangedTrue() throws Exception {
        useRealFeedbackProducerWithFailingKafka();
        when(counterService.like("comment", String.valueOf(COMMENT_ID), USER_ID)).thenReturn(true);

        mockMvc.perform(post("/api/v1/comments/{commentId}/like", COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void likeDoesNotPublishFeedbackWhenCounterNoOps() throws Exception {
        when(counterService.like("comment", String.valueOf(COMMENT_ID), USER_ID)).thenReturn(false);

        mockMvc.perform(post("/api/v1/comments/{commentId}/like", COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));

        verify(feedbackProducer, never()).publish(any());
    }

    @Test
    void unlikeCallsCounterAndPublishesFeedback() throws Exception {
        when(counterService.unlike("comment", String.valueOf(COMMENT_ID), USER_ID)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/comments/{commentId}/like", COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));

        verify(jwtService).extractUserId(jwt);
        verify(counterService).unlike("comment", String.valueOf(COMMENT_ID), USER_ID);
        ArgumentCaptor<CommentFeedbackEvent> eventCaptor = ArgumentCaptor.forClass(CommentFeedbackEvent.class);
        verify(feedbackProducer).publish(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue().action()).isEqualTo(CommentFeedbackEvent.UNLIKE);
    }

    @Test
    void unlikeReturnsOkWhenFeedbackPublishFailsAfterChangedTrue() throws Exception {
        useRealFeedbackProducerWithFailingKafka();
        when(counterService.unlike("comment", String.valueOf(COMMENT_ID), USER_ID)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/comments/{commentId}/like", COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void unlikeDoesNotPublishFeedbackWhenCounterNoOps() throws Exception {
        when(counterService.unlike("comment", String.valueOf(COMMENT_ID), USER_ID)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/comments/{commentId}/like", COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));

        verify(feedbackProducer, never()).publish(any());
    }

    private void useRealFeedbackProducerWithFailingKafka() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(), any(), any())).thenThrow(new RuntimeException("feedback kafka down"));
        feedbackProducer = new CommentFeedbackProducer(kafkaTemplate, new ObjectMapper(), "comment-feedback");
        CommentController controller = new CommentController(commentService, counterService, jwtService, feedbackProducer);
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
}

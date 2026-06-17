package com.tongji.comment.api;

import com.tongji.auth.token.JwtService;
import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentStatusResponse;
import com.tongji.comment.api.dto.CommentSubmitRequest;
import com.tongji.comment.api.dto.CommentSubmitResponse;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.comment.event.CommentFeedbackProducer;
import com.tongji.comment.service.CommentService;
import com.tongji.counter.service.CounterService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CommentController {
    private final CommentService commentService;
    private final CounterService counterService;
    private final JwtService jwtService;
    private final CommentFeedbackProducer feedbackProducer;

    public CommentController(CommentService commentService,
                             CounterService counterService,
                             JwtService jwtService,
                             CommentFeedbackProducer feedbackProducer) {
        this.commentService = commentService;
        this.counterService = counterService;
        this.jwtService = jwtService;
        this.feedbackProducer = feedbackProducer;
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentSubmitResponse> submit(@PathVariable("postId") long postId,
                                                        @Valid @RequestBody CommentSubmitRequest request,
                                                        @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(commentService.submit(userId, postId, request));
    }

    @GetMapping("/comments/{pendingCommentId}/status")
    public CommentStatusResponse status(@PathVariable("pendingCommentId") long pendingCommentId) {
        return commentService.status(pendingCommentId);
    }

    @GetMapping("/posts/{postId}/comments")
    public CommentPageResponse comments(@PathVariable("postId") long postId,
                                        @RequestParam(value = "cursorCreateTime", required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorCreateTime,
                                        @RequestParam(value = "cursorCommentId", required = false) Long cursorCommentId,
                                        @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return commentService.pageComments(postId, cursorCreateTime, cursorCommentId, limit);
    }

    @GetMapping("/comments/{commentId}/replies")
    public CommentPageResponse replies(@PathVariable("commentId") long commentId,
                                       @RequestParam(value = "cursorCreateTime", required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorCreateTime,
                                       @RequestParam(value = "cursorCommentId", required = false) Long cursorCommentId,
                                       @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return commentService.pageReplies(commentId, cursorCreateTime, cursorCommentId, limit);
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable("commentId") long commentId,
                                       @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        commentService.delete(userId, commentId);
        feedbackProducer.publish(new CommentFeedbackEvent(commentId, null, null, null, userId, CommentFeedbackEvent.DELETE));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{commentId}/like")
    public Map<String, Object> like(@PathVariable("commentId") long commentId,
                                    @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        boolean changed = counterService.like("comment", String.valueOf(commentId), userId);
        if (changed) {
            feedbackProducer.publish(new CommentFeedbackEvent(commentId, null, null, null, userId, CommentFeedbackEvent.LIKE));
        }
        return Map.of("changed", changed);
    }

    @DeleteMapping("/comments/{commentId}/like")
    public Map<String, Object> unlike(@PathVariable("commentId") long commentId,
                                      @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        boolean changed = counterService.unlike("comment", String.valueOf(commentId), userId);
        if (changed) {
            feedbackProducer.publish(new CommentFeedbackEvent(commentId, null, null, null, userId, CommentFeedbackEvent.UNLIKE));
        }
        return Map.of("changed", changed);
    }
}

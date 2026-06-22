package com.tongji.knowpost.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.KnowPostContentConfirmRequest;
import com.tongji.knowpost.api.dto.KnowPostDraftCreateResponse;
import com.tongji.knowpost.api.dto.KnowPostPatchRequest;
import com.tongji.knowpost.api.dto.KnowPostTopPatchRequest;
import com.tongji.knowpost.api.dto.KnowPostVisibilityPatchRequest;
import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishRequest;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.manager.PublishManager;
import com.tongji.knowpost.service.KnowPostService;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import com.tongji.recommendation.HomeFeedMixingService;
import com.tongji.recommendation.feed.FollowFeedService;
import com.tongji.recommendation.feed.TimelineItem;
import com.tongji.recommendation.feed.TimelinePage;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowposts")
@Validated
public class KnowPostController {

    private static final int FOLLOW_FEED_SIZE = 20;

    private final KnowPostService service;
    private final KnowPostFeedService feedService;
    private final JwtService jwtService;
    private final PublishManager publishManager;
    private final HomeFeedMixingService homeFeedMixingService;
    private final FollowFeedService followFeedService;
    private final boolean mixedHomeFeedEnabled;

    public KnowPostController(KnowPostService service,
                              KnowPostFeedService feedService,
                              JwtService jwtService,
                              PublishManager publishManager,
                              HomeFeedMixingService homeFeedMixingService,
                              FollowFeedService followFeedService,
                              @Value("${feed.home.mixed-enabled:false}") boolean mixedHomeFeedEnabled) {
        this.service = service;
        this.feedService = feedService;
        this.jwtService = jwtService;
        this.publishManager = publishManager;
        this.homeFeedMixingService = homeFeedMixingService;
        this.followFeedService = followFeedService;
        this.mixedHomeFeedEnabled = mixedHomeFeedEnabled;
    }

    /**
     * 创建草稿，返回新 ID。默认类型为 image_text。
     */
    @PostMapping("/drafts")
    public KnowPostDraftCreateResponse createDraft(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        long id = service.createDraft(userId);
        return new KnowPostDraftCreateResponse(String.valueOf(id));
    }

    /**
     * 上传内容成功后回传确认，写入对象存储信息。
     */
    @PostMapping("/{id}/content/confirm")
    public ResponseEntity<Void> confirmContent(@PathVariable("id") long id,
                                               @Valid @RequestBody KnowPostContentConfirmRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.confirmContent(userId, id, request.objectKey(), request.etag(), request.size(), request.sha256());
        return ResponseEntity.noContent().build();
    }

    /**
     * 更新元数据（标题、标签、可见性、置顶、图片列表等）。
     */
    @PatchMapping("/{id}")
    public ResponseEntity<Void> patchMetadata(@PathVariable("id") long id,
                                              @Valid @RequestBody KnowPostPatchRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateMetadata(userId, id, request.title(), request.tagId(), request.tags(), request.imgUrls(), request.visible(), request.isTop(), request.description());
        return ResponseEntity.noContent().build();
    }

    /**
     * 发布帖子（状态置为 published）。
     */
    @PostMapping("/{id}/publish")
    public ResponseEntity<PublishAcceptedResponse> publish(@PathVariable("id") long id,
                                                           @Valid @RequestBody PublishRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        PublishAcceptedResponse response = publishManager.acceptPublish(userId, id, request.idempotentKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}/publish/status")
    public PublishStatusResponse publishStatus(@PathVariable("id") long id,
                                               @RequestParam("attemptId") long attemptId,
                                               @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return publishManager.getPublishStatus(userId, id, attemptId);
    }

    @PostMapping("/{id}/publish/{attemptId}/retry")
    public ResponseEntity<PublishAcceptedResponse> retryPublish(@PathVariable("id") long id,
                                                                @PathVariable("attemptId") long attemptId,
                                                                @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        PublishAcceptedResponse response = publishManager.retryPublish(userId, id, attemptId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 设置置顶状态。
     */
    @PatchMapping("/{id}/top")
    public ResponseEntity<Void> patchTop(@PathVariable("id") long id,
                                         @Valid @RequestBody KnowPostTopPatchRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateTop(userId, id, request.isTop());
        return ResponseEntity.noContent().build();
    }

    /**
     * 设置可见性（权限）。
     */
    @PatchMapping("/{id}/visibility")
    public ResponseEntity<Void> patchVisibility(@PathVariable("id") long id,
                                                @Valid @RequestBody KnowPostVisibilityPatchRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.updateVisibility(userId, id, request.visible());
        return ResponseEntity.noContent().build();
    }

    /**
     * 删除知文（软删除）。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id,
                                       @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 首页 Feed（公开、已发布）分页查询；默认每页 20，最大 50。
     */
    @GetMapping("/feed")
    public FeedPageResponse feed(@RequestParam(value = "page", defaultValue = "1") int page,
                                 @RequestParam(value = "size", defaultValue = "20") int size,
                                 @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        if (userId != null && mixedHomeFeedEnabled) {
            return homeFeedMixingService.getHomeFeed(userId);
        }
        return feedService.getPublicFeed(page, size, userId);
    }

    @GetMapping("/feed/follow")
    public FeedPageResponse followFeed(@RequestParam(value = "cursor", required = false) String cursor,
                                       @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        validateCursor(cursor);
        List<FeedItemResponse> items = new ArrayList<>(FOLLOW_FEED_SIZE);
        String nextCursor = null;
        String currentCursor = cursor;
        while (items.size() < FOLLOW_FEED_SIZE) {
            TimelinePage timelinePage = followFeedService.getTimeline(userId, currentCursor, FOLLOW_FEED_SIZE);
            if (timelinePage == null || timelinePage.items() == null) {
                break;
            }
            List<TimelineItem> rawItems = timelinePage.items();
            int need = FOLLOW_FEED_SIZE - items.size();
            List<Long> ids = rawItems.stream()
                    .map(TimelineItem::contentId)
                    .toList();
            List<FeedItemResponse> hydrated =
                    feedService.getFeedByIds(ids, userId, KnowPostFeedService.FeedVisibilityScope.FOLLOW);
            if (hydrated == null) {
                hydrated = List.of();
            }
            if (hydrated.size() >= need) {
                items.addAll(hydrated.subList(0, need));
                String lastIncludedId = hydrated.get(need - 1).id();
                String lastIncludedCursor = cursorForLastIncludedRawItem(rawItems, lastIncludedId, null);
                boolean hasMoreInCurrentBatch = hasMoreAfterIncludedItem(rawItems, lastIncludedId);
                nextCursor = (hasMoreInCurrentBatch || timelinePage.nextCursor() != null) ? lastIncludedCursor : null;
                break;
            }
            items.addAll(hydrated);
            if (timelinePage.nextCursor() == null) {
                break;
            }
            currentCursor = timelinePage.nextCursor();
        }
        return new FeedPageResponse(items, 1, FOLLOW_FEED_SIZE, nextCursor != null, nextCursor);
    }

    private String cursorForLastIncludedRawItem(List<com.tongji.recommendation.feed.TimelineItem> rawItems,
                                                String lastIncludedId,
                                                String fallbackCursor) {
        if (lastIncludedId == null) {
            return fallbackCursor;
        }
        try {
            long includedContentId = Long.parseLong(lastIncludedId);
            for (com.tongji.recommendation.feed.TimelineItem rawItem : rawItems) {
                if (rawItem.contentId() == includedContentId) {
                    return rawItem.publishTs().toEpochMilli() + ":" + rawItem.contentId();
                }
            }
        } catch (NumberFormatException ignored) {
            return fallbackCursor;
        }
        return fallbackCursor;
    }

    private boolean hasMoreAfterIncludedItem(List<com.tongji.recommendation.feed.TimelineItem> rawItems,
                                             String lastIncludedId) {
        if (rawItems == null || lastIncludedId == null) {
            return false;
        }
        try {
            long includedContentId = Long.parseLong(lastIncludedId);
            for (int i = 0; i < rawItems.size(); i++) {
                if (rawItems.get(i).contentId() == includedContentId) {
                    return i < rawItems.size() - 1;
                }
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
        return false;
    }

    private void validateCursor(String cursor) {
        if (cursor == null) {
            return;
        }
        if (cursor.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "cursor 非法");
        }
        int separator = cursor.indexOf(':');
        if (separator <= 0 || separator == cursor.length() - 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "cursor 非法");
        }
        try {
            Long.parseLong(cursor.substring(0, separator));
            Long.parseLong(cursor.substring(separator + 1));
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "cursor 非法");
        }
    }

    /**
     * 我的知文（当前用户已发布）分页查询；默认每页 20，最大 50。
     */
    @GetMapping("/mine")
    public FeedPageResponse mine(@RequestParam(value = "page", defaultValue = "1") int page,
                                 @RequestParam(value = "size", defaultValue = "20") int size,
                                 @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return feedService.getMyPublished(userId, page, size);
    }

    /**
     * 知文详情（公开：published+public；非公开需作者本人）。
     */
    @GetMapping("/detail/{id}")
    public KnowPostDetailResponse detail(@PathVariable("id") long id,
                                         @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return service.getDetail(id, userId);
    }
}

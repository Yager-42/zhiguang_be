package com.tongji.knowpost.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.service.KnowPostService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.FeedPageCounterState;
import com.tongji.storage.MinioStorageService;
import com.tongji.outbox.OutboxMapper;
import com.tongji.cache.hotkey.HotKeyDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class KnowPostServiceImpl implements KnowPostService {

    private final KnowPostMapper mapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;
    private final MinioStorageService storageService;
    private final CounterService counterService;
    private final UserCounterService userCounterService;
    private final StringRedisTemplate redis;
    @Qualifier("knowPostDetailCache")
    private final Cache<String, KnowPostDetailResponse> knowPostDetailCache;
    private final HotKeyDetector hotKey;
    private static final Logger log = LoggerFactory.getLogger(KnowPostServiceImpl.class);
    private static final int DETAIL_LAYOUT_VER = 1;
    private static final String DETAIL_SINGLEFLIGHT_STAGE = "knowpost-detail";
    private static final TypeReference<KnowPostDetailResponse> DETAIL_TYPE = new TypeReference<>() {};
    private final DistributedSingleFlightService singleFlightService;
    private final OutboxMapper outboxMapper;

    // 手动编写构造器，Spring的@Qualifier直接标注在参数上（核心）
    public KnowPostServiceImpl(
            KnowPostMapper mapper,
            IdService idService,
            ObjectMapper objectMapper,
            MinioStorageService storageService,
            CounterService counterService,
            UserCounterService userCounterService,
            StringRedisTemplate redis,
            @Qualifier("knowPostDetailCache") Cache<String, KnowPostDetailResponse> knowPostDetailCache,
            HotKeyDetector hotKey,
            OutboxMapper outboxMapper,
            DistributedSingleFlightService singleFlightService
    ) {
        this.mapper = mapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.counterService = counterService;
        this.userCounterService = userCounterService;
        this.redis = redis;
        this.knowPostDetailCache = knowPostDetailCache; // 带@Qualifier的参数赋值
        this.hotKey = hotKey;
        this.outboxMapper = outboxMapper;
        this.singleFlightService = singleFlightService;
    }
    /**
     * 创建草稿并返回新 ID。
     */
    @Transactional
    public long createDraft(long creatorId) {
        long id = idService.nextId(IdNamespace.POST);
        Instant now = Instant.now();
        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .status("draft")
                .type("image_text")
                .visible("public")
                .isTop(false)
                .createTime(now)
                .updateTime(now)
                .build();
        mapper.insertDraft(post);
        return id;
    }

    /**
     * 确认内容上传（写入 objectKey、etag、大小、校验和，并生成公共 URL）。
     */
    @Transactional
    public void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256) {
        // 缓存双删
        invalidateCache(id);

        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .contentObjectKey(objectKey)
                .contentEtag(etag)
                .contentSize(size)
                .contentSha256(sha256)
                .contentUrl(publicUrl(objectKey))
                .updateTime(Instant.now())
                .build();

        int updated = mapper.updateContent(post);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);

        // 触发一次预索引（草稿阶段可能因可见性/状态被跳过）
    }

    /**
     * 更新元数据：标题、标签、可见性、置顶、图片列表等。
     */
    @Transactional
    public void updateMetadata(long creatorId, long id, String title, Long tagId, List<String> tags, List<String> imgUrls, String visible, Boolean isTop, String description) {
        invalidateCache(id);

        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .title(title)
                .tagId(tagId)
                .tags(toJsonOrNull(tags))
                .imgUrls(toJsonOrNull(imgUrls))
                .visible(visible)
                .isTop(isTop)
                .description(description)
                .type("image_text")
                .updateTime(Instant.now())
                .build();

        int updated = mapper.updateMetadata(post);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 元数据变更后写入 Outbox 事件，驱动搜索索引更新
        try {
            long outId = idService.nextId(IdNamespace.OUTBOX_EVENT);
            String payload = objectMapper.writeValueAsString(Map.of("entity", "knowpost", "op", "upsert", "id", id));
            outboxMapper.insert(outId, "knowpost", id, "KnowPostMetadataUpdated", payload);
        } catch (Exception e) {
            log.warn("Outbox event after metadata update failed, post {}: {}", id, e.getMessage());
        }

        invalidateCache(id);
    }

    /**
     * 设置置顶。
     */
    @Transactional
    public void updateTop(long creatorId, long id, boolean isTop) {
        invalidateCache(id);

        int updated = mapper.updateTop(id, creatorId, isTop);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);
    }

    /**
     * 设置可见性（权限）。
     */
    @Transactional
    public void updateVisibility(long creatorId, long id, String visible) {
        if (!isValidVisible(visible)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可见性取值非法");
        }

        invalidateCache(id);

        int updated = mapper.updateVisibility(id, creatorId, visible);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);
    }

    /**
     * 软删除。
     */
    @Transactional
    public void delete(long creatorId, long id) {
        invalidateCache(id);

        int updated = mapper.softDelete(id, creatorId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 写入 Outbox 事件，驱动搜索索引软删
        try {
            long outId = idService.nextId(IdNamespace.OUTBOX_EVENT);
            String payload = objectMapper.writeValueAsString(Map.of("entity", "knowpost", "op", "delete", "id", id));
            outboxMapper.insert(outId, "knowpost", id, "KnowPostDeleted", payload);
        } catch (Exception e) {
            log.warn("Outbox event after delete failed, post {}: {}", id, e.getMessage());
        }

        invalidateCache(id);
    }

    private boolean isValidVisible(String visible) {
        if (visible == null) {
            return false;
        }

        return switch (visible) {
            case "public", "followers", "school", "private", "unlisted" -> true;
            default -> false;
        };
    }

    private String toJsonOrNull(List<String> list) {
        if (list == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JSON 处理失败");
        }
    }

    private String publicUrl(String objectKey) {
        return storageService.publicUrl(objectKey);
    }

    /**
     * 获取知文详情（含作者信息、图片列表）。
     * <p>
     * 流程：
     * 1. 尝试读取 Redis 缓存。
     * 2. 若缓存命中，直接返回（需叠加实时计数与用户状态）。
     * 3. 若缓存未命中，使用 SingleFlight 锁机制防止缓存击穿。
     * 4. 锁内再次检查缓存（双重检查）。
     * 5. 若仍未命中，回源查询数据库。
     * 6. 校验内容状态与访问权限。
     * 7. 组装数据并写入 Redis 缓存（带随机过期时间与热点自动延期）。
     * 8. 返回最终结果（叠加用户维度状态）。
     * </p>
     *
     * @param id 知文 ID
     * @param currentUserIdNullable 当前用户 ID（可空，用于判断权限与点赞状态）
     * @return 知文详情响应
     */
    @Transactional(readOnly = true)
    public KnowPostDetailResponse getDetail(long id, Long currentUserIdNullable) {
        String pageKey = "knowpost:detail:" + id + ":v" + DETAIL_LAYOUT_VER;

        KnowPostDetailResponse local = knowPostDetailCache.getIfPresent(pageKey);
        if (local != null) {
            requireVisibleTo(local, currentUserIdNullable);
            recordItemHeat(id);
            log.debug("detail source=local key={}", pageKey);
            return enrichDetailResponse(local, currentUserIdNullable);
        }

        KnowPostDetailResponse cached = readBaseDetail(redis.opsForValue().get(pageKey), id, pageKey, "page");
        if (cached != null) {
            requireVisibleTo(cached, currentUserIdNullable);
            return enrichDetailResponse(cached, currentUserIdNullable);
        }

        String viewerKey = currentUserIdNullable == null ? "anonymous" : String.valueOf(currentUserIdNullable);
        KnowPostDetailResponse base = singleFlightService.execute(
                DETAIL_SINGLEFLIGHT_STAGE,
                pageKey + ":viewer:" + viewerKey,
                DETAIL_TYPE,
                () -> {
                    KnowPostDetailResponse again = readBaseDetail(
                            redis.opsForValue().get(pageKey), id, pageKey, "page(after-flight)");
                    if (again != null) {
                        requireVisibleTo(again, currentUserIdNullable);
                        return again;
                    }

                    KnowPostDetailRow row = mapper.findDetailById(id);
                    if (row == null || "deleted".equals(row.getStatus())) {
                        redis.opsForValue().set(pageKey, "NULL", Duration.ofSeconds(
                                30 + ThreadLocalRandom.current().nextInt(31)));
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "内容不存在");
                    }

                    KnowPostDetailResponse loaded = toBaseDetail(row);
                    requireVisibleTo(loaded, currentUserIdNullable);
                    writeDetailCaches(pageKey, loaded);
                    return loaded;
                }
        );
        requireVisibleTo(base, currentUserIdNullable);
        return enrichDetailResponse(base, currentUserIdNullable);
    }

    private KnowPostDetailResponse readBaseDetail(String cached, long id, String pageKey, String sourceLog) {
        if (cached == null) {
            return null;
        }
        if ("NULL".equals(cached)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容不存在");
        }
        try {
            KnowPostDetailResponse base = objectMapper.readValue(cached, KnowPostDetailResponse.class);
            knowPostDetailCache.put(pageKey, base);
            recordItemHeat(id);
            log.debug("detail source={} key={}", sourceLog, pageKey);
            return base;
        } catch (Exception ignored) {
            return null;
        }
    }

    private KnowPostDetailResponse toBaseDetail(KnowPostDetailRow row) {
        return new KnowPostDetailResponse(
                String.valueOf(row.getId()),
                row.getTitle(),
                row.getDescription(),
                row.getContentUrl(),
                parseStringArray(row.getImgUrls()),
                parseStringArray(row.getTags()),
                String.valueOf(row.getCreatorId()),
                row.getAuthorAvatar(),
                row.getAuthorNickname(),
                row.getAuthorTagJson(),
                0L,
                0L,
                null,
                null,
                row.getIsTop(),
                row.getVisible(),
                row.getType(),
                row.getPublishTime()
        );
    }

    private void requireVisibleTo(KnowPostDetailResponse detail, Long viewerId) {
        boolean isPublic = "public".equals(detail.visible());
        boolean isOwner = viewerId != null && String.valueOf(viewerId).equals(detail.authorId());
        if (!isPublic && !isOwner) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无权限查看");
        }
    }

    private void writeDetailCaches(String pageKey, KnowPostDetailResponse detail) {
        try {
            String json = objectMapper.writeValueAsString(detail);
            int baseTtl = 60;
            int jitter = ThreadLocalRandom.current().nextInt(30);
            int target = hotKey.ttlForPublic(baseTtl, pageKey);
            redis.opsForValue().set(pageKey, json, Duration.ofSeconds(Math.max(target, baseTtl + jitter)));
            knowPostDetailCache.put(pageKey, detail);
            log.debug("detail source=db key={}", pageKey);
        } catch (Exception ignored) {
        }
    }



    /**
     * 丰富详情响应：叠加实时计数与用户状态。
     *
     * @param base 基础响应对象（来自缓存或 DB）
     * @param uid 当前用户 ID
     * @return 叠加了最新状态的响应对象
     */
    private KnowPostDetailResponse enrichDetailResponse(KnowPostDetailResponse base, Long uid) {
        Long likeCount = base.likeCount();
        Long favoriteCount = base.favoriteCount();

        Map<String, FeedPageCounterState> states = counterService.getFeedPageStateBatch(
                "knowpost", List.of(base.id()), uid, List.of("like", "fav"));
        FeedPageCounterState state = states == null ? null : states.get(base.id());
        if (state != null) {
            likeCount = state.counts().getOrDefault("like", likeCount == null ? 0L : likeCount);
            favoriteCount = state.counts().getOrDefault("fav", favoriteCount == null ? 0L : favoriteCount);
        }
        Boolean liked = state != null && state.liked();
        Boolean faved = state != null && state.faved();

        // 3. 构造新的 Record 对象返回
        return new KnowPostDetailResponse(
                base.id(),
                base.title(),
                base.description(),
                base.contentUrl(),
                base.images(),
                base.tags(),
                base.authorId(),
                base.authorAvatar(),
                base.authorNickname(),
                base.authorTagJson(),
                likeCount,
                favoriteCount,
                liked,
                faved,
                base.isTop(),
                base.visible(),
                base.type(),
                base.publishTime()
        );
    }

    /**
     * 仅在进程内记录内容热度；缓存 TTL 在写入时按热度确定，读取路径不做远程续期。
     */
    private void recordItemHeat(long id) {
        hotKey.record("knowpost:detail:" + id + ":v" + DETAIL_LAYOUT_VER);
    }

    private void invalidateCache(long id) {
        String pageKey = "knowpost:detail:" + id + ":v" + DETAIL_LAYOUT_VER;

        redis.delete(pageKey);

        knowPostDetailCache.invalidate(pageKey);
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}

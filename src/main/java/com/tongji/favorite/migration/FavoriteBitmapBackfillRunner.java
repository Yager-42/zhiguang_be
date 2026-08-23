package com.tongji.favorite.migration;

import com.tongji.counter.schema.BitmapShard;
import com.tongji.favorite.mapper.FavoriteMapper;
import com.tongji.favorite.model.UserFavorite;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 将切换前的收藏 Bitmap 一次性迁移到 MySQL 收藏关系表。
 *
 * <p>任务默认关闭，通过 {@code FAVORITE_BACKFILL_ENABLED=true} 显式启用；写入使用
 * {@code INSERT IGNORE}，中断后可以安全重跑。迁移不写 Outbox，因为 Redis 与计数已经包含这些历史事实。</p>
 *
 * @since 2026-08-21
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "favorite.backfill.enabled", havingValue = "true")
public class FavoriteBitmapBackfillRunner implements ApplicationRunner {
    private static final int INSERT_BATCH_SIZE = 500;
    private static final String FAVORITE_BITMAP_PATTERN = "bm:fav:knowpost:*:*";

    private final StringRedisTemplate redis;
    private final FavoriteMapper favoriteMapper;

    public FavoriteBitmapBackfillRunner(StringRedisTemplate redis, FavoriteMapper favoriteMapper) {
        this.redis = redis;
        this.favoriteMapper = favoriteMapper;
    }

    /**
     * 扫描历史收藏分片并以有界批次写入 MySQL。
     *
     * @param args Spring 启动参数，本任务不读取其中内容
     */
    @Override
    public void run(ApplicationArguments args) {
        List<UserFavorite> batch = new ArrayList<>(INSERT_BATCH_SIZE);
        long discovered = 0L;
        long inserted = 0L;
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(FAVORITE_BITMAP_PATTERN).count(500).build())) {
            while (cursor.hasNext()) {
                BitmapReference reference = parseReference(cursor.next());
                if (reference == null) {
                    continue;
                }
                byte[] bitmap = readBitmap(reference.key());
                if (bitmap == null) {
                    continue;
                }
                for (long userId : decodeUserIds(bitmap, reference.chunk())) {
                    if (userId <= 0) {
                        continue;
                    }
                    batch.add(favorite(userId, reference.postId()));
                    discovered++;
                    if (batch.size() == INSERT_BATCH_SIZE) {
                        inserted += favoriteMapper.insertIgnoreBatch(batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                inserted += favoriteMapper.insertIgnoreBatch(batch);
            }
            log.info("favorite bitmap backfill completed discovered={} inserted={}", discovered, inserted);
        } catch (RuntimeException exception) {
            log.error("favorite bitmap backfill failed; rerun is safe", exception);
            throw exception;
        }
    }

    private byte[] readBitmap(String key) {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
    }

    static List<Long> decodeUserIds(byte[] bitmap, long chunk) {
        List<Long> userIds = new ArrayList<>();
        int bitLimit = Math.min(bitmap.length * Byte.SIZE, BitmapShard.CHUNK_SIZE);
        for (int offset = 0; offset < bitLimit; offset++) {
            int byteValue = bitmap[offset / Byte.SIZE] & 0xff;
            int mask = 1 << (7 - offset % Byte.SIZE);
            if ((byteValue & mask) != 0) {
                userIds.add(chunk * BitmapShard.CHUNK_SIZE + offset);
            }
        }
        return userIds;
    }

    private BitmapReference parseReference(String key) {
        String[] parts = key.split(":", 5);
        if (parts.length != 5 || !"bm".equals(parts[0]) || !"fav".equals(parts[1])
                || !"knowpost".equals(parts[2])) {
            return null;
        }
        try {
            long postId = Long.parseLong(parts[3]);
            long chunk = Long.parseLong(parts[4]);
            return postId > 0 && chunk >= 0 ? new BitmapReference(key, postId, chunk) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UserFavorite favorite(long userId, long postId) {
        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(userId);
        favorite.setPostId(postId);
        favorite.setCreatedAt(Instant.now());
        return favorite;
    }

    private record BitmapReference(String key, long postId, long chunk) {
    }
}

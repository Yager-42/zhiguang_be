package com.tongji.counter.service;

import com.tongji.counter.schema.CounterKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Backfills bitmap shard indexes for facts written before shard indexes were introduced.
 */
@Slf4j
@Component
public class CounterBitmapShardIndexInitializer implements ApplicationRunner {
    private static final int PIPELINE_BATCH_SIZE = 500;

    private final StringRedisTemplate redis;

    public CounterBitmapShardIndexInitializer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ShardRef> batch = new ArrayList<>(PIPELINE_BATCH_SIZE);
        long indexed = 0L;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match("bm:*").count(1000).build())) {
            while (cursor.hasNext()) {
                ShardRef ref = parse(cursor.next());
                if (ref == null) {
                    continue;
                }
                batch.add(ref);
                if (batch.size() == PIPELINE_BATCH_SIZE) {
                    flush(batch);
                    indexed += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                flush(batch);
                indexed += batch.size();
            }
            log.info("counter bitmap shard index backfill completed indexed={}", indexed);
        } catch (RuntimeException exception) {
            log.warn("counter bitmap shard index backfill failed", exception);
        }
    }

    private void flush(List<ShardRef> batch) {
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (ShardRef ref : batch) {
                connection.setCommands().sAdd(
                        ref.indexKey().getBytes(StandardCharsets.UTF_8),
                        ref.chunk().getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
    }

    private ShardRef parse(String key) {
        String[] parts = key.split(":", 5);
        if (parts.length != 5 || !"bm".equals(parts[0]) || "index".equals(parts[1])) {
            return null;
        }
        try {
            Long.parseLong(parts[4]);
        } catch (NumberFormatException exception) {
            return null;
        }
        return new ShardRef(CounterKeys.bitmapShardIndexKey(parts[1], parts[2], parts[3]), parts[4]);
    }

    private record ShardRef(String indexKey, String chunk) {
    }
}

package com.tongji.counter.schema;

/**
 * Redis Key 生成工具。
 */
public final class CounterKeys {
    private CounterKeys() {}

    public static String sdsKey(String entityType, String entityId) {
        return String.format("cnt:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId); // 固定结构计数（SDS）键
    }

    // 分片键：bm:{metric}:{etype}:{eid}:{chunk}
    public static String bitmapKey(String metric, String entityType, String entityId, long chunk) {
        return String.format("bm:%s:%s:%s:%d", metric, entityType, entityId, chunk); // 位图事实层（分片）
    }

    public static String bitmapShardIndexKey(String metric, String entityType, String entityId) {
        return String.format("bm:index:%s:%s:%s", metric, entityType, entityId);
    }

    // 聚合增量持久化桶（Hash）：agg:{schema}:{etype}:{eid}
    public static String aggKey(String entityType, String entityId) {
        return String.format("agg:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId); // 刷写前的增量存储桶
    }

    public static String aggregateDirtySetKey() {
        return String.format("agg:%s:dirty", CounterSchema.SCHEMA_ID);
    }
}
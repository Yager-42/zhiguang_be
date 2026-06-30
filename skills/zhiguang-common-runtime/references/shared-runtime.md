# 共享运行时边界

## 覆盖包

- `src/main/java/com/tongji/common/**`
- `src/main/java/com/tongji/config/**`
- `src/main/java/com/tongji/id/**`
- `src/main/java/com/tongji/llm/**`

## 主要入口

- `src/main/java/com/tongji/common/web/GlobalExceptionHandler.java`
- `src/main/java/com/tongji/common/exception/BusinessException.java`
- `src/main/java/com/tongji/common/exception/ErrorCode.java`
- `src/main/java/com/tongji/common/id/DefaultIdService.java`
- `src/main/java/com/tongji/common/id/segment/SegmentIdGenerator.java`
- `src/main/java/com/tongji/config/ThreadPoolConfig.java`
- `src/main/java/com/tongji/config/RedissonConfig.java`
- `src/main/java/com/tongji/config/ElasticsearchConfig.java`
- `src/main/java/com/tongji/config/EsProperties.java`

## Drift Notes

### 2026-06-30 Drift Note / SUG-zhiguang-common-runtime-config-7bc26be01d
- 触发类型：`config`
- 代码事实：`application.yml` 来自 `src/main/resources/application.yml`
- 建议动作：人工确认 `shared-runtime.md` 是否需要补入这批变更的最新语义。

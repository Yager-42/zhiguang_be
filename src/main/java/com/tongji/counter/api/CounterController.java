package com.tongji.counter.api;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.counter.api.dto.BatchCountsResponse;
import com.tongji.counter.api.dto.CountsResponse;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.counter.service.CounterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 计数读取接口：返回指定实体在给定指标上的汇总计数（SDS）。
 */
@RestController
@RequestMapping("/api/v1/counter")
public class CounterController {

    private static final int MAX_BATCH_SIZE = 50;

    private final CounterService counterService;

    public CounterController(CounterService counterService) {
        this.counterService = counterService;
    }

    /**
     * 获取实体的计数汇总。
     * @param entityType 实体类型（如 knowpost）
     * @param entityId 实体ID
     * @param metricsStr 指标列表（逗号分隔），为空则返回全部支持指标
     */
    @GetMapping("/{etype}/{eid}")
    public ResponseEntity<CountsResponse> getCounts(@PathVariable("etype") String entityType,
                                                    @PathVariable("eid") String entityId,
                                                    @RequestParam(value = "metrics", required = false) String metricsStr) {
        List<String> metrics = parseMetrics(metricsStr);

        Map<String, Long> counts = counterService.getCounts(entityType, entityId, metrics);

        return ResponseEntity.ok(new CountsResponse(entityType, entityId, counts));
    }

    /**
     * 批量读取同一实体类型的计数，避免列表页逐条访问计数服务。
     *
     * @param entityType 实体类型，例如 {@code knowpost}
     * @param entityIds 实体 ID 列表，数量范围为 1 到 50
     * @param metricsStr 指标列表，逗号分隔；为空时返回全部支持指标
     * @return 按请求顺序组织的批量计数
     */
    @GetMapping("/{etype}")
    public ResponseEntity<BatchCountsResponse> getCountsBatch(
            @PathVariable("etype") String entityType,
            @RequestParam("eids") List<String> entityIds,
            @RequestParam(value = "metrics", required = false) String metricsStr) {
        List<String> normalizedIds = entityIds.stream()
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
        if (normalizedIds.isEmpty() || normalizedIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "eids 数量必须在 1 到 50 之间");
        }
        List<String> metrics = parseMetrics(metricsStr);
        Map<String, Map<String, Long>> counts = counterService.getCountsBatch(entityType, normalizedIds, metrics);
        return ResponseEntity.ok(new BatchCountsResponse(entityType, counts));
    }

    private List<String> parseMetrics(String metricsStr) {
        if (metricsStr == null || metricsStr.isBlank()) {
            return new ArrayList<>(CounterSchema.SUPPORTED_METRICS);
        }
        return Arrays.stream(metricsStr.split(","))
                .map(String::trim)
                .filter(CounterSchema.SUPPORTED_METRICS::contains)
                .distinct()
                .toList();
    }
}

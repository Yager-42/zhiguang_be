package com.tongji.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.counter.service.CounterService;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.search.api.dto.SearchResponse;
import com.tongji.search.api.dto.SuggestResponse;
import com.tongji.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搜索服务实现：
 * - 宽召回（multi_match 命中 title^3 与 body）+ 业务加权（function_score）
 * - 过滤（status=published、可选 tags）+ 排序（score/publish_time/like/view/content_id）
 * - 高亮片段合并为 snippet；游标分页使用 search_after
 * - 首屏（after == null）在 organic 结果前插入至多 1 条 search_top_slot 商业位；
 *   nextAfter/hasMore 只基于 organic 子序列计算，不被 promoted 污染。
 */
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private static final int PROMOTED_LIMIT = 1;
    /**
     * ponytail: organic 多取的 lookahead 预算，吸收被丢弃的命中（无效 _source / 重复 id），
     * 使 hasMore 仍按“实际接受的 organic 子序列”判断而非原始命中数。
     * 生产中丢弃≈0（promoted 帖已由 must_not 排除、ES 单次响应无重复 doc）；若无效命中率上升超过此预算，
     * upgrade：改为按 search_after 续取的循环。
     */
    private static final int SKIP_LOOKAHEAD = 4;

    private final ElasticsearchClient es;
    private final CounterService counterService;
    private final KnowPostFeedService knowPostFeedService;
    private final PromotionAllocationService promotionAllocationService;
    /**
     * ES 索引名：zhiguang 内容统一索引。
     */
    private static final String INDEX = "zhiguang_content_index";

    /**
     * 关键词检索：相关性 + 互动数据加权，支持游标分页与高亮；首屏插入至多 1 条商业位。
     */
    @SuppressWarnings("unchecked")
    public SearchResponse search(String q, int size, String tagsCsv, String after, Long currentUserIdNullable) {
        int safeSize = Math.max(size, 1);
        int promotedLimit = (after == null) ? PROMOTED_LIMIT : 0;

        // 始终读取当前 search allocation：首屏用于插入 promoted，所有页用于把 promoted 帖从 organic 排除，
        // 避免 promoted 帖在后续页以 organic 形式重复出现（跨页去重）。
        List<PromotionAllocationView> allocations = promotionAllocationService.getActiveSearchAllocation();
        if (allocations == null) {
            allocations = List.of();
        }
        List<Long> excludedPostIds = allocations.stream().map(PromotionAllocationView::postIdAsLong).toList();

        List<FeedItemResponse> items = new ArrayList<>(safeSize + 1);
        Set<String> seen = new LinkedHashSet<>();
        appendPromotedSearch(items, seen, currentUserIdNullable, promotedLimit, allocations);

        int organicNeed = safeSize - items.size();
        // 页已被 promoted 占满：无 organic 游标可给，直接返回（不查 ES）
        if (organicNeed <= 0) {
            return new SearchResponse(items, null, false);
        }
        int requested = organicNeed + 1 + SKIP_LOOKAHEAD;
        List<FieldValue> afterValues = parseAfter(after);
        List<SortOptions> sorts = buildSorts();

        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp;
        try {
            resp = es.search(s -> {
                var b = s.index(INDEX)
                        .size(requested)
                        // 召回与加权：先构造 bool 查询，再用 function_score 做互动数据加权
                        .query(qb -> qb.functionScore(fs -> fs
                                .query(qb2 -> qb2.bool(bq -> {
                                    bq.must(m -> m.multiMatch(mm -> mm.query(q)
                                            .fields("title^3", "body")));
                                    bq.filter(f -> f.term(t -> t.field("status")
                                            .value(v -> v.stringValue("published"))));

                                    if (tagsCsv != null && !tagsCsv.isBlank()) {
                                        List<String> tags = parseCsv(tagsCsv);
                                        if (!tags.isEmpty()) {
                                            bq.filter(f -> f.terms(t -> t.field("tags")
                                                    .terms(tv -> tv.value(tags.stream().map(FieldValue::of).toList()))));
                                        }
                                    }
                                    // promoted 帖从 organic 排除（content_id 为 long），保证跨页不以 organic 重复
                                    if (!excludedPostIds.isEmpty()) {
                                        bq.mustNot(mn -> mn.terms(t -> t.field("content_id")
                                                .terms(tv -> tv.value(excludedPostIds.stream()
                                                        .map(id -> FieldValue.of(id.longValue())).toList()))));
                                    }
                                    return bq;
                                }))
                                // 对点赞数收藏数设置权重
                                .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("like_count")
                                        .modifier(FieldValueFactorModifier.Log1p))
                                        .weight(2.0))
                                .functions(fn -> fn.fieldValueFactor(fvf -> fvf.field("view_count")
                                        .modifier(FieldValueFactorModifier.Log1p))
                                        .weight(1.0))
                                .boostMode(FunctionBoostMode.Sum)
                        ))
                        // 返回 title/body 高亮片段，后续合并为 snippet
                        .highlight(h -> h
                                .fields(Map.of(
                                        "title", new HighlightField.Builder().build(),
                                        "body", new HighlightField.Builder().build()
                                ))
                        )
                        .sort(sorts);
                // 游标分页：携带上一次最后命中的 sort 值
                if (afterValues != null && !afterValues.isEmpty()) {
                    b = b.searchAfter(afterValues);
                }

                return b;
            }, (Class<Map<String, Object>>) (Class<?>) Map.class);
        } catch (Exception e) {
            // ES 失败：仅返回已组装的商业位（若有），不提供 organic 分页
            return new SearchResponse(items, null, false);
        }

        List<Hit<Map<String, Object>>> hits = resp.hits() == null ? Collections.emptyList() : resp.hits().hits();
        List<FieldValue> lastAcceptedSort = null;
        boolean moreAvailable = false;
        for (Hit<Map<String, Object>> hit : hits) {
            FeedItemResponse item = mapHit(hit, currentUserIdNullable);
            if (item == null || !seen.add(item.id())) {
                // 无效命中或与 promoted/已接受 organic 重复：不计入 organic，也不算“还有更多”
                continue;
            }
            if (items.size() < safeSize) {
                items.add(item);
                lastAcceptedSort = hit.sort();
            } else {
                // 命中可接受但页已满 → 确有更多 organic
                moreAvailable = true;
                break;
            }
        }

        // nextAfter/hasMore 只按实际接受的 organic 子序列计算：promoted-overlap/重复/无效命中不污染 hasMore
        boolean hasMore = lastAcceptedSort != null && moreAvailable;
        return new SearchResponse(items, encodeSort(lastAcceptedSort), hasMore);
    }

    /**
     * 首屏插入 search 商业位：取传入 allocation 前 {@code limit} 条、hydrate 帖子并标记 promoted。
     * allocation 由调用方预先读取（同时用于 organic 排除），避免重复读缓存。
     */
    private void appendPromotedSearch(List<FeedItemResponse> items, Set<String> seen, Long currentUserIdNullable,
                                      int limit, List<PromotionAllocationView> allocations) {
        if (limit <= 0 || allocations.isEmpty()) {
            return;
        }
        List<Long> ids = allocations.stream().limit(limit).map(PromotionAllocationView::postIdAsLong).toList();
        Map<Long, PromotionAllocationView> byId = allocations.stream()
                .collect(Collectors.toMap(PromotionAllocationView::postIdAsLong, Function.identity(), (left, right) -> left));
        for (FeedItemResponse item : knowPostFeedService.getFeedByIds(ids, currentUserIdNullable, KnowPostFeedService.FeedVisibilityScope.PUBLIC)) {
            if (items.size() >= limit) {
                break;
            }
            PromotionAllocationView allocation = byId.get(Long.parseLong(item.id()));
            if (allocation != null && seen.add(item.id())) {
                items.add(item.withPromotion(allocation.placementType(), allocation.promotionCampaignId(),
                        allocation.auctionWindowId()));
            }
        }
    }

    private List<SortOptions> buildSorts() {
        List<SortOptions> sorts = new ArrayList<>();
        sorts.add(SortOptions.of(s -> s.score(o -> o.order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("publish_time").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("like_count").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("view_count").order(SortOrder.Desc))));
        sorts.add(SortOptions.of(s -> s.field(f -> f.field("content_id").order(SortOrder.Desc))));
        return sorts;
    }

    private FeedItemResponse mapHit(Hit<Map<String, Object>> hit, Long currentUserIdNullable) {
        Map<String, Object> source = hit.source();
        if (source == null) {
            return null;
        }
        String id = asString(source.get("content_id"));
        String title = asString(source.get("title"));
        String descriptionFromDoc = asString(source.get("description"));
        String snippet = buildSnippet(hit);
        String description = (snippet != null && !snippet.isBlank()) ? snippet : descriptionFromDoc;
        List<String> tagList = asStringList(source.get("tags"));
        List<String> imgs = asStringList(source.get("img_urls"));
        String cover = imgs.isEmpty() ? null : imgs.getFirst();
        String authorAvatar = asString(source.get("author_avatar"));
        String authorNickname = asString(source.get("author_nickname"));
        String tagJson = asString(source.get("author_tag_json"));
        Long likeCount = asLong(source.get("like_count"));
        Long favoriteCount = asLong(source.get("favorite_count"));
        Boolean liked = currentUserIdNullable != null && counterService.isLiked("knowpost", id, currentUserIdNullable);
        Boolean faved = currentUserIdNullable != null && counterService.isFaved("knowpost", id, currentUserIdNullable);
        return FeedItemResponse.organic(id, title, description, cover, tagList, authorAvatar, authorNickname,
                tagJson, likeCount, favoriteCount, liked, faved, null);
    }

    private String encodeSort(List<FieldValue> sort) {
        if (sort == null || sort.isEmpty()) {
            return null;
        }
        List<String> parts = sort.stream().map(this::fieldValueToString).collect(Collectors.toList());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(String.join(",", parts).getBytes());
    }

    /**
     * 联想建议：Completion Suggester，取 title_suggest 的候选文本。
     */
    @SuppressWarnings("unchecked")
    public SuggestResponse suggest(String prefix, int size) {
        co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> resp;
        try {
            resp = es.search(s -> s.index(INDEX)
                    .suggest(sug -> sug.suggesters("title_suggest",
                            sc -> sc.prefix(prefix).completion(c -> c.field("title_suggest").size(size))))
                    , (Class<Map<String, Object>>) (Class<?>) Map.class);
        } catch (Exception e) {
            return new SuggestResponse(Collections.emptyList());
        }
        List<String> items = new ArrayList<>();
        try {
            var sugg = resp.suggest();
            List<Suggestion<Map<String, Object>>> entry = sugg == null ? null : sugg.get("title_suggest");
            if (entry != null) {
                for (var s : entry) {
                    var comp = s.completion();
                    if (comp != null && comp.options() != null) {
                        for (var opt : comp.options()) {
                            String text = opt.text();
                            if (text != null && !text.isBlank()) {
                                items.add(text);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return new SuggestResponse(items);
    }

    /**
     * 逗号分隔字符串解析为列表；空字符串返回 null。
     */
    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }

        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>();

        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }

        }
        return out;
    }

    /**
     * 解析 Base64URL 游标为 sort 值数组，按顺序还原各 FieldValue。
     */
    private List<FieldValue> parseAfter(String after) {
        if (after == null || after.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(after));
            String[] parts = decoded.split(",");
            List<FieldValue> out = new ArrayList<>(parts.length);

            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (i == 0) {
                    out.add(FieldValue.of(Double.parseDouble(p)));
                } else if (i == 1) {
                    out.add(FieldValue.of(Long.parseLong(p)));
                } else {
                    out.add(FieldValue.of(Long.parseLong(p)));
                }
            }

            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 合并高亮片段为 snippet（标题片段在前，正文片段在后）。
     */
    private String buildSnippet(Hit<Map<String, Object>> hit) {
        StringBuilder sb = new StringBuilder();

        if (hit.highlight() != null) {
            List<String> ht = hit.highlight().get("title");
            if (ht != null && !ht.isEmpty()) {
                sb.append(String.join(" ", ht));
            }

            List<String> hb = hit.highlight().get("body");
            if (hb != null && !hb.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(String.join(" ", hb));
            }
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 将 sort 的 FieldValue 安全转换为字符串，便于编码游标。
     */
    private String fieldValueToString(FieldValue fv) {
        if (fv.isDouble()) {
            return String.valueOf(fv.doubleValue());
        }
        if (fv.isLong()) {
            return String.valueOf(fv.longValue());
        }
        if (fv.isString()) {
            return fv.stringValue();
        }
        if (fv.isBoolean()) {
            return String.valueOf(fv.booleanValue());
        }

        return String.valueOf(fv._get());
    }

    /**
     * 任意对象转字符串，null 保护。
     */
    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 任意对象转 Long（Number 直接转换，字符串容错解析）。
     */
    private Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean asBoolean(Object o) {
        switch (o) {
            case null -> {
                return null;
            }
            case Boolean b -> {
                return b;
            }
            case Number n -> {
                return n.intValue() != 0;
            }
            default -> {
            }
        }

        String s = String.valueOf(o).toLowerCase();
        if ("true".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 任意对象转 List<String>（支持原生 List 与简单 JSON 数组字符串）。
     */
    private List<String> asStringList(Object o) {
        if (o == null) {
            return Collections.emptyList();
        }

        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object e : l) {
                if (e != null) {
                    out.add(String.valueOf(e));
                }
            }
            return out;
        }

        String s = String.valueOf(o);
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
            if (s.isBlank()) {
                return Collections.emptyList();
            }

            String[] parts = s.split(",");
            List<String> out = new ArrayList<>();
            for (String p : parts) {
                String t = p.trim();
                if (t.startsWith("\"") && t.endsWith("\"")) {
                    t = t.substring(1, t.length() - 1);
                }

                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
        return Collections.emptyList();
    }
}

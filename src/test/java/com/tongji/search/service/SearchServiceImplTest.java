package com.tongji.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.ObjectBuilder;
import com.tongji.counter.service.CounterService;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.search.api.dto.SearchResponse;
import com.tongji.search.service.impl.SearchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private ElasticsearchClient es;

    @Mock
    private CounterService counterService;

    @Mock
    private KnowPostFeedService knowPostFeedService;

    @Mock
    private PromotionAllocationService promotionAllocationService;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SearchServiceImpl(es, counterService, knowPostFeedService, promotionAllocationService);
    }

    @Test
    void prependsPromotedSearchItemAndDedupesOrganicHit() throws Exception {
        when(promotionAllocationService.getActiveSearchAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "search_top_slot", "301", "401")));
        when(knowPostFeedService.getFeedByIds(eq(List.of(201L)), eq(42L),
                eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC))).thenReturn(List.of(feedItem("201")));
        stubEs("201", "202");

        SearchResponse response = service.search("guide", 2, null, null, 42L);

        // promoted 201 在前；organic 命中的 201 被去重，仅保留 202
        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(response.items().getFirst().commercial()).isTrue();
        assertThat(response.items().getFirst().placementType()).isEqualTo("search_top_slot");
        assertThat(response.items().get(1).commercial()).isFalse();
    }

    @Test
    void keepsNextAfterAndHasMoreBasedOnOrganicHitsOnly() throws Exception {
        when(promotionAllocationService.getActiveSearchAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "search_top_slot", "301", "401")));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L),
                eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC))).thenReturn(List.of(feedItem("201")));
        stubEs("202", "203");

        SearchResponse response = service.search("guide", 2, null, null, 42L);

        // 只返回 promoted 201 + organic 202；nextAfter 基于 202（organic 最后返回项），而非多取的 203
        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(response.nextAfter()).isEqualTo(expectedAfter("202"));
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    void skipsPromotedOnNonFirstPage() throws Exception {
        // after != null：不插 promoted，直接走 organic 分页
        stubEs("301", "302");

        SearchResponse response = service.search("guide", 2, null, "cGFnZTI=", 42L);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("301", "302");
        assertThat(response.items()).allSatisfy(item -> assertThat(item.commercial()).isFalse());
    }

    @Test
    void doesNotReportHasMoreWhenPromotedOverlapsOrganicHit() throws Exception {
        // promoted 201 与 organic 命中 201 去重：被吃掉的命中不应把 hasMore 错算成 true
        when(promotionAllocationService.getActiveSearchAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "search_top_slot", "301", "401")));
        when(knowPostFeedService.getFeedByIds(eq(List.of(201L)), eq(42L),
                eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC))).thenReturn(List.of(feedItem("201")));
        stubEs("201", "202");

        SearchResponse response = service.search("guide", 2, null, null, 42L);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextAfter()).isEqualTo(expectedAfter("202"));
    }

    @Test
    void doesNotReportHasMoreOnDuplicateOrganicHits() throws Exception {
        // 非首屏（无 promoted）；ES 返回重复命中，去重后只接受 1 条，不应误报 hasMore
        stubEs("301", "301");

        SearchResponse response = service.search("guide", 2, null, "cGFnZTI=", 42L);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("301");
        assertThat(response.hasMore()).isFalse();
    }

    @Test
    void organicQueryExcludesCurrentlyPromotedPostIds() throws Exception {
        // promoted 帖必须从 organic 查询排除，杜绝其在后续页以 organic 形式重复出现
        when(promotionAllocationService.getActiveSearchAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "search_top_slot", "301", "401")));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L),
                eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC))).thenReturn(List.of(feedItem("201")));
        stubEs("202", "203");

        service.search("guide", 2, null, null, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor = ArgumentCaptor.forClass(Function.class);
        verify(es).search(captor.capture(), any());
        SearchRequest req = captor.getValue().apply(new SearchRequest.Builder()).build();

        // organic bool 的 mustNot 必含 content_id terms 且值含 promoted 帖 201（content_id 为 long）
        List<co.elastic.clients.elasticsearch._types.query_dsl.Query> mustNot =
                req.query().functionScore().query().bool().mustNot();
        assertThat(mustNot).anySatisfy(q -> {
            assertThat(q.isTerms()).isTrue();
            assertThat(q.terms().field()).isEqualTo("content_id");
            List<Long> excluded = q.terms().terms().value().stream().map(FieldValue::longValue).toList();
            assertThat(excluded).contains(201L);
        });
    }

    @Test
    void reportsHasMoreWhenSkippedHitsPrecedeAcceptableOrganic() throws Exception {
        // 非首屏；ES 前几条为无效命中（被丢弃），其后仍有可接受 organic 且还有更多 → hasMore 仍应为 true。
        // 关键：若 lookahead 预算只够 organicNeed+1，被丢弃的命中会吃掉预算导致 false negative。
        stubEs(null, null, "301", "302", "303");

        SearchResponse response = service.search("guide", 2, null, "cGFnZTI=", 42L);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("301", "302");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextAfter()).isEqualTo(expectedAfter("302"));
    }

    private FeedItemResponse feedItem(String id) {
        return FeedItemResponse.organic(id, "title-" + id, "desc-" + id, null, List.of(),
                null, "author", null, 0L, 0L, false, false, null);
    }

    /**
     * 绑定 ElasticsearchClient.search(Function, Type) 重载，返回伪 ES 响应；
     * 关键：按请求里的 size 切片（模拟真实 ES 尊重 size），使 lookahead 预算行为可测。
     * ids 中 null 表示无效命中（无 _source，mapHit 返回 null 被丢弃）。
     */
    @SuppressWarnings("unchecked")
    private void stubEs(String... ids) throws Exception {
        List<Hit<Map<String, Object>>> all = new ArrayList<>();
        for (String id : ids) {
            all.add(id == null ? invalidHit() : validHit(id));
        }
        when(es.<Map<String, Object>>search(
                org.mockito.ArgumentMatchers.<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
                org.mockito.ArgumentMatchers.<Class<Map<String, Object>>>any()))
                .thenAnswer(inv -> {
                    Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> fn = inv.getArgument(0);
                    SearchRequest req = fn.apply(new SearchRequest.Builder()).build();
                    int size = req.size() == null ? all.size() : Math.min(req.size(), all.size());
                    return esResponse(all.subList(0, size));
                });
    }

    private Hit<Map<String, Object>> validHit(String id) {
        long lid = Long.parseLong(id);
        Map<String, Object> source = new HashMap<>();
        source.put("content_id", id);
        source.put("title", "title-" + id);
        return Hit.of(h -> h.index("zhiguang_content_index").id(id).source(source).sort(sortOf(lid)));
    }

    private Hit<Map<String, Object>> invalidHit() {
        return Hit.of(h -> h.index("zhiguang_content_index").id("invalid").sort(sortOf(0L)));
    }

    private List<FieldValue> sortOf(long lid) {
        return List.of(FieldValue.of(1.0), FieldValue.of(1000L), FieldValue.of(5L), FieldValue.of(10L), FieldValue.of(lid));
    }

    @SuppressWarnings("unchecked")
    private co.elastic.clients.elasticsearch.core.SearchResponse<Map<String, Object>> esResponse(List<Hit<Map<String, Object>>> hits) {
        return co.elastic.clients.elasticsearch.core.SearchResponse.of(r -> r
                .took(1L)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).skipped(0).failed(0))
                .hits(h -> h.total(t -> t.value((long) hits.size())
                        .relation(co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq)).hits(hits)));
    }

    private String expectedAfter(String id) {
        String joined = "1.0,1000,5,10," + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(joined.getBytes());
    }
}

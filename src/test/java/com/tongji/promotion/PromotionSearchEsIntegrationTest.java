package com.tongji.promotion;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.tongji.counter.service.CounterService;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.search.api.dto.SearchResponse;
import com.tongji.search.service.impl.SearchServiceImpl;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * search 商业位 ES 集成测试：对真 ES 验证 promoted 帖被 must_not 从 organic 排除（跨页不再以 organic 再现）。
 * <p>索引用标准分词器的最小 mapping（生产用 IK，此处与排除逻辑无关）；无 ES 时通过 {@code @EnabledIf} 跳过。</p>
 */
@SpringBootTest(classes = PromotionSearchEsIntegrationTest.TestConfig.class)
@EnabledIf("esReachable")
class PromotionSearchEsIntegrationTest {

    private static final String INDEX = "zhiguang_content_index";

    @Autowired
    private ElasticsearchClient es;

    @Autowired
    private SearchServiceImpl searchService;

    @Autowired
    private KnowPostFeedService knowPostFeedService;

    @Autowired
    private PromotionAllocationService promotionAllocationService;

    @BeforeEach
    void setUpIndex() throws Exception {
        try {
            es.indices().delete(d -> d.index(INDEX));
        } catch (Exception ignored) {
        }
        es.indices().create(c -> c.index(INDEX).mappings(m -> m
                .properties("content_id", Property.of(p -> p.long_(b -> b)))
                .properties("title", Property.of(p -> p.text(b -> b)))
                .properties("body", Property.of(p -> p.text(b -> b)))
                .properties("description", Property.of(p -> p.text(b -> b)))
                .properties("tags", Property.of(p -> p.keyword(b -> b)))
                .properties("author_avatar", Property.of(p -> p.keyword(b -> b)))
                .properties("author_nickname", Property.of(p -> p.keyword(b -> b)))
                .properties("author_tag_json", Property.of(p -> p.keyword(b -> b)))
                .properties("publish_time", Property.of(p -> p.date(b -> b)))
                .properties("like_count", Property.of(p -> p.integer(b -> b)))
                .properties("favorite_count", Property.of(p -> p.integer(b -> b)))
                .properties("view_count", Property.of(p -> p.integer(b -> b)))
                .properties("status", Property.of(p -> p.keyword(b -> b)))
                .properties("img_urls", Property.of(p -> p.keyword(b -> b)))
        ));
        indexDoc(201L, 100, 50, "2026-06-20T10:00:00Z", "search guide one");
        indexDoc(202L, 50, 30, "2026-06-20T09:00:00Z", "search guide two");
        indexDoc(203L, 10, 5, "2026-06-20T08:00:00Z", "search guide three");
        es.indices().refresh(r -> r.index(INDEX));
    }

    @AfterEach
    void tearDownIndex() throws Exception {
        try {
            es.indices().delete(d -> d.index(INDEX));
        } catch (Exception ignored) {
        }
    }

    @Test
    void promotedPostIsExcludedFromOrganicOnRealEs() throws Exception {
        when(promotionAllocationService.getActiveSearchAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "search_top_slot", "301", "401")));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L),
                eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC))).thenReturn(List.of(feedItem("201")));

        SearchResponse response = searchService.search("search guide", 2, null, null, 42L);

        // promoted 201 置顶；organic 仅 202（203 作为多取的探测命中）
        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(response.items().get(0).commercial()).isTrue();
        assertThat(response.items().get(0).placementType()).isEqualTo("search_top_slot");
        assertThat(response.items().get(1).commercial()).isFalse();
        // 关键：hasMore=true 证明 201 被真 ES 的 must_not 排除（否则 201 占用 organic 槽，203 不可见 → hasMore=false）
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    void promotedPostStaysExcludedOnSecondPageOnRealEs() throws Exception {
        when(promotionAllocationService.getActiveSearchAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "search_top_slot", "301", "401")));
        when(knowPostFeedService.getFeedByIds(anyList(), eq(42L),
                eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC))).thenReturn(List.of(feedItem("201")));

        SearchResponse first = searchService.search("search guide", 2, null, null, 42L);
        assertThat(first.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(first.hasMore()).isTrue();

        // 第二页：after != null 不插 promoted，但仍排除 promoted 帖 201；201 在两页 organic 中都不出现
        SearchResponse second = searchService.search("search guide", 2, null, first.nextAfter(), 42L);
        assertThat(second.items()).extracting(FeedItemResponse::id).containsExactly("203");
        assertThat(second.items().get(0).commercial()).isFalse();
    }

    private void indexDoc(long contentId, int like, int view, String publishTime, String title) throws Exception {
        Map<String, Object> doc = new HashMap<>();
        doc.put("content_id", contentId);
        doc.put("title", title);
        doc.put("body", title);
        doc.put("status", "published");
        doc.put("publish_time", publishTime);
        doc.put("like_count", like);
        doc.put("view_count", view);
        doc.put("favorite_count", 0);
        es.index(i -> i.index(INDEX).id(String.valueOf(contentId)).document(doc));
    }

    private FeedItemResponse feedItem(String id) {
        return FeedItemResponse.organic(id, "title-" + id, "desc-" + id, null, List.of(),
                null, "author", null, 0L, 0L, false, false, null);
    }

    static boolean esReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 9200), 1000);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Configuration
    static class TestConfig {
        @Bean(destroyMethod = "close")
        RestClient restClient() {
            return RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")).build();
        }

        @Bean(destroyMethod = "close")
        RestClientTransport restClientTransport(RestClient restClient) {
            return new RestClientTransport(restClient, new JacksonJsonpMapper());
        }

        @Bean
        ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
            return new ElasticsearchClient(transport);
        }

        @Bean
        CounterService counterService() {
            return mock(CounterService.class);
        }

        @Bean
        KnowPostFeedService knowPostFeedService() {
            return mock(KnowPostFeedService.class);
        }

        @Bean
        PromotionAllocationService promotionAllocationService() {
            return mock(PromotionAllocationService.class);
        }

        @Bean
        SearchServiceImpl searchServiceImpl(ElasticsearchClient es, CounterService counterService,
                                            KnowPostFeedService knowPostFeedService,
                                            PromotionAllocationService promotionAllocationService) {
            return new SearchServiceImpl(es, counterService, knowPostFeedService, promotionAllocationService);
        }
    }
}

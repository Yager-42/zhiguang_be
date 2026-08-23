package com.tongji.recommendation.gorse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GorseClientTest {

    private MockRestServiceServer server;
    private GorseClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        GorseProperties properties = new GorseProperties();
        properties.setEndpoint("http://localhost:8087");
        properties.setApiKey("secret");
        properties.setItemToItemName("similar_topics");
        client = new GorseClient(restTemplate, properties);
    }

    @Test
    void relatedReadsScoredItemIds() {
        server.expect(requestTo("http://localhost:8087/api/item-to-item/similar_topics/101?n=3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "secret"))
                .andRespond(withSuccess("[{\"Id\":\"202\",\"Score\":0.8}]", MediaType.APPLICATION_JSON));

        assertThat(client.related(101L, 3)).containsExactly("202");
        server.verify();
    }

    @Test
    void trendingReadsConfiguredNonPersonalizedRanking() {
        server.expect(requestTo("http://localhost:8087/api/non-personalized/trending?offset=20&n=3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "secret"))
                .andRespond(withSuccess("[{\"Id\":\"202\",\"Score\":18.5}]", MediaType.APPLICATION_JSON));

        assertThat(client.trending(20, 3)).containsExactly("202");
        server.verify();
    }

    @Test
    void feedbackUsesArrayBodyRequiredByGorseApi() {
        server.expect(requestTo("http://localhost:8087/api/feedback"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$[0].FeedbackType").value("like"))
                .andExpect(jsonPath("$[0].UserId").value("7"))
                .andExpect(jsonPath("$[0].ItemId").value("101"))
                .andExpect(jsonPath("$[0].Value").value(1))
                .andRespond(withSuccess());

        client.insertFeedback("like", 7L, "101");
        server.verify();
    }

    @Test
    void newItemIncludesTopicsAndTitle() {
        server.expect(requestTo("http://localhost:8087/api/item/101"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound());
        server.expect(requestTo("http://localhost:8087/api/item"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.Labels.topics[0]").value("Java"))
                .andExpect(jsonPath("$.Labels.author_id").value("7"))
                .andExpect(jsonPath("$.Comment").value("测试知文"))
                .andRespond(withSuccess());

        client.upsertItem(new GorseItemInput(
                101L,
                7L,
                Instant.parse("2026-06-18T10:15:30Z"),
                "测试知文",
                List.of("Java")
        ));
        server.verify();
    }

    @Test
    void existingItemIsUpdatedWithPatch() {
        server.expect(requestTo("http://localhost:8087/api/item/101"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8087/api/item/101"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.Labels.topics[0]").value("Java"))
                .andExpect(jsonPath("$.Comment").value("更新后的知文"))
                .andRespond(withSuccess());

        client.upsertItem(new GorseItemInput(
                101L,
                7L,
                Instant.parse("2026-06-18T10:15:30Z"),
                "更新后的知文",
                List.of("Java")
        ));
        server.verify();
    }
}

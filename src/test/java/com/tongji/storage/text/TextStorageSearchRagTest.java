package com.tongji.storage.text;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.Endpoint;
import co.elastic.clients.transport.TransportOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.config.EsProperties;
import com.tongji.counter.service.CounterService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.search.index.SearchIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class TextStorageSearchRagTest {

    @Test
    void searchIndexUsesCassandraBodyBeforeDescriptionFallback() {
        RecordingElasticsearchTransport transport = new RecordingElasticsearchTransport();
        CountingTextStorageService textStorage = new CountingTextStorageService(Optional.of("body from cassandra"));
        SearchIndexService service = new SearchIndexService(
                new ElasticsearchClient(transport),
                mapperReturning(baseRow("description fallback")),
                counterService(Map.of("like", 7L, "fav", 3L)),
                new ObjectMapper(),
                textStorage
        );

        service.upsertKnowPost(101L);

        assertThat(textStorage.lastPostId).isEqualTo(101L);
        assertThat(textStorage.lastFallbackUrl).isEqualTo("http://minio/posts/101.md");
        assertThat(transport.indexRequest.document().get("body")).isEqualTo("body from cassandra");
    }

    @Test
    void searchIndexFallsBackToDescriptionWhenStorageBodyBlank() {
        RecordingElasticsearchTransport transport = new RecordingElasticsearchTransport();
        SearchIndexService service = new SearchIndexService(
                new ElasticsearchClient(transport),
                mapperReturning(baseRow("description fallback")),
                counterService(Map.of()),
                new ObjectMapper(),
                new CountingTextStorageService(Optional.of("   "))
        );

        service.upsertKnowPost(101L);

        assertThat(transport.indexRequest.document().get("body")).isEqualTo("description fallback");
    }

    @Test
    void ragIndexUsesCassandraBodyBeforeMinioFallback() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        CountingTextStorageService textStorage = new CountingTextStorageService(Optional.of("# Title\ncassandra body"));
        RagIndexService service = new RagIndexService(
                vectorStore,
                mapperReturning(publishedPublicRow()),
                textStorage,
                new ElasticsearchClient(new RecordingElasticsearchTransport()),
                esProperties("rag-index")
        );

        int count = service.reindexSinglePost(101L);

        assertThat(count).isEqualTo(1);
        assertThat(textStorage.lastPostId).isEqualTo(101L);
        assertThat(textStorage.lastFallbackUrl).isEqualTo("http://minio/posts/101.md");
        assertThat(vectorStore.addedDocs).hasSize(1);
        assertThat(vectorStore.addedDocs.getFirst().getText()).isEqualTo("# Title\ncassandra body\n");
        assertThat(vectorStore.addedDocs.getFirst().getMetadata())
                .containsEntry("postId", "101")
                .containsEntry("contentUrl", "http://minio/posts/101.md");
    }

    @Test
    void ragIndexAllowsMinioFallbackThroughTextStorageService() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        RagIndexService service = new RagIndexService(
                vectorStore,
                mapperReturning(publishedPublicRow()),
                new CountingTextStorageService(Optional.of("# Title\nfrom minio fallback")),
                new ElasticsearchClient(new RecordingElasticsearchTransport()),
                esProperties("rag-index")
        );

        int count = service.reindexSinglePost(101L);

        assertThat(count).isEqualTo(1);
        assertThat(vectorStore.addedDocs).hasSize(1);
        assertThat(vectorStore.addedDocs.getFirst().getText()).contains("from minio fallback");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void ragIndexReadsCassandraTextWhenContentUrlMissing(String contentUrl) {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        CountingTextStorageService textStorage = new CountingTextStorageService(Optional.of("# Title\ncassandra body"));
        KnowPostDetailRow row = publishedPublicRow();
        row.setContentUrl(contentUrl);
        RagIndexService service = new RagIndexService(
                vectorStore,
                mapperReturning(row),
                textStorage,
                new ElasticsearchClient(new RecordingElasticsearchTransport()),
                esProperties("rag-index")
        );

        int count = service.reindexSinglePost(101L);

        assertThat(count).isEqualTo(1);
        assertThat(textStorage.lastPostId).isEqualTo(101L);
        assertThat(textStorage.lastFallbackUrl).isEqualTo(contentUrl);
        assertThat(vectorStore.addedDocs).hasSize(1);
        assertThat(vectorStore.addedDocs.getFirst().getText()).isEqualTo("# Title\ncassandra body\n");
    }

    @Test
    void ragIndexSkipsWhenStorageReturnsBlank() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        RagIndexService service = new RagIndexService(
                vectorStore,
                mapperReturning(publishedPublicRow()),
                new CountingTextStorageService(Optional.of(" ")),
                new ElasticsearchClient(new RecordingElasticsearchTransport()),
                esProperties("rag-index")
        );

        int count = service.reindexSinglePost(101L);

        assertThat(count).isZero();
        assertThat(vectorStore.addedDocs).isEmpty();
    }

    private KnowPostMapper mapperReturning(KnowPostDetailRow row) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("findDetailById".equals(method.getName())) {
                return row;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType.equals(boolean.class)) {
                return false;
            }
            if (returnType.equals(int.class) || returnType.equals(long.class)) {
                return 0;
            }
            return null;
        };
        return (KnowPostMapper) Proxy.newProxyInstance(
                KnowPostMapper.class.getClassLoader(),
                new Class<?>[]{KnowPostMapper.class},
                handler
        );
    }

    private CounterService counterService(Map<String, Long> counts) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getCounts".equals(method.getName())) {
                return counts;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType.equals(boolean.class)) {
                return false;
            }
            if (returnType.equals(Map.class)) {
                return Map.of();
            }
            return null;
        };
        return (CounterService) Proxy.newProxyInstance(
                CounterService.class.getClassLoader(),
                new Class<?>[]{CounterService.class},
                handler
        );
    }

    private KnowPostDetailRow baseRow(String description) {
        KnowPostDetailRow row = new KnowPostDetailRow();
        row.setId(101L);
        row.setCreatorId(11L);
        row.setType("knowpost");
        row.setTitle("Indexed title");
        row.setDescription(description);
        row.setContentUrl("http://minio/posts/101.md");
        row.setStatus("published");
        return row;
    }

    private KnowPostDetailRow publishedPublicRow() {
        KnowPostDetailRow row = baseRow("description fallback");
        row.setVisible("public");
        row.setContentSha256("sha-101");
        row.setContentEtag("etag-101");
        return row;
    }

    private EsProperties esProperties(String indexName) {
        EsProperties props = new EsProperties();
        props.setIndex(indexName);
        return props;
    }

    private IndexResponse indexResponse() {
        return IndexResponse.of(b -> b
                .id("101")
                .index("zhiguang_content_index")
                .version(1)
                .shards(shards())
                .result(Result.Created)
        );
    }

    private SearchResponse<Map> emptySearchResponse() {
        return SearchResponse.<Map>of(b -> b
                .took(0)
                .timedOut(false)
                .shards(shards())
                .hits(HitsMetadata.<Map>of(h -> h.hits(List.of())))
        );
    }

    private DeleteByQueryResponse deleteResponse() {
        return DeleteByQueryResponse.of(b -> b
                .deleted(0L)
                .batches(1L)
                .failures(List.of())
                .noops(0L)
                .requestsPerSecond(0f)
                .retries(r -> r.bulk(0L).search(0L))
                .throttledMillis(0L)
                .throttledUntilMillis(0L)
                .timedOut(false)
                .took(0L)
                .total(0L)
        );
    }

    private ShardStatistics shards() {
        return ShardStatistics.of(b -> b
                .failed(0)
                .successful(1)
                .total(1)
        );
    }

    private static final class CountingTextStorageService implements TextStorageService {
        private final Optional<String> postText;
        private long lastPostId;
        private String lastFallbackUrl;

        private CountingTextStorageService(Optional<String> postText) {
            this.postText = postText;
        }

        @Override
        public void savePostText(long postId, String body, String sha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> getPostText(long postId, String fallbackContentUrl) {
            lastPostId = postId;
            lastFallbackUrl = fallbackContentUrl;
            return postText;
        }

        @Override
        public void saveCommentText(long commentId, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<Long, String> getCommentTexts(Collection<Long> commentIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deletePostText(long postId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteCommentText(long commentId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingVectorStore implements VectorStore {
        private List<Document> addedDocs = List.of();

        @Override
        public void add(List<Document> documents) {
            addedDocs = List.copyOf(documents);
        }

        @Override
        public void delete(List<String> idList) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private final class RecordingElasticsearchTransport implements ElasticsearchTransport {
        private final JsonpMapper mapper = new JacksonJsonpMapper();
        private final TransportOptions options = new TransportOptions() {
            @Override
            public Collection<Map.Entry<String, String>> headers() {
                return List.of();
            }

            @Override
            public Map<String, String> queryParameters() {
                return Map.of();
            }

            @Override
            public java.util.function.Function<List<String>, Boolean> onWarnings() {
                return warnings -> true;
            }

            @Override
            public void updateToken(String token) {
            }

            @Override
            public boolean keepResponseBodyOnException() {
                return false;
            }

            @Override
            public Builder toBuilder() {
                throw new UnsupportedOperationException();
            }
        };
        private IndexRequest<Map<String, Object>> indexRequest;

        @Override
        @SuppressWarnings("unchecked")
        public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
                RequestT request,
                Endpoint<RequestT, ResponseT, ErrorT> endpoint,
                TransportOptions transportOptions
        ) throws IOException {
            return switch (endpoint.id()) {
                case "es/index" -> {
                    indexRequest = (IndexRequest<Map<String, Object>>) request;
                    yield (ResponseT) indexResponse();
                }
                case "es/search" -> (ResponseT) emptySearchResponse();
                case "es/delete_by_query" -> (ResponseT) deleteResponse();
                default -> throw new IOException("Unexpected endpoint: " + endpoint.id());
            };
        }

        @Override
        public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
                RequestT request,
                Endpoint<RequestT, ResponseT, ErrorT> endpoint,
                TransportOptions transportOptions
        ) {
            try {
                return CompletableFuture.completedFuture(performRequest(request, endpoint, transportOptions));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public JsonpMapper jsonpMapper() {
            return mapper;
        }

        @Override
        public TransportOptions options() {
            return options;
        }

        @Override
        public void close() {
        }
    }
}

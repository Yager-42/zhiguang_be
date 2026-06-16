package com.tongji.storage.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CassandraTextStorageServiceTest {

    @Mock
    private PostTextRepository postTextRepository;

    @Mock
    private CommentTextRepository commentTextRepository;

    @Test
    void savePostTextCreatesNewRowWithVersionOne() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(postTextRepository.findById(101L)).thenReturn(Optional.empty());

        service.savePostText(101L, "body-v1", "sha-a");

        ArgumentCaptor<PostText> captor = ArgumentCaptor.forClass(PostText.class);
        verify(postTextRepository).save(captor.capture());
        PostText saved = captor.getValue();
        assertThat(saved.getPostId()).isEqualTo(101L);
        assertThat(saved.getBody()).isEqualTo("body-v1");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getSha256()).isEqualTo("sha-a");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void savePostTextOverwriteIncrementsVersion() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        PostText existing = new PostText(101L, "old", 4, "sha-old", Instant.parse("2026-01-01T00:00:00Z"));
        when(postTextRepository.findById(101L)).thenReturn(Optional.of(existing));

        service.savePostText(101L, "body-v2", "sha-new");

        ArgumentCaptor<PostText> captor = ArgumentCaptor.forClass(PostText.class);
        verify(postTextRepository).save(captor.capture());
        PostText saved = captor.getValue();
        assertThat(saved.getVersion()).isEqualTo(5);
        assertThat(saved.getBody()).isEqualTo("body-v2");
        assertThat(saved.getSha256()).isEqualTo("sha-new");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void savePostTextWrapsWriteFailures() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(postTextRepository.findById(101L)).thenReturn(Optional.empty());
        when(postTextRepository.save(any(PostText.class))).thenThrow(new RuntimeException("write failed"));

        assertThatThrownBy(() -> service.savePostText(101L, "body", "sha"))
                .isInstanceOf(TextWriteException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void getPostTextReturnsCassandraBodyFirst() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(postTextRepository.findById(101L))
                .thenReturn(Optional.of(new PostText(101L, "from-cassandra", 2, "sha", Instant.now())));

        Optional<String> result = service.getPostText(101L, "http://minio/posts/101.txt");

        assertThat(result).contains("from-cassandra");
        assertThat(restTemplate.getInvocationCount()).isZero();
    }

    @Test
    void getPostTextFallsBackToLegacyMinioUrlWhenCassandraMisses() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(postTextRepository.findById(101L)).thenReturn(Optional.empty());
        restTemplate.setResponseBody("from-minio");

        Optional<String> result = service.getPostText(101L, "http://minio/posts/101.txt");

        assertThat(result).contains("from-minio");
        assertThat(restTemplate.getInvocationCount()).isEqualTo(1);
    }

    @Test
    void getPostTextReturnsEmptyWhenFallbackIsMissing() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(postTextRepository.findById(101L)).thenReturn(Optional.empty());
        restTemplate.setException(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "not found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        ));

        Optional<String> result = service.getPostText(101L, "http://minio/posts/101.txt");

        assertThat(result).isEmpty();
        assertThat(restTemplate.getInvocationCount()).isEqualTo(1);
    }

    @Test
    void getPostTextWrapsCassandraReadFailures() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(postTextRepository.findById(101L)).thenThrow(new RuntimeException("read failed"));

        assertThatThrownBy(() -> service.getPostText(101L, "http://minio/posts/101.txt"))
                .isInstanceOf(TextReadException.class)
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(restTemplate.getInvocationCount()).isZero();
    }

    @Test
    void getPostTextWrapsNon404FallbackFailures() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(postTextRepository.findById(101L)).thenReturn(Optional.empty());
        restTemplate.setException(HttpClientErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "server error",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        ));

        assertThatThrownBy(() -> service.getPostText(101L, "http://minio/posts/101.txt"))
                .isInstanceOf(TextReadException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class);
        assertThat(restTemplate.getInvocationCount()).isEqualTo(1);
    }

    @Test
    void saveCommentTextCreatesNewRowWithVersionOne() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(commentTextRepository.findById(201L)).thenReturn(Optional.empty());

        service.saveCommentText(201L, "comment-v1");

        ArgumentCaptor<CommentText> captor = ArgumentCaptor.forClass(CommentText.class);
        verify(commentTextRepository).save(captor.capture());
        CommentText saved = captor.getValue();
        assertThat(saved.getCommentId()).isEqualTo(201L);
        assertThat(saved.getBody()).isEqualTo("comment-v1");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void saveCommentTextOverwriteIncrementsVersion() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        CommentText existing = new CommentText(201L, "old", 8, Instant.parse("2026-01-01T00:00:00Z"));
        when(commentTextRepository.findById(201L)).thenReturn(Optional.of(existing));

        service.saveCommentText(201L, "comment-v2");

        ArgumentCaptor<CommentText> captor = ArgumentCaptor.forClass(CommentText.class);
        verify(commentTextRepository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(9);
        assertThat(captor.getValue().getBody()).isEqualTo("comment-v2");
    }

    @Test
    void saveCommentTextWrapsWriteFailures() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(commentTextRepository.findById(201L)).thenReturn(Optional.empty());
        when(commentTextRepository.save(any(CommentText.class))).thenThrow(new RuntimeException("write failed"));

        assertThatThrownBy(() -> service.saveCommentText(201L, "comment"))
                .isInstanceOf(TextWriteException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void getCommentTextsReturnsOnlyExistingRows() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        when(commentTextRepository.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(
                new CommentText(1L, "one", 1, Instant.now()),
                new CommentText(3L, "three", 2, Instant.now())
        ));

        Map<Long, String> result = service.getCommentTexts(List.of(1L, 2L, 3L));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, "one",
                3L, "three"
        ));
        assertThat(result).doesNotContainKey(2L);
    }

    @Test
    void deletePostTextDeletesById() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );

        service.deletePostText(101L);

        verify(postTextRepository).deleteById(101L);
    }

    @Test
    void deletePostTextWrapsDeleteFailures() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        org.mockito.Mockito.doThrow(new RuntimeException("delete failed"))
                .when(postTextRepository)
                .deleteById(101L);

        assertThatThrownBy(() -> service.deletePostText(101L))
                .isInstanceOf(TextWriteException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void deleteCommentTextDeletesById() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );

        service.deleteCommentText(201L);

        verify(commentTextRepository).deleteById(201L);
    }

    @Test
    void deleteCommentTextWrapsDeleteFailures() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        CassandraTextStorageService service = new CassandraTextStorageService(
                postTextRepository,
                commentTextRepository,
                restTemplate
        );
        org.mockito.Mockito.doThrow(new RuntimeException("delete failed"))
                .when(commentTextRepository)
                .deleteById(201L);

        assertThatThrownBy(() -> service.deleteCommentText(201L))
                .isInstanceOf(TextWriteException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    private static final class StubRestTemplate extends RestTemplate {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private String responseBody;
        private RuntimeException exception;

        void setResponseBody(String responseBody) {
            this.responseBody = responseBody;
        }

        void setException(RuntimeException exception) {
            this.exception = exception;
        }

        int getInvocationCount() {
            return invocationCount.get();
        }

        @Override
        public <T> T getForObject(String url, Class<T> responseType, Object... uriVariables) throws RestClientException {
            invocationCount.incrementAndGet();
            if (exception != null) {
                throw exception;
            }
            return responseType.cast(responseBody);
        }

    }
}

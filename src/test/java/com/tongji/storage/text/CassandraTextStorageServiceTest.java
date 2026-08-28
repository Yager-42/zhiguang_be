package com.tongji.storage.text;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CassandraTextStorageServiceTest {

    private static final String SHA256 = "a".repeat(64);

    private PostTextArchiveRepository postTextArchiveRepository;
    private CommentTextRepository commentTextRepository;
    private RestTemplate restTemplate;
    private CqlSession cqlSession;
    private ResultSet resultSet;
    private Row lwtRow;
    private CassandraTextStorageService service;

    @BeforeEach
    void setUp() {
        postTextArchiveRepository = mock(PostTextArchiveRepository.class);
        commentTextRepository = mock(CommentTextRepository.class);
        restTemplate = mock(RestTemplate.class);
        cqlSession = mock(CqlSession.class);
        resultSet = mock(ResultSet.class);
        lwtRow = mock(Row.class);
        when(cqlSession.execute(any(SimpleStatement.class))).thenReturn(resultSet);
        when(resultSet.one()).thenReturn(lwtRow);
        service = new CassandraTextStorageService(
                postTextArchiveRepository,
                commentTextRepository,
                restTemplate,
                cqlSession
        );
    }

    @Test
    void firstArchiveAppliesLwtWithoutRepositoryOverwrite() {
        when(lwtRow.getBoolean("[applied]")).thenReturn(true);

        service.savePostTextIdempotent(101L, "body", SHA256.toUpperCase());

        verify(cqlSession).execute(any(SimpleStatement.class));
        verify(postTextArchiveRepository, never()).save(any());
        verify(postTextArchiveRepository, never()).findById(101L);
    }

    @Test
    void sameDigestReplaySucceedsWithoutOverwrite() {
        when(lwtRow.getBoolean("[applied]")).thenReturn(false);
        when(postTextArchiveRepository.findById(101L)).thenReturn(Optional.of(
                new PostTextArchive(101L, "body", SHA256, Instant.now())));

        service.savePostTextIdempotent(101L, "body", SHA256.toUpperCase());

        verify(postTextArchiveRepository, never()).save(any());
    }

    @Test
    void differentDigestReplayCannotOverwriteArchive() {
        when(lwtRow.getBoolean("[applied]")).thenReturn(false);
        when(postTextArchiveRepository.findById(101L)).thenReturn(Optional.of(
                new PostTextArchive(101L, "old", "b".repeat(64), Instant.now())));

        assertThatThrownBy(() -> service.savePostTextIdempotent(101L, "new", SHA256))
                .isInstanceOf(TextDigestConflictException.class)
                .hasMessageContaining("digest conflict");
        verify(postTextArchiveRepository, never()).save(any());
    }

    @Test
    void malformedDigestIsRejectedBeforeCassandra() {
        assertThatThrownBy(() -> service.savePostTextIdempotent(101L, "body", "not-a-sha"))
                .isInstanceOf(TextWriteException.class);
        verify(cqlSession, never()).execute(any(SimpleStatement.class));
    }

    @Test
    void getPostTextReturnsArchiveBeforeFallback() {
        when(postTextArchiveRepository.findById(101L)).thenReturn(Optional.of(
                new PostTextArchive(101L, "from-cassandra", SHA256, Instant.now())));

        assertThat(service.getPostText(101L, "https://minio/body")).contains("from-cassandra");
        verify(restTemplate, never()).getForObject(any(String.class), any());
    }

    @Test
    void getPostTextUsesFallbackWhenArchiveMissing() {
        when(postTextArchiveRepository.findById(101L)).thenReturn(Optional.empty());
        when(restTemplate.getForObject("https://minio/body", String.class)).thenReturn("from-minio");

        assertThat(service.getPostText(101L, "https://minio/body")).contains("from-minio");
    }

    @Test
    void commentWriteKeepsDeterministicVersion() {
        service.saveCommentText(201L, "comment");

        ArgumentCaptor<CommentText> saved = ArgumentCaptor.forClass(CommentText.class);
        verify(commentTextRepository).save(saved.capture());
        assertThat(saved.getValue().getVersion()).isEqualTo(1);
        assertThat(saved.getValue().getBody()).isEqualTo("comment");
    }

    @Test
    void commentBatchReadReturnsOnlyExistingRows() {
        when(commentTextRepository.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(
                new CommentText(1L, "one", 1, Instant.now()),
                new CommentText(3L, "three", 1, Instant.now())
        ));

        Map<Long, String> result = service.getCommentTexts(List.of(1L, 2L, 3L));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(1L, "one", 3L, "three"));
        assertThat(result).doesNotContainKey(2L);
    }
}

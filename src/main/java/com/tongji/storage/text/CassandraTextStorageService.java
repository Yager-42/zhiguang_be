package com.tongji.storage.text;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 使用 Cassandra 保存不可变正文归档和评论正文。
 *
 * <p>知文正文通过 LWT 首次写入；同摘要重放成功，不同摘要绝不覆盖。</p>
 *
 * @since 2026-08-28
 */
@Service
public class CassandraTextStorageService implements TextStorageService {

    private static final String INSERT_POST_ARCHIVE = """
            INSERT INTO post_text_archive_by_post_id (post_id, body, sha256, archived_at)
            VALUES (?, ?, ?, ?) IF NOT EXISTS
            """;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final PostTextArchiveRepository postTextArchiveRepository;
    private final CommentTextRepository commentTextRepository;
    private final RestTemplate restTemplate;
    private final CqlSession cqlSession;

    public CassandraTextStorageService(PostTextArchiveRepository postTextArchiveRepository,
                                       CommentTextRepository commentTextRepository,
                                       RestTemplate restTemplate,
                                       CqlSession cqlSession) {
        this.postTextArchiveRepository = postTextArchiveRepository;
        this.commentTextRepository = commentTextRepository;
        this.restTemplate = restTemplate;
        this.cqlSession = cqlSession;
    }

    @Override
    public void savePostTextIdempotent(long postId, String body, String sha256) throws TextWriteException {
        if (postId <= 0 || body == null || sha256 == null || !SHA_256.matcher(sha256).matches()) {
            throw new TextWriteException("Invalid post archive input for postId=" + postId);
        }
        String normalizedSha256 = sha256.toLowerCase(Locale.ROOT);
        try {
            ResultSet resultSet = cqlSession.execute(SimpleStatement.newInstance(
                    INSERT_POST_ARCHIVE,
                    postId,
                    body,
                    normalizedSha256,
                    Instant.now()
            ));
            Row result = resultSet.one();
            if (result == null) {
                throw new TextWriteException("Cassandra LWT returned no result for postId=" + postId);
            }
            if (result.getBoolean("[applied]")) {
                return;
            }
            PostTextArchive existing = postTextArchiveRepository.findById(postId)
                    .orElseThrow(() -> new TextWriteException(
                            "Post archive disappeared after LWT conflict for postId=" + postId));
            if (normalizedSha256.equalsIgnoreCase(existing.getSha256())) {
                return;
            }
            throw new TextDigestConflictException(
                    "Post archive digest conflict for postId=" + postId
                            + ", expected=" + normalizedSha256
                            + ", existing=" + existing.getSha256());
        } catch (TextDigestConflictException exception) {
            throw exception;
        } catch (TextWriteException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TextWriteException("Failed to archive post text for postId=" + postId, exception);
        }
    }

    @Override
    public Optional<String> getPostText(long postId, String fallbackContentUrl) throws TextReadException {
        try {
            Optional<PostTextArchive> postText = postTextArchiveRepository.findById(postId);
            if (postText.isPresent()) {
                return postText.map(PostTextArchive::getBody);
            }
        } catch (RuntimeException exception) {
            throw new TextReadException("Failed to read post text from Cassandra for postId=" + postId, exception);
        }

        if (fallbackContentUrl == null || fallbackContentUrl.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(restTemplate.getForObject(fallbackContentUrl, String.class));
        } catch (HttpStatusCodeException exception) {
            if (isMissing(exception.getStatusCode())) {
                return Optional.empty();
            }
            throw new TextReadException("Failed to fetch fallback post text for postId=" + postId, exception);
        } catch (RestClientException exception) {
            throw new TextReadException("Failed to fetch fallback post text for postId=" + postId, exception);
        }
    }

    @Override
    public void saveCommentText(long commentId, String body) throws TextWriteException {
        saveCommentTextIdempotent(commentId, body, LocalDateTime.now());
    }

    @Override
    public void saveCommentTextIdempotent(long commentId, String body, LocalDateTime occurredAt)
            throws TextWriteException {
        try {
            Instant updatedAt = occurredAt.atZone(ZoneId.systemDefault()).toInstant();
            commentTextRepository.save(new CommentText(commentId, body, 1, updatedAt));
        } catch (RuntimeException exception) {
            throw new TextWriteException("Failed to save comment text for commentId=" + commentId, exception);
        }
    }

    @Override
    public Map<Long, String> getCommentTexts(Collection<Long> commentIds) throws TextReadException {
        try {
            Map<Long, String> result = new LinkedHashMap<>();
            for (CommentText commentText : commentTextRepository.findAllById(commentIds)) {
                result.put(commentText.getCommentId(), commentText.getBody());
            }
            return result;
        } catch (RuntimeException exception) {
            throw new TextReadException("Failed to read comment texts", exception);
        }
    }

    @Override
    public void deletePostText(long postId) throws TextWriteException {
        try {
            postTextArchiveRepository.deleteById(postId);
        } catch (RuntimeException exception) {
            throw new TextWriteException("Failed to delete post text for postId=" + postId, exception);
        }
    }

    @Override
    public void deleteCommentText(long commentId) throws TextWriteException {
        try {
            commentTextRepository.deleteById(commentId);
        } catch (RuntimeException exception) {
            throw new TextWriteException("Failed to delete comment text for commentId=" + commentId, exception);
        }
    }

    private boolean isMissing(HttpStatusCode statusCode) {
        return statusCode.value() == 404;
    }
}

package com.tongji.storage.text;

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
import java.util.Map;
import java.util.Optional;

@Service
public class CassandraTextStorageService implements TextStorageService {

    private final PostTextRepository postTextRepository;
    private final CommentTextRepository commentTextRepository;
    private final RestTemplate restTemplate;

    public CassandraTextStorageService(
            PostTextRepository postTextRepository,
            CommentTextRepository commentTextRepository,
            RestTemplate restTemplate
    ) {
        this.postTextRepository = postTextRepository;
        this.commentTextRepository = commentTextRepository;
        this.restTemplate = restTemplate;
    }

    @Override
    public void savePostText(long postId, String body, String sha256) throws TextWriteException {
        try {
            int version = postTextRepository.findById(postId)
                    .map(PostText::getVersion)
                    .orElse(0) + 1;
            postTextRepository.save(new PostText(postId, body, version, sha256, Instant.now()));
        } catch (RuntimeException ex) {
            throw new TextWriteException("Failed to save post text for postId=" + postId, ex);
        }
    }

    @Override
    public Optional<String> getPostText(long postId, String fallbackContentUrl) throws TextReadException {
        try {
            Optional<PostText> postText = postTextRepository.findById(postId);
            if (postText.isPresent()) {
                return postText.map(PostText::getBody);
            }
        } catch (RuntimeException ex) {
            throw new TextReadException("Failed to read post text from Cassandra for postId=" + postId, ex);
        }

        if (fallbackContentUrl == null || fallbackContentUrl.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(restTemplate.getForObject(fallbackContentUrl, String.class));
        } catch (HttpStatusCodeException ex) {
            if (isMissing(ex.getStatusCode())) {
                return Optional.empty();
            }
            throw new TextReadException("Failed to fetch fallback post text for postId=" + postId, ex);
        } catch (RestClientException ex) {
            throw new TextReadException("Failed to fetch fallback post text for postId=" + postId, ex);
        }
    }

    @Override
    public void saveCommentText(long commentId, String body) throws TextWriteException {
        saveCommentTextIdempotent(commentId, body, LocalDateTime.now());
    }

    @Override
    public void saveCommentTextIdempotent(long commentId, String body, LocalDateTime occurredAt) throws TextWriteException {
        try {
            Instant updatedAt = occurredAt.atZone(ZoneId.systemDefault()).toInstant();
            commentTextRepository.save(new CommentText(commentId, body, 1, updatedAt));
        } catch (RuntimeException ex) {
            throw new TextWriteException("Failed to save comment text for commentId=" + commentId, ex);
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
        } catch (RuntimeException ex) {
            throw new TextReadException("Failed to read comment texts", ex);
        }
    }

    @Override
    public void deletePostText(long postId) throws TextWriteException {
        try {
            postTextRepository.deleteById(postId);
        } catch (RuntimeException ex) {
            throw new TextWriteException("Failed to delete post text for postId=" + postId, ex);
        }
    }

    @Override
    public void deleteCommentText(long commentId) throws TextWriteException {
        try {
            commentTextRepository.deleteById(commentId);
        } catch (RuntimeException ex) {
            throw new TextWriteException("Failed to delete comment text for commentId=" + commentId, ex);
        }
    }

    private boolean isMissing(HttpStatusCode statusCode) {
        return statusCode.value() == 404;
    }
}

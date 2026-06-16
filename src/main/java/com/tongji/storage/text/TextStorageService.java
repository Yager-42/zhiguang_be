package com.tongji.storage.text;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface TextStorageService {

    void savePostText(long postId, String body, String sha256) throws TextWriteException;

    Optional<String> getPostText(long postId, String fallbackContentUrl) throws TextReadException;

    void saveCommentText(long commentId, String body) throws TextWriteException;

    Map<Long, String> getCommentTexts(Collection<Long> commentIds) throws TextReadException;

    void deletePostText(long postId) throws TextWriteException;

    void deleteCommentText(long commentId) throws TextWriteException;
}

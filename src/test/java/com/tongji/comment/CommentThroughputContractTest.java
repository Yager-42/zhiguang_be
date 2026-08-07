package com.tongji.comment;

import com.tongji.comment.cache.CommentBaseItem;
import com.tongji.comment.cache.CommentBasePage;
import com.tongji.comment.cache.CommentCacheKeys;
import com.tongji.comment.consumer.CommentWriteConsumer;
import com.tongji.comment.service.impl.CommentMaterializationService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CommentThroughputContractTest {

    @Test
    void cacheKeysSeparatePostRootAndPageSize() {
        assertThat(CommentCacheKeys.postHead(7L, 20)).isEqualTo("comment:idx:post:7:head:20");
        assertThat(CommentCacheKeys.rootHead(7L, 20)).isEqualTo("comment:idx:root:7:head:20");
        assertThat(CommentCacheKeys.postHead(7L, 100)).isNotEqualTo(CommentCacheKeys.postHead(7L, 20));
    }

    @Test
    void sharedCacheTypesContainNoRequestUserState() {
        Set<String> fieldNames = Arrays.stream(CommentBaseItem.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
        fieldNames.addAll(Arrays.stream(CommentBasePage.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet()));

        assertThat(fieldNames).doesNotContain("liked", "userId", "currentUserId");
    }

    @Test
    void onlyMysqlFinalizerOwnsMaterializationTransaction() throws Exception {
        assertThat(CommentMaterializationService.class
                .getMethod("finalizeMaterialization", com.tongji.comment.event.CommentOutboxEvent.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(CommentWriteConsumer.class.getMethod("onMessage", String.class)
                .getAnnotation(Transactional.class)).isNull();
    }
}

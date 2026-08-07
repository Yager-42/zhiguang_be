package com.tongji.comment.event;

import com.tongji.comment.mapper.CommentOutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CommentOutboxCleanerTest {

    @Test
    void deletesAtMostConfiguredBatchOlderThanRetention() {
        CommentOutboxMapper mapper = mock(CommentOutboxMapper.class);
        CommentOutboxCleaner cleaner = new CommentOutboxCleaner(mapper, 24, 1000);
        LocalDateTime before = LocalDateTime.now().minusHours(24).minusSeconds(1);

        cleaner.clean();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).deletePublishedBefore(cutoff.capture(), org.mockito.ArgumentMatchers.eq(1000));
        assertThat(cutoff.getValue()).isAfter(before);
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusHours(24).plusSeconds(1));
    }
}

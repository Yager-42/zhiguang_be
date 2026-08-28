package com.tongji.knowpost.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.publish.PublishAttempt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishManagerImplTest {

    private final PublishAttemptService publishAttemptService = mock(PublishAttemptService.class);
    private final PublishManager manager = new PublishManagerImpl(publishAttemptService);

    @Test
    void acceptPublishReturnsPersistedAttemptId() {
        when(publishAttemptService.acceptPublish(7L, 9L, "publish-key"))
                .thenReturn(PublishAttempt.builder().attemptId(88L).build());

        PublishAcceptedResponse response = manager.acceptPublish(7L, 9L, "publish-key");

        assertThat(response.publishAttemptId()).isEqualTo("88");
        verify(publishAttemptService).acceptPublish(7L, 9L, "publish-key");
    }

    @Test
    void acceptPublishRejectsBlankIdempotencyKeyBeforeCallingService() {
        assertThatThrownBy(() -> manager.acceptPublish(7L, 9L, " "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void retryPublishReturnsSameAttemptIdentity() {
        when(publishAttemptService.retryPublish(7L, 9L, 88L))
                .thenReturn(PublishAttempt.builder().attemptId(88L).runVersion(2).build());

        PublishAcceptedResponse response = manager.retryPublish(7L, 9L, 88L);

        assertThat(response.publishAttemptId()).isEqualTo("88");
    }

    @Test
    void getStatusDelegatesWithoutSchedulingLocalWork() {
        PublishStatusResponse expected = new PublishStatusResponse(
                "88", "publishing", "publishing", null, false);
        when(publishAttemptService.getPublishStatus(7L, 9L, 88L)).thenReturn(expected);

        assertThat(manager.getPublishStatus(7L, 9L, 88L)).isSameAs(expected);
    }
}

package com.tongji.common.id;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class IdNamespaceTest {

    @Test
    void exposesExactlyTheApprovedNamespaceSet() {
        assertThat(Arrays.stream(IdNamespace.values())
                .map(Enum::name))
                .containsExactly(
                        "POST",
                        "COMMENT",
                        "PENDING_COMMENT",
                        "PUBLISH_ATTEMPT",
                        "RELATION",
                        "OUTBOX_EVENT",
                        "NOTIFICATION",
                        "MODERATION_REPORT",
                        "RECONCILIATION_TASK",
                        "ADMIN_OPERATION",
                        "AUDIT_LOG"
                );
    }

    @Test
    void routesNamespacesToExpectedModes() {
        assertThat(IdNamespace.POST.getMode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.COMMENT.getMode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.PENDING_COMMENT.getMode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.PUBLISH_ATTEMPT.getMode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.RELATION.getMode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.OUTBOX_EVENT.getMode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.NOTIFICATION.getMode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.MODERATION_REPORT.getMode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.RECONCILIATION_TASK.getMode()).isEqualTo(IdMode.SEGMENT);
        assertThat(IdNamespace.ADMIN_OPERATION.getMode()).isEqualTo(IdMode.SEGMENT);
        assertThat(IdNamespace.AUDIT_LOG.getMode()).isEqualTo(IdMode.SEGMENT);
    }
}

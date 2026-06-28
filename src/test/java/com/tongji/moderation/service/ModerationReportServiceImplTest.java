package com.tongji.moderation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.moderation.api.dto.ModerationReportRequest;
import com.tongji.moderation.api.dto.ModerationReportResponse;
import com.tongji.moderation.mapper.ModerationReportMapper;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.service.impl.ModerationReportServiceImpl;
import com.tongji.relation.outbox.OutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationReportServiceImplTest {

    private ModerationReportMapper reportMapper;
    private KnowPostMapper knowPostMapper;
    private CommentMapper commentMapper;
    private OutboxMapper outboxMapper;
    private IdService idService;
    private ModerationReportService service;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ModerationReportMapper.class);
        knowPostMapper = mock(KnowPostMapper.class);
        commentMapper = mock(CommentMapper.class);
        outboxMapper = mock(OutboxMapper.class);
        idService = mock(IdService.class);
        service = new ModerationReportServiceImpl(
                reportMapper,
                knowPostMapper,
                commentMapper,
                outboxMapper,
                idService,
                new ObjectMapper()
        );
    }

    @Test
    void duplicateReportReturnsExistingRecordWithoutWritingOutbox() {
        ModerationReport existing = ModerationReport.builder()
                .id(11L)
                .reporterUserId(7L)
                .targetType("post")
                .targetId(101L)
                .status("pending")
                .build();
        when(reportMapper.findByReporterAndTarget(7L, "post", 101L)).thenReturn(existing);

        ModerationReportResponse response = service.submitReport(7L,
                new ModerationReportRequest("post", 101L, "spam", null));

        assertThat(response.reportId()).isEqualTo(11L);
        assertThat(response.status()).isEqualTo("pending");
        verify(reportMapper, never()).insert(org.mockito.Mockito.any());
        verify(outboxMapper, never()).insert(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void newPostReportInsertsPendingRecordAndReviewRequestedOutbox() {
        KnowPost post = KnowPost.builder()
                .id(101L)
                .creatorId(8L)
                .status("published")
                .build();
        when(knowPostMapper.findById(101L)).thenReturn(post);
        when(idService.nextId(IdNamespace.MODERATION_REPORT)).thenReturn(21L);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(31L);

        ModerationReportResponse response = service.submitReport(7L,
                new ModerationReportRequest("post", 101L, "spam", "bad links"));

        assertThat(response.reportId()).isEqualTo(21L);
        assertThat(response.status()).isEqualTo("pending");

        ArgumentCaptor<ModerationReport> reportCaptor = ArgumentCaptor.forClass(ModerationReport.class);
        verify(reportMapper).insert(reportCaptor.capture());
        ModerationReport report = reportCaptor.getValue();
        assertThat(report.getId()).isEqualTo(21L);
        assertThat(report.getReporterUserId()).isEqualTo(7L);
        assertThat(report.getTargetOwnerUserId()).isEqualTo(8L);
        assertThat(report.getTargetType()).isEqualTo("post");
        assertThat(report.getStatus()).isEqualTo("pending");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insert(org.mockito.Mockito.eq(31L),
                org.mockito.Mockito.eq("moderation_report"),
                org.mockito.Mockito.eq(21L),
                org.mockito.Mockito.eq("review_requested"),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"entity\":\"moderation_report\"");
        assertThat(payloadCaptor.getValue()).contains("\"op\":\"review_requested\"");
        assertThat(payloadCaptor.getValue()).contains("\"reportId\":21");
    }

    @Test
    void deletedCommentCannotBeReported() {
        when(commentMapper.findById(301L)).thenReturn(Comment.builder()
                .commentId(301L)
                .creatorId(9L)
                .status(1)
                .build());

        assertThatThrownBy(() -> service.submitReport(7L,
                new ModerationReportRequest("comment", 301L, "harassment", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标不存在");
    }

    @Test
    void unpublishedPostCannotBeReported() {
        when(knowPostMapper.findById(101L)).thenReturn(KnowPost.builder()
                .id(101L)
                .creatorId(8L)
                .status("draft")
                .build());

        assertThatThrownBy(() -> service.submitReport(7L,
                new ModerationReportRequest("post", 101L, "spam", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标不存在");
    }

    @Test
    void userReportTargetTypeIsRejected() {
        assertThatThrownBy(() -> service.submitReport(7L,
                new ModerationReportRequest("user", 8L, "spam", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("targetType");
    }

    @Test
    void protocolValuesNormalizeWithRootLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            KnowPost post = KnowPost.builder()
                    .id(101L)
                    .creatorId(8L)
                    .status("published")
                    .build();
            when(knowPostMapper.findById(101L)).thenReturn(post);
            when(idService.nextId(IdNamespace.MODERATION_REPORT)).thenReturn(21L);
            when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(31L);

            ModerationReportResponse response = service.submitReport(7L,
                    new ModerationReportRequest("POST", 101L, "ILLEGAL", null));

            assertThat(response.reportId()).isEqualTo(21L);
            verify(reportMapper).insert(org.mockito.Mockito.any());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void submitReportTransactionAllowsDuplicateKeyRaceRecovery() throws NoSuchMethodException {
        Method method = ModerationReportServiceImpl.class.getMethod(
                "submitReport",
                long.class,
                ModerationReportRequest.class
        );

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(DuplicateKeyException.class);
    }
}

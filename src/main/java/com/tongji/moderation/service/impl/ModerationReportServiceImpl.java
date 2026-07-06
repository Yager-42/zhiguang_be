package com.tongji.moderation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.moderation.api.dto.ModerationReportRequest;
import com.tongji.moderation.api.dto.ModerationReportResponse;
import com.tongji.moderation.mapper.ModerationReportMapper;
import com.tongji.moderation.model.ModerationReason;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.model.ModerationStatus;
import com.tongji.moderation.model.ModerationTargetType;
import com.tongji.moderation.service.ModerationReportService;
import com.tongji.relation.outbox.OutboxMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class ModerationReportServiceImpl implements ModerationReportService {
    private static final Integer COMMENT_STATUS_DELETED = 1;

    private final ModerationReportMapper reportMapper;
    private final KnowPostMapper knowPostMapper;
    private final CommentMapper commentMapper;
    private final OutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;

    public ModerationReportServiceImpl(ModerationReportMapper reportMapper,
                                       KnowPostMapper knowPostMapper,
                                       CommentMapper commentMapper,
                                       OutboxMapper outboxMapper,
                                       IdService idService,
                                       ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.knowPostMapper = knowPostMapper;
        this.commentMapper = commentMapper;
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(noRollbackFor = DuplicateKeyException.class)
    public ModerationReportResponse submitReport(long reporterUserId, ModerationReportRequest request) {
        String targetType = normalize(request.targetType());
        String reason = normalize(request.reason());
        if (!ModerationTargetType.POST.equals(targetType) && !ModerationTargetType.COMMENT.equals(targetType)) {
            throw badRequest("targetType 只允许 post 或 comment");
        }
        if (!ModerationReason.isSupported(reason)) {
            throw badRequest("reason 非法");
        }
        ModerationReport existing = reportMapper.findByReporterAndTarget(reporterUserId, targetType, request.targetId());
        if (existing != null) {
            return new ModerationReportResponse(String.valueOf(existing.getId()), existing.getStatus());
        }

        long ownerUserId = resolveOwner(targetType, request.targetId());
        long reportId = idService.nextId(IdNamespace.MODERATION_REPORT);
        LocalDateTime now = LocalDateTime.now();
        ModerationReport report = ModerationReport.builder()
                .id(reportId)
                .reporterUserId(reporterUserId)
                .targetType(targetType)
                .targetId(request.targetId())
                .targetOwnerUserId(ownerUserId)
                .reason(reason)
                .description(blankToNull(request.description()))
                .status(ModerationStatus.PENDING)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException exception) {
            ModerationReport raced = reportMapper.findByReporterAndTarget(reporterUserId, targetType, request.targetId());
            if (raced != null) {
                return new ModerationReportResponse(String.valueOf(raced.getId()), raced.getStatus());
            }
            throw exception;
        }
        writeReviewRequestedOutbox(report);
        return new ModerationReportResponse(String.valueOf(reportId), ModerationStatus.PENDING);
    }

    private long resolveOwner(String targetType, long targetId) {
        if (ModerationTargetType.POST.equals(targetType)) {
            KnowPost post = knowPostMapper.findById(targetId);
            if (post == null || !"published".equals(post.getStatus())) {
                throw badRequest("目标不存在或不可举报");
            }
            return post.getCreatorId();
        }
        Comment comment = commentMapper.findById(targetId);
        if (comment == null || COMMENT_STATUS_DELETED.equals(comment.getStatus())) {
            throw badRequest("目标不存在或不可举报");
        }
        return comment.getCreatorId();
    }

    private void writeReviewRequestedOutbox(ModerationReport report) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("entity", "moderation_report");
            payload.put("op", "review_requested");
            payload.put("reportId", report.getId());
            payload.put("targetType", report.getTargetType());
            payload.put("targetId", report.getTargetId());
            outboxMapper.insert(
                    idService.nextId(IdNamespace.OUTBOX_EVENT),
                    "moderation_report",
                    report.getId(),
                    "review_requested",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "审核事件序列化失败");
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}

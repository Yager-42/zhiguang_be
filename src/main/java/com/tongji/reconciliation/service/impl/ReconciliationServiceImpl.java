package com.tongji.reconciliation.service.impl;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.scan.ReconciliationScanService;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskQuery;
import com.tongji.reconciliation.model.ReconciliationTaskStatus;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.service.ReconciliationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ReconciliationServiceImpl implements ReconciliationService {

    private final ReconciliationTaskMapper taskMapper;
    private final IdService idService;
    private final Clock clock;
    private final ReconciliationScanService scanService;

    @Autowired
    public ReconciliationServiceImpl(ReconciliationTaskMapper taskMapper,
                                     IdService idService,
                                     ReconciliationScanService scanService) {
        this(taskMapper, idService, scanService, Clock.systemDefaultZone());
    }

    public ReconciliationServiceImpl(ReconciliationTaskMapper taskMapper,
                                     IdService idService,
                                     ReconciliationScanService scanService,
                                     Clock clock) {
        this.taskMapper = taskMapper;
        this.idService = idService;
        this.scanService = scanService;
        this.clock = clock;
    }

    @Override
    public ReconciliationTask createTask(String taskType, String targetType, Long targetId) {
        return createTask(taskType, targetType, targetId, null);
    }

    @Override
    public ReconciliationTask createTask(String taskType, String targetType, Long targetId, String taskPayload) {
        LocalDateTime now = LocalDateTime.now(clock);
        ReconciliationTask task = ReconciliationTask.builder()
                .id(idService.nextId(IdNamespace.RECONCILIATION_TASK))
                .taskType(taskType)
                .targetType(targetType)
                .targetId(targetId)
                .status(ReconciliationTaskStatus.PENDING)
                .retryCount(0)
                .nextExecuteAt(now)
                .dedupeScope(dedupeScope(taskType, targetType, targetId, taskPayload))
                .taskPayload(taskPayload)
                .createdAt(now)
                .updatedAt(now)
                .build();
        taskMapper.insert(task);
        return task;
    }

    @Override
    public ReconciliationTask createTaskIfAbsent(String taskType, String targetType, Long targetId) {
        return createTaskIfAbsent(taskType, targetType, targetId, null);
    }

    @Override
    public ReconciliationTask createTaskIfAbsent(String taskType, String targetType, Long targetId, String taskPayload) {
        String dedupeScope = dedupeScope(taskType, targetType, targetId, taskPayload);
        ReconciliationTask existing = taskMapper.findActiveByDedupeScope(dedupeScope);
        if (existing != null) {
            return null;
        }
        try {
            return createTask(taskType, targetType, targetId, taskPayload);
        } catch (DuplicateKeyException ex) {
            return null;
        }
    }

    @Override
    public ReconciliationTask createDeadTaskIfAbsent(String taskType, String targetType, Long targetId, String lastError) {
        if (taskMapper.existsActiveTask(taskType, targetType, targetId)
                || taskMapper.existsByStatus(taskType, targetType, targetId, ReconciliationTaskStatus.DEAD)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ReconciliationTask task = ReconciliationTask.builder()
                .id(idService.nextId(IdNamespace.RECONCILIATION_TASK))
                .taskType(taskType)
                .targetType(targetType)
                .targetId(targetId)
                .status(ReconciliationTaskStatus.DEAD)
                .retryCount(0)
                .nextExecuteAt(now)
                .dedupeScope(dedupeScope(taskType, targetType, targetId, null))
                .lastError(lastError)
                .createdAt(now)
                .updatedAt(now)
                .build();
        taskMapper.insert(task);
        return task;
    }

    @Override
    public ReconciliationTask retryTask(Long taskId) {
        if (taskMapper.resetDeadToPending(taskId) != 1) {
            return null;
        }
        return taskMapper.findById(taskId);
    }

    @Override
    public List<ReconciliationTask> rerunTarget(String targetType, Long targetId) {
        List<ReconciliationTask> tasks = new ArrayList<>();
        if (ReconciliationTargetType.POST.equals(targetType)) {
            addIfCreated(tasks, ReconciliationTaskType.ES_INDEX, targetType, targetId);
            addIfCreated(tasks, ReconciliationTaskType.RAG_INDEX, targetType, targetId);
            addIfCreated(tasks, ReconciliationTaskType.GORSE_ITEM_UPSERT, targetType, targetId);
            addIfCreated(tasks, ReconciliationTaskType.CASSANDRA_TEXT, targetType, targetId);
            addIfCreated(tasks, ReconciliationTaskType.COMMENT_COUNT, targetType, targetId);
            ReconciliationScanService.FollowInboxTaskSpec followInboxTaskSpec = scanService.buildFollowInboxTaskSpec(targetId);
            if (followInboxTaskSpec != null) {
                ReconciliationTask followInboxTask = createTaskIfAbsent(
                        ReconciliationTaskType.FOLLOW_INBOX,
                        ReconciliationTargetType.USER,
                        followInboxTaskSpec.authorId(),
                        followInboxTaskSpec.payload()
                );
                if (followInboxTask != null) {
                    tasks.add(followInboxTask);
                }
            }
        } else if (ReconciliationTargetType.COMMENT.equals(targetType)) {
            addIfCreated(tasks, ReconciliationTaskType.CASSANDRA_TEXT, targetType, targetId);
            addIfCreated(tasks, ReconciliationTaskType.COMMENT_COUNT, targetType, targetId);
        } else if (ReconciliationTargetType.USER.equals(targetType)) {
            addIfCreated(tasks, ReconciliationTaskType.FOLLOW_GRAPH, targetType, targetId);
        }
        return tasks;
    }

    @Override
    public ReconciliationTask findById(Long taskId) {
        return taskMapper.findById(taskId);
    }

    @Override
    public List<ReconciliationTask> query(ReconciliationTaskQuery query) {
        return taskMapper.query(query);
    }

    private void addIfCreated(List<ReconciliationTask> tasks, String taskType, String targetType, Long targetId) {
        ReconciliationTask task = createTaskIfAbsent(taskType, targetType, targetId, null);
        if (task != null) {
            tasks.add(task);
        }
    }

    private String dedupeScope(String taskType, String targetType, Long targetId, String taskPayload) {
        String payloadPart = taskPayload == null || taskPayload.isBlank() ? "" : ":" + payloadDigest(taskPayload);
        return taskType + ":" + targetType + ":" + targetId + payloadPart;
    }

    private String payloadDigest(String taskPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(taskPayload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash reconciliation task payload", e);
        }
    }
}

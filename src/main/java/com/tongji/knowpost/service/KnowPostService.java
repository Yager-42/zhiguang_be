package com.tongji.knowpost.service;

import com.tongji.knowpost.api.dto.KnowPostDetailResponse;

import java.util.List;

/**
 * 知文业务接口。
 */
public interface KnowPostService {

    long createDraft(long creatorId);

    void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256);

    void updateMetadata(long creatorId, long id, String title, Long tagId, List<String> tags, List<String> imgUrls, String visible, Boolean isTop, String description);

    void updateTop(long creatorId, long id, boolean isTop);

    void updateVisibility(long creatorId, long id, String visible);

    void delete(long creatorId, long id);

    /**
     * 判断知文是否处于可互动的已发布状态。
     *
     * @param id 知文 ID
     * @return 已发布时返回 {@code true}
     */
    boolean isPublished(long id);

    KnowPostDetailResponse getDetail(long id, Long currentUserIdNullable);
}

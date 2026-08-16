package com.tongji.knowpost.mapper;

import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.knowpost.model.KnowPostFeedRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowPostMapper {
    void insertDraft(KnowPost post);

    KnowPost findById(@Param("id") Long id);

    int updateContent(KnowPost post);

    int updateMetadata(KnowPost post);

    int publish(@Param("id") Long id, @Param("creatorId") Long creatorId);

    int startPublishing(@Param("id") Long id,
                        @Param("creatorId") Long creatorId,
                        @Param("publishAttemptId") Long publishAttemptId);

    int completePublish(@Param("id") Long id,
                        @Param("creatorId") Long creatorId,
                        @Param("publishAttemptId") Long publishAttemptId);

    int failPublish(@Param("id") Long id,
                    @Param("creatorId") Long creatorId,
                    @Param("publishAttemptId") Long publishAttemptId,
                    @Param("publishFailedReason") String publishFailedReason);

    KnowPost findPublishStatus(@Param("id") Long id, @Param("creatorId") Long creatorId);

    int retryPublishing(@Param("id") Long id,
                        @Param("creatorId") Long creatorId,
                        @Param("publishAttemptId") Long publishAttemptId);

    List<KnowPostFeedRow> listFeedPublic(@Param("limit") int limit,
                                         @Param("offset") int offset);

    List<Long> listFeedPublicIds(@Param("limit") int limit,
                                 @Param("offset") int offset);

    List<KnowPostFeedRow> listFeedByIds(@Param("ids") List<Long> ids,
                                        @Param("currentUserId") Long currentUserId,
                                        @Param("allowFollowers") boolean allowFollowers);

    List<KnowPostFeedRow> listMyPublished(@Param("creatorId") long creatorId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    int updateTop(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("isTop") Boolean isTop);

    int updateVisibility(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("visible") String visible);

    int softDelete(@Param("id") Long id, @Param("creatorId") Long creatorId);

    int rejectPublishedForModeration(@Param("id") Long id);

    KnowPostDetailRow findDetailById(@Param("id") Long id);

    long countMyPublished(@Param("creatorId") long creatorId);

    List<Long> listMyPublishedIds(@Param("creatorId") long creatorId);

    /**
     * 按发布时间倒序列出作者最近已发布的知文（用于关注后时间线回填）。
     */
    List<KnowPost> listRecentPublishedByCreator(@Param("creatorId") long creatorId,
                                                @Param("limit") int limit);

    List<Long> listPublishedPostIdsCursor(@Param("cursorPostId") Long cursorPostId,
                                          @Param("limit") int limit);

    List<Long> listPublicPublishedPostIdsCursor(@Param("cursorPostId") Long cursorPostId,
                                                @Param("limit") int limit);

}

package com.tongji.relation.service;

import java.util.List;
import com.tongji.profile.api.dto.ProfileResponse;

/**
 * 关系服务接口。
 * 能力：关注/粉丝读取、分页查询，以及用户资料视图聚合。
 */
public interface RelationService {
    /**
     * 获取关注列表（偏移分页）。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量
     * @return 关注用户ID列表
     */
    List<Long> following(long userId, int limit, int offset);
    /**
     * 获取粉丝列表（偏移分页）。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量
     * @return 粉丝用户ID列表
     */
    List<Long> followers(long userId, int limit, int offset);
    /**
     * 游标分页关注列表。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param cursor 上一页末条的分数（毫秒时间戳），为空代表第一页
     * @return 关注用户ID列表
     */
    List<Long> followingCursor(long userId, int limit, Long cursor);
    /**
     * 游标分页粉丝列表。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param cursor 上一页末条的分数（毫秒时间戳），为空代表第一页
     * @return 粉丝用户ID列表
     */
    List<Long> followersCursor(long userId, int limit, Long cursor);

    /**
     * 关注列表（资料视图）：在 ID 列表基础上聚合用户资料，支持偏移或游标。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量（游标为空时生效）
     * @param cursor 游标（上一页末条分数）
     * @return 关注用户的资料视图列表
     */
    List<ProfileResponse> followingProfiles(long userId, int limit, int offset, Long cursor);

    /**
     * 粉丝列表（资料视图）：在 ID 列表基础上聚合用户资料，支持偏移或游标。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量（游标为空时生效）
     * @param cursor 游标（上一页末条分数）
     * @return 粉丝用户的资料视图列表
     */
    List<ProfileResponse> followersProfiles(long userId, int limit, int offset, Long cursor);
}

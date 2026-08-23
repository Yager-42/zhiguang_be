package com.tongji.favorite.mapper;

import com.tongji.favorite.model.UserFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 持久化用户收藏关系并提供稳定游标分页。
 *
 * @since 2026-08-21
 */
@Mapper
public interface FavoriteMapper {

    /**
     * 插入收藏关系；重复收藏由主键约束转换为无变化。
     *
     * @return 插入行数，取值为 0 或 1
     */
    int insertIgnore(@Param("userId") long userId,
                     @Param("postId") long postId,
                     @Param("createdAt") Instant createdAt);

    /**
     * 删除收藏关系；关系不存在时幂等返回 0。
     *
     * @return 删除行数，取值为 0 或 1
     */
    int delete(@Param("userId") long userId, @Param("postId") long postId);

    /**
     * 幂等批量写入旧 Redis Bitmap 中的收藏关系，不产生 Outbox。
     *
     * @param favorites 单批收藏关系，调用方限制为最多 500 条
     * @return 实际插入行数
     */
    int insertIgnoreBatch(@Param("favorites") List<UserFavorite> favorites);

    /**
     * 按收藏时间和知文 ID 倒序分页。
     *
     * @param cursorCreatedAt 上一页末条收藏时间；第一页为空
     * @param cursorPostId 上一页末条知文 ID；第一页为空
     * @param limit 查询上限
     * @return 收藏关系列表；无结果时返回空列表
     */
    List<UserFavorite> listPage(@Param("userId") long userId,
                                @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                @Param("cursorPostId") Long cursorPostId,
                                @Param("limit") int limit);
}

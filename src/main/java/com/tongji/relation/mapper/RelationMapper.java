package com.tongji.relation.mapper;

import com.tongji.recommendation.feed.FanoutFollowerRow;
import com.tongji.recommendation.feed.FollowedAuthorRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 关系表数据访问层。
 * 职责：维护关注/粉丝关系的插入与逻辑取消，分页读取与行数据回填，统计有效关系计数。
 */
@Mapper
public interface RelationMapper {
    /**
     * 插入关注关系。
     * @param id 主键ID
     * @param fromUserId 发起关注的用户ID
     * @param toUserId 被关注的用户ID
     * @param relStatus 关系状态
     * @return 影响行数
     */
    int insertFollowing(@Param("id") Long id,
                        @Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId,
                        @Param("relStatus") Integer relStatus);

    /**
     * 取消关注关系（逻辑更新）。
     * @param fromUserId 发起者
     * @param toUserId 目标者
     * @return 影响行数
     */
    int cancelFollowing(@Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId);


    /**
     * 判断是否存在关注关系。
     * @param fromUserId 发起者
     * @param toUserId 目标者
     * @return 是否存在（>0 表示存在）
     */
    int existsFollowing(@Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId);

    /**
     * 查询当前有效关注关系的主键 ID。
     * @param fromUserId 发起者
     * @param toUserId 目标者
     * @return 有效关系主键，若不存在则为空
     */
    Long findActiveFollowingId(@Param("fromUserId") Long fromUserId,
                               @Param("toUserId") Long toUserId);


    /**
     * 列出关注行（包含 createdAt，按 SQL 顺序返回，供分页截取）。
     * @param fromUserId 发起者
     * @param limit 上限
     * @param offset 偏移
     * @return 关注行列表（toUserId/createdAt 两列）
     */
    List<Map<String, Object>> listFollowingRows(@Param("fromUserId") Long fromUserId,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);

    /**
     * 列出粉丝行（包含 createdAt，按 SQL 顺序返回，供分页截取）。
     * @param toUserId 被关注者
     * @param limit 上限
     * @param offset 偏移
     * @return 粉丝行列表（fromUserId/createdAt 两列）
     */
    List<Map<String, Object>> listFollowerRows(@Param("toUserId") Long toUserId,
                                               @Param("limit") int limit,
                                               @Param("offset") int offset);

    /**
     * 游标分页列出关注行（created_at DESC, to_user_id DESC）。
     */
    List<Map<String, Object>> listFollowingRowsCursor(@Param("fromUserId") Long fromUserId,
                                                      @Param("cursorCreatedAt") java.sql.Timestamp cursorCreatedAt,
                                                      @Param("cursorToUserId") Long cursorToUserId,
                                                      @Param("limit") int limit);

    /**
     * 游标分页列出粉丝行（created_at DESC, from_user_id DESC）。
     */
    List<Map<String, Object>> listFollowerRowsCursor(@Param("toUserId") Long toUserId,
                                                     @Param("cursorCreatedAt") java.sql.Timestamp cursorCreatedAt,
                                                     @Param("cursorFromUserId") Long cursorFromUserId,
                                                     @Param("limit") int limit);

    /**
     * 统计关注数（有效关系）。
     */
    int countFollowingActive(@Param("fromUserId") Long fromUserId);

    /**
     * 统计粉丝数（有效关系）。
     */
    int countFollowerActive(@Param("toUserId") Long toUserId);

    List<FanoutFollowerRow> listFollowersForFanout(@Param("toUserId") Long toUserId,
                                                   @Param("cursorCreatedAt") java.sql.Timestamp cursorCreatedAt,
                                                   @Param("cursorFromUserId") Long cursorFromUserId,
                                                   @Param("limit") int limit);

    List<FollowedAuthorRow> listFollowedAuthorsForFeed(@Param("fromUserId") Long fromUserId,
                                                       @Param("cursorCreatedAt") java.sql.Timestamp cursorCreatedAt,
                                                       @Param("cursorToUserId") Long cursorToUserId,
                                                       @Param("limit") int limit);

    Timestamp findFollowedAuthorCreatedAt(@Param("fromUserId") Long fromUserId,
                                          @Param("toUserId") Long toUserId);

}

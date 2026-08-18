package com.tongji.relation.api;

import com.tongji.auth.token.JwtService;
import com.tongji.counter.service.UserCounterReader;
import com.tongji.counter.service.UserCounters;
import com.tongji.profile.api.dto.ProfileResponse;
import com.tongji.relation.command.RelationCommandService;
import com.tongji.relation.manager.RelationManager;
import com.tongji.relation.service.RelationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 关系接口控制器。
 * 职责：关注/取关命令受理（限流 → Kafka 投递，事实写由消费端事务完成）、
 * 关系三态查询、关注/粉丝列表（直查 MySQL，偏移与游标）、用户维度计数读取与采样自检。
 */
@RestController
@RequestMapping("/api/v1/relation")
public class RelationController {
    private final RelationCommandService relationCommandService;
    private final RelationManager relationManager;
    private final RelationService relationService;
    private final JwtService jwtService;
    private final UserCounterReader userCounterReader;

    public RelationController(RelationCommandService relationCommandService,
                              RelationManager relationManager,
                              RelationService relationService,
                              JwtService jwtService,
                              UserCounterReader userCounterReader) {
        this.relationCommandService = relationCommandService;
        this.relationManager = relationManager;
        this.relationService = relationService;
        this.jwtService = jwtService;
        this.userCounterReader = userCounterReader;
    }

    /**
     * 发起关注（异步受理：限流 → 投递 Kafka，立即返回）。
     * @param toUserId 被关注的用户ID
     * @param jwt 认证令牌
     * @return 是否受理成功
     */
    @PostMapping("/follow")
    public boolean follow(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationCommandService.follow(uid, toUserId).success();
    }

    /**
     * 取消关注（异步受理，立即返回）。
     * @param toUserId 被取消关注的用户ID
     * @param jwt 认证令牌
     * @return 是否受理成功
     */
    @PostMapping("/unfollow")
    public boolean unfollow(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationCommandService.unfollow(uid, toUserId).success();
    }

    /**
     * 查询与目标用户的关系三态。
     * @param toUserId 目标用户ID
     * @param jwt 认证令牌
     * @return following / followedBy / mutual
     */
    @GetMapping("/status")
    public Map<String, Boolean> status(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationManager.status(uid, toUserId);
    }

    /**
     * 获取关注列表，支持偏移或游标分页。
     * @param userId 用户ID
     * @param limit 每页条数（默认 20，上限 100）
     * @param offset 偏移分页游标起点
     * @param cursor 游标分页（created_at 严格小于该值）
     * @return 用户概要列表
     */
    @GetMapping("/following")
    public List<ProfileResponse> following(@RequestParam("userId") long userId,
                                @RequestParam(value = "limit", defaultValue = "20") int limit,
                                @RequestParam(value = "offset", defaultValue = "0") int offset,
                                @RequestParam(value = "cursor", required = false) Long cursor) {
        return relationService.followingProfiles(userId, limit, offset, cursor);
    }

    /**
     * 获取粉丝列表，支持偏移或游标分页。
     * @param userId 用户ID
     * @param limit 每页条数（默认 20，上限 100）
     * @param offset 偏移分页游标起点
     * @param cursor 游标分页（created_at 严格小于该值）
     * @return 用户概要列表
     */
    @GetMapping("/followers")
    public List<ProfileResponse> followers(@RequestParam("userId") long userId,
                                          @RequestParam(value = "limit", defaultValue = "20") int limit,
                                          @RequestParam(value = "offset", defaultValue = "0") int offset,
                                          @RequestParam(value = "cursor", required = false) Long cursor) {
        return relationService.followersProfiles(userId, limit, offset, cursor);
    }

    /**
     * 获取用户维度计数。
     * @param userId 用户ID
     * @return 各类计数
     */
    @GetMapping("/counter")
    public UserCounters counter(@RequestParam("userId") long userId) {
        return userCounterReader.getVerified(userId);
    }
}
package com.tongji.counter.api;

import com.tongji.counter.api.dto.ActionRequest;
import com.tongji.counter.service.CounterService;
import com.tongji.auth.token.JwtService;
import com.tongji.favorite.service.FavoriteService;
import com.tongji.favorite.service.FavoriteWriteResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 行为接口：点赞/取消点赞、收藏/取消收藏。
 *
 * <p>所有接口基于登录用户，返回操作是否改变状态以及当前状态值。</p>
 */
@RestController
@RequestMapping("/api/v1/action")
public class ActionController {

    private final CounterService counterService;
    private final JwtService jwtService;
    private final FavoriteService favoriteService;

    public ActionController(CounterService counterService, JwtService jwtService, FavoriteService favoriteService) {
        this.counterService = counterService;
        this.jwtService = jwtService;
        this.favoriteService = favoriteService;
    }

    /**
     * 点赞操作。
     */
    @PostMapping("/like")
    public ResponseEntity<Map<String, Object>> like(@Valid @RequestBody ActionRequest req,
                                                    @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterService.like(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 标识这次操作是否改变状态（避免重复点击）
                "liked", counterService.isLiked(req.getEntityType(), req.getEntityId(), uid)
        ));
    }

    /**
     * 取消点赞操作。
     */
    @PostMapping("/unlike")
    public ResponseEntity<Map<String, Object>> unlike(@Valid @RequestBody ActionRequest req,
                                                      @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterService.unlike(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 状态是否发生变化
                "liked", counterService.isLiked(req.getEntityType(), req.getEntityId(), uid)
        ));
    }

    /**
     * 收藏操作。
     */
    @PostMapping("/fav")
    public ResponseEntity<Map<String, Object>> fav(@Valid @RequestBody ActionRequest req,
                                                   @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        FavoriteWriteResult result = favoriteService.favorite(uid, req.getEntityType(), req.getEntityId());
        return ResponseEntity.ok(Map.of(
                "changed", result.changed(),
                "faved", result.faved()
        ));
    }

    /**
     * 取消收藏操作。
     */
    @PostMapping("/unfav")
    public ResponseEntity<Map<String, Object>> unfav(@Valid @RequestBody ActionRequest req,
                                                     @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        FavoriteWriteResult result = favoriteService.unfavorite(uid, req.getEntityType(), req.getEntityId());
        return ResponseEntity.ok(Map.of(
                "changed", result.changed(),
                "faved", result.faved()
        ));
    }
}

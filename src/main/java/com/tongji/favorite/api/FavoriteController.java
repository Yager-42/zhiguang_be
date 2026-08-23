package com.tongji.favorite.api;

import com.tongji.auth.token.JwtService;
import com.tongji.favorite.service.FavoriteService;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户收藏列表接口。
 *
 * @since 2026-08-21
 */
@RestController
@RequestMapping("/api/v1/favorites")
@Validated
public class FavoriteController {
    private final FavoriteService favoriteService;
    private final JwtService jwtService;

    public FavoriteController(FavoriteService favoriteService, JwtService jwtService) {
        this.favoriteService = favoriteService;
        this.jwtService = jwtService;
    }

    /**
     * 按收藏时间倒序返回当前用户仍可访问的知文。
     *
     * @param cursor 上一页返回的不透明游标；第一页为空
     * @param size 每页数量，范围为 1 到 50
     * @param jwt 当前登录凭证
     * @return 收藏知文游标页
     */
    @GetMapping
    public FeedPageResponse list(@RequestParam(value = "cursor", required = false) String cursor,
                                 @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(50) int size,
                                 @AuthenticationPrincipal Jwt jwt) {
        return favoriteService.list(jwtService.extractUserId(jwt), cursor, size);
    }
}

package com.tongji.reconciliation.security;

import com.tongji.auth.token.JwtService;
import com.tongji.reconciliation.config.ReconciliationOperatorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 对账模块授权判断。访问权限只以后端操作员配置为准。
 */
@Component("reconciliationAuthorization")
@RequiredArgsConstructor
public class ReconciliationAuthorization {

    private final ReconciliationOperatorProperties properties;
    private final JwtService jwtService;

    /**
     * 判断当前认证用户是否为对账操作员。
     *
     * @param authentication Spring Security 认证对象
     * @return 是操作员时返回 true
     */
    public boolean isOperator(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        return properties.getOperatorUserIds().contains(jwtService.extractUserId(jwt));
    }
}

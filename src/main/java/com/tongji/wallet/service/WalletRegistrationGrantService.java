package com.tongji.wallet.service;

import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册赠币编排服务：在单事务内完成建用户 + 初始化钱包 + 可选注册赠币。
 * <p>
 * 边界只接收 {@code User} 与赠币金额，不接收 auth DTO / client info。
 * {@code grantAmount <= 0} 解释为"不发赠币"（只建用户 + 初始化钱包），不是 wallet movement。
 * AuthService 必须使用返回的 created user 签 token / 存 refresh token。
 */
@Service
@RequiredArgsConstructor
public class WalletRegistrationGrantService {

    private final UserService userService;
    private final WalletService walletService;

    /**
     * 创建用户并按需发放注册赠币。
     *
     * @param user        待创建用户（id 由持久化回填）。
     * @param grantAmount 赠币金额；&lt;= 0 时只建用户 + 初始化钱包，跳过赠币 movement。
     * @return 持久化后的用户（含最终 id / 字段）。
     */
    @Transactional
    public User createUserAndGrant(User user, long grantAmount) {
        User created = userService.createUser(user);
        walletService.initializeIfAbsent(created.getId());
        if (grantAmount <= 0) {
            return created;
        }
        walletService.grant(created.getId(), grantAmount, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "registration-grant:user:" + created.getId());
        return created;
    }
}

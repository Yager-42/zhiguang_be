package com.tongji.wallet.service;

import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import com.tongji.wallet.model.WalletLedgerReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册赠币编排服务：在单事务内完成建用户 + 初始化钱包 + 注册赠币。
 * <p>
 * AuthService 在事务成功后再签发 token；赠币失败会随事务回滚用户创建。
 * 赠币金额由配置控制，置 0 即停掉赠币写入（保留钱包表）。
 */
@Service
@RequiredArgsConstructor
public class WalletRegistrationGrantService {

    private final UserService userService;
    private final WalletService walletService;

    /**
     * 创建用户并发放注册赠币。
     *
     * @param user         待创建用户（id 由持久化回填）。
     * @param grantAmount  赠币金额；&lt;= 0 时跳过赠币写入。
     * @return 持久化后的用户。
     */
    @Transactional
    public User createUserAndGrant(User user, long grantAmount) {
        User created = userService.createUser(user);
        walletService.initializeIfAbsent(created.getId());
        if (grantAmount > 0) {
            walletService.grant(created.getId(), grantAmount, WalletLedgerReason.REGISTRATION_GRANT,
                    "registration-grant:user:" + created.getId());
        }
        return created;
    }
}

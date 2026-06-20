package com.tongji.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钱包配置属性，绑定前缀 {@code wallet.*}。
 * <ul>
 *   <li>{@code platform-user-id}：平台账本主体哨兵 ID，默认 0。</li>
 *   <li>{@code registration-grant-amount}：注册赠币金额，默认 100。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "wallet")
public class WalletProperties {

    /** 平台账本主体哨兵 user id。 */
    private long platformUserId = 0L;

    /** 注册赠币金额。 */
    private long registrationGrantAmount = 100L;
}

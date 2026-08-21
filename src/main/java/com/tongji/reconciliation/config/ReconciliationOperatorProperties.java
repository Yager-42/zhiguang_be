package com.tongji.reconciliation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 对账操作员配置。操作员用户 ID 由 {@code reconciliation.operator-user-ids} 提供。
 */
@Data
@Component
@ConfigurationProperties(prefix = "reconciliation")
public class ReconciliationOperatorProperties {

    /** 允许访问对账能力的用户 ID。 */
    private Set<Long> operatorUserIds = new HashSet<>();
}

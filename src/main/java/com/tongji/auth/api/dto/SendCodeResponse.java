package com.tongji.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tongji.auth.verification.VerificationScene;

/**
 * 发送验证码响应。
 * <p>
 * 返回规范化后的账号、场景、验证码有效期（秒），以及演示环境可选的注册或登录验证码。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendCodeResponse(
        String identifier,
        VerificationScene scene,
        int expireSeconds,
        String demoCode
) {
}

package com.tongji.auth.verification;

/**
 * 发送验证码结果。
 * <p>
 * 返回规范化账号、发送场景、验证码有效期（秒）与本次生成的验证码。
 */
public record SendCodeResult(String identifier,
                             VerificationScene scene,
                             int expireSeconds,
                             String code
) {
}

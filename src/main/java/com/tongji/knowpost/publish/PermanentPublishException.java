package com.tongji.knowpost.publish;

/**
 * 表示重试无法修复的发布输入或归档冲突。
 *
 * <p>发布 Kafka 消费者将该异常直接路由到 DLT，不执行普通退避重试。</p>
 *
 * @since 2026-08-28
 */
public class PermanentPublishException extends RuntimeException {

    public PermanentPublishException(String message) {
        super(message);
    }

    public PermanentPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}

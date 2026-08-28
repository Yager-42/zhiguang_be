package com.tongji.storage.text;

/**
 * 同一知文 ID 已归档为不同摘要时抛出的不可恢复完整性冲突。
 *
 * @since 2026-08-28
 */
public class TextDigestConflictException extends TextWriteException {

    public TextDigestConflictException(String message) {
        super(message);
    }
}

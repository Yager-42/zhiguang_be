package com.tongji.comment.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;

/**
 * 顶层评论的稳定游标排序方向。
 *
 * @since 2026-08-21
 */
public enum CommentSortOrder {
    LATEST("latest"),
    EARLIEST("earliest");

    private final String wireValue;

    CommentSortOrder(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 解析接口排序值。
     *
     * @param value 接口传入值；允许 {@code latest} 或 {@code earliest}
     * @return 对应排序方向
     * @throws BusinessException 当排序值不受支持时
     */
    public static CommentSortOrder fromWireValue(String value) {
        for (CommentSortOrder order : values()) {
            if (order.wireValue.equalsIgnoreCase(value)) {
                return order;
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "sort 只允许 latest 或 earliest");
    }

    /**
     * 返回稳定的接口值。
     *
     * @return 小写排序值
     */
    public String wireValue() {
        return wireValue;
    }
}

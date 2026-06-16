package com.tongji.knowpost.api.dto;

/**
 * 发布受理响应。返回字符串 ID，避免前端 Long 精度丢失。
 */
public record PublishAcceptedResponse(
        String publishAttemptId
) {}

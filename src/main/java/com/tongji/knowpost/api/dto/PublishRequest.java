package com.tongji.knowpost.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PublishRequest(
        @NotBlank(message = "幂等键不能为空")
        String idempotentKey
) {}

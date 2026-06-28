package com.tongji.moderation.service.impl;

import com.tongji.moderation.model.ModerationLlmResult;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.service.ModerationLlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "moderation.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledModerationLlmClient implements ModerationLlmClient {

    @Override
    public ModerationLlmResult review(ModerationReport report) {
        return ModerationLlmResult.retryableFailure("disabled", "local", "LLM_DISABLED", "moderation.llm.enabled is false");
    }
}

package com.tongji.moderation.service;

import com.tongji.moderation.model.ModerationLlmResult;
import com.tongji.moderation.model.ModerationReport;

public interface ModerationLlmClient {
    ModerationLlmResult review(ModerationReport report);
}

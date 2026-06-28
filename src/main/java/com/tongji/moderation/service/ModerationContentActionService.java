package com.tongji.moderation.service;

import com.tongji.moderation.model.ModerationReport;

public interface ModerationContentActionService {
    void applyApprovedAction(ModerationReport report);
}

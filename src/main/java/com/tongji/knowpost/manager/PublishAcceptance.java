package com.tongji.knowpost.manager;

import com.tongji.knowpost.publish.PublishAttempt;

public record PublishAcceptance(
        PublishAttempt attempt,
        boolean schedulePublishWork
) {}

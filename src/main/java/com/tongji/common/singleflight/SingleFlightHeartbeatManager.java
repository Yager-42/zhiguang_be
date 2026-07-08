package com.tongji.common.singleflight;

import com.tongji.common.singleflight.model.SingleFlightOwnerContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class SingleFlightHeartbeatManager {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "singleflight-heartbeat-");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public String start(SingleFlightOwnerContext ownerContext, Runnable heartbeat) {
        long intervalMillis = Math.max(500L, ownerContext.policy().heartbeatIntervalMillis());
        String taskKey = ownerContext.requestKey() + "|" + ownerContext.ownerToken() + "|" + UUID.randomUUID();
        ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {
            try {
                heartbeat.run();
            } catch (RuntimeException ignored) {
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        tasks.put(taskKey, task);
        return taskKey;
    }

    public void stop(String taskKey) {
        if (taskKey == null) {
            return;
        }
        ScheduledFuture<?> task = tasks.remove(taskKey);
        if (task != null) {
            task.cancel(false);
        }
    }
}

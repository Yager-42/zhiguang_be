package com.tongji.common.id;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("commonSnowflakeIdGenerator")
public class SnowflakeIdGenerator {
    static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long datacenterId;
    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    @Autowired
    public SnowflakeIdGenerator(SnowflakeProperties properties) {
        this(properties.getDatacenterId(), properties.getWorkerId());
    }

    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId out of range");
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId out of range");
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = currentTime();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5L) {
                waitForClock(offset);
                timestamp = currentTime();
                if (timestamp < lastTimestamp) {
                    throw new ClockBackwardException(
                            "Clock is still behind after waiting. last=" + lastTimestamp + ", now=" + timestamp);
                }
            } else {
                throw new ClockBackwardException(
                        "Clock moved backwards too much. Refusing to generate id. offset=" + offset + "ms");
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    long currentTime() {
        return System.currentTimeMillis();
    }

    void sleepMillis(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private void waitForClock(long offset) {
        try {
            sleepMillis(offset);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClockBackwardException("Interrupted while waiting for clock to catch up", e);
        }
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = currentTime();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTime();
        }
        return timestamp;
    }
}

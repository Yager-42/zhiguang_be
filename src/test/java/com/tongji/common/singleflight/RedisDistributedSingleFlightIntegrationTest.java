package com.tongji.common.singleflight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.singleflight.model.SingleFlightAcquireResult;
import com.tongji.common.singleflight.model.SingleFlightAction;
import com.tongji.common.singleflight.model.SingleFlightPolicy;
import com.tongji.common.singleflight.model.SingleFlightStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = RedisDistributedSingleFlightIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379",
        "spring.data.redis.database=0"
})
@EnabledIf("redisReachable")
class RedisDistributedSingleFlightIntegrationTest {

    private static final TypeReference<Map<String, Long>> MAP_TYPE = new TypeReference<>() {
    };

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisSingleFlightNotificationService notificationService;
    @Autowired
    private RedisSingleFlightCoordinatorRepository coordinatorRepository;
    @Autowired
    private DistributedSingleFlightService singleFlightService;

    private final List<String> touchedKeys = new ArrayList<>();
    private final List<String> touchedPatterns = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String pattern : touchedPatterns) {
            touchedKeys.addAll(redis.keys(pattern));
        }
        if (!touchedKeys.isEmpty()) {
            redis.delete(touchedKeys);
            touchedKeys.clear();
        }
        touchedPatterns.clear();
    }

    @Test
    void publishAddsStreamEventAndTtlInRealRedis() {
        String requestKey = uniqueRequestKey("publish");
        String streamKey = streamKey(requestKey);
        touchedKeys.add(streamKey);

        notificationService.publish(requestKey, "owner_succeeded", SingleFlightStatus.SUCCEEDED,
                17L, null, false, 5000L);

        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .range(streamKey, Range.unbounded(), Limit.limit().count(10));
        assertThat(records).hasSize(1);
        Map<Object, Object> event = records.get(0).getValue();
        assertThat(event).containsEntry("event", "owner_succeeded");
        assertThat(event).containsEntry("status", "SUCCEEDED");
        assertThat(event).containsEntry("ownerToken", "17");
        assertThat(redis.getExpire(streamKey, TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void waitFromEmptyStreamOffsetDoesNotMissFirstTerminalEvent() throws Exception {
        String requestKey = uniqueRequestKey("wait");
        touchedKeys.add(streamKey(requestKey));
        assertThat(notificationService.currentEventOffset(requestKey)).isEqualTo("0-0");

        long startedAt = System.nanoTime();
        CompletableFuture<Void> waiter = CompletableFuture.runAsync(
                () -> notificationService.waitForTerminalEvent(requestKey, "0-0", 5000L));
        Thread.sleep(150L);

        notificationService.publish(requestKey, "owner_succeeded", SingleFlightStatus.SUCCEEDED,
                18L, null, false, 5000L);
        waiter.get(2, TimeUnit.SECONDS);

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertThat(elapsedMillis).isLessThan(2000L);
        assertThat(notificationService.currentEventOffset(requestKey)).isNotEqualTo("0-0");
    }

    @Test
    void followerReplaysOwnerResultThroughRealRedisStreamAndResultStore() throws Exception {
        String requestKey = uniqueRequestKey("replay");
        addFlightKeys("counter-sds:" + requestKey);
        CountDownLatch ownerEntered = new CountDownLatch(1);
        AtomicInteger supplierCalls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Map<String, Long>> owner = CompletableFuture.supplyAsync(() ->
                    singleFlightService.execute("counter-sds", requestKey, MAP_TYPE, () -> {
                        supplierCalls.incrementAndGet();
                        ownerEntered.countDown();
                        sleep(350L);
                        return Map.of("like", 42L);
                    }), executor);

            assertThat(ownerEntered.await(2, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Map<String, Long>> follower = CompletableFuture.supplyAsync(() ->
                    singleFlightService.execute("counter-sds", requestKey, MAP_TYPE, () -> {
                        supplierCalls.incrementAndGet();
                        return Map.of("like", 0L);
                    }), executor);

            assertThat(owner.get(5, TimeUnit.SECONDS)).containsEntry("like", 42L);
            assertThat(follower.get(5, TimeUnit.SECONDS)).containsEntry("like", 42L);
            assertThat(supplierCalls).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleOwnerHeartbeatAllowsTakeoverBeforeMetaTtlExpires() throws Exception {
        String requestKey = uniqueRequestKey("takeover");
        String repositoryKey = "counter-sds:" + requestKey;
        addFlightKeys(repositoryKey);
        SingleFlightPolicy policy = new SingleFlightPolicy(
                3000L,
                5000L,
                1000L,
                1000L,
                100L,
                100L,
                200L,
                1000L,
                false,
                1L,
                10,
                Integer.MAX_VALUE,
                "gzip"
        );

        SingleFlightAcquireResult first = coordinatorRepository.acquireOrJoin("counter-sds", repositoryKey, "owner-a", policy);
        assertThat(first.action()).isEqualTo(SingleFlightAction.OWNER_NEW);
        assertThat(coordinatorRepository.markRunning(repositoryKey, "owner-a", first.ownerToken(), policy.runningTtlMillis()))
                .isTrue();

        Thread.sleep(350L);
        SingleFlightAcquireResult second = coordinatorRepository.acquireOrJoin("counter-sds", repositoryKey, "owner-b", policy);

        assertThat(second.action()).isEqualTo(SingleFlightAction.OWNER_TAKEOVER);
        assertThat(second.ownerToken()).isGreaterThan(first.ownerToken());
    }

    private String uniqueRequestKey(String suffix) {
        return "it:" + suffix + ":" + UUID.randomUUID();
    }

    private void addFlightKeys(String repositoryKey) {
        String safe = repositoryKey.replaceAll("[^A-Za-z0-9:_-]", "_");
        touchedKeys.add("zg:singleflight:stream:" + safe);
        touchedPatterns.add("zg:singleflight:meta:*:" + safe);
        touchedPatterns.add("zg:singleflight:result:*:" + safe);
    }

    private String streamKey(String requestKey) {
        return "zg:singleflight:stream:" + requestKey.replaceAll("[^A-Za-z0-9:_-]", "_");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
    }

    static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Configuration
    @ImportAutoConfiguration({RedisAutoConfiguration.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        SingleFlightProperties singleFlightProperties() {
            SingleFlightProperties properties = new SingleFlightProperties();
            SingleFlightProperties.SingleFlightPolicyDefaults defaults =
                    new SingleFlightProperties.SingleFlightPolicyDefaults();
            defaults.setRunningTtlMillis(3000L);
            defaults.setResultTtlMillis(5000L);
            defaults.setFailedResultTtlMillis(1000L);
            defaults.setFollowerMaxWaitMillis(3000L);
            defaults.setStreamBlockTimeoutMillis(500L);
            defaults.setPollFallbackIntervalMillis(100L);
            defaults.setTakeoverDetectMillis(1000L);
            defaults.setHeartbeatIntervalMillis(500L);
            defaults.setL1CacheEnabled(false);
            defaults.setL1CacheTtlMillis(1L);
            properties.setDefaults(defaults);
            return properties;
        }

        @Bean
        LocalSingleFlightService localSingleFlightService() {
            return new LocalSingleFlightService();
        }

        @Bean
        RedisSingleFlightCoordinatorRepository redisSingleFlightCoordinatorRepository(StringRedisTemplate redis) {
            return new RedisSingleFlightCoordinatorRepository(redis);
        }

        @Bean
        RedisSingleFlightNotificationService redisSingleFlightNotificationService(StringRedisTemplate redis) {
            return new RedisSingleFlightNotificationService(redis);
        }

        @Bean
        SingleFlightHeartbeatManager singleFlightHeartbeatManager() {
            return new SingleFlightHeartbeatManager();
        }

        @Bean
        SingleFlightResultCodec singleFlightResultCodec(ObjectMapper objectMapper) {
            return new SingleFlightResultCodec(objectMapper);
        }

        @Bean
        SingleFlightLocalReplayCache singleFlightLocalReplayCache() {
            return new SingleFlightLocalReplayCache();
        }

        @Bean
        DistributedSingleFlightService distributedSingleFlightService(SingleFlightProperties properties,
                                                                      LocalSingleFlightService localSingleFlightService,
                                                                      RedisSingleFlightCoordinatorRepository coordinatorRepository,
                                                                      RedisSingleFlightNotificationService notificationService,
                                                                      SingleFlightHeartbeatManager heartbeatManager,
                                                                      SingleFlightResultCodec resultCodec,
                                                                      SingleFlightLocalReplayCache localReplayCache) {
            return new DistributedSingleFlightService(
                    properties,
                    localSingleFlightService,
                    coordinatorRepository,
                    notificationService,
                    heartbeatManager,
                    resultCodec,
                    localReplayCache
            );
        }
    }
}

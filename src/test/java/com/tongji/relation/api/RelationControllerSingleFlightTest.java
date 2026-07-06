package com.tongji.relation.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.auth.token.JwtService;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.counter.service.UserCounterRebuildAdapter;
import com.tongji.relation.manager.RelationManager;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.service.RelationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationControllerSingleFlightTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void missingUserCounterUsesDistributedSingleFlight() {
        RelationManager relationManager = mock(RelationManager.class);
        RelationService relationService = mock(RelationService.class);
        JwtService jwtService = mock(JwtService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        UserCounterRebuildAdapter adapter = mock(UserCounterRebuildAdapter.class);
        RelationMapper relationMapper = mock(RelationMapper.class);
        DistributedSingleFlightService singleFlightService = mock(DistributedSingleFlightService.class);
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("followings", 1L);
        expected.put("followers", 2L);
        expected.put("posts", 3L);
        expected.put("likedPosts", 4L);
        expected.put("favedPosts", 5L);

        when(redis.execute(any(RedisCallback.class))).thenReturn(null);
        doReturn(expected).when(singleFlightService).execute(
                eq("user-counter"),
                eq("42"),
                any(TypeReference.class),
                any(Supplier.class)
        );
        RelationController controller = new RelationController(
                relationManager,
                relationService,
                jwtService,
                redis,
                adapter,
                relationMapper,
                singleFlightService
        );

        Map<String, Long> result = controller.counter(42L);

        assertThat(result).isEqualTo(expected);
    }
}

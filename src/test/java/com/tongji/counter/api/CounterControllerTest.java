package com.tongji.counter.api;

import com.tongji.counter.api.dto.BatchCountsResponse;
import com.tongji.counter.service.CounterService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CounterControllerTest {

    @Test
    void batchReadIncludesCommentMetricWithoutPerItemCalls() {
        CounterService counterService = mock(CounterService.class);
        CounterController controller = new CounterController(counterService);
        List<String> metrics = List.of("like", "fav", "comment");
        Map<String, Map<String, Long>> counts = Map.of(
                "101", Map.of("like", 5L, "fav", 3L, "comment", 8L),
                "102", Map.of("like", 2L, "fav", 1L, "comment", 4L)
        );
        when(counterService.getCountsBatch("knowpost", List.of("101", "102"), metrics)).thenReturn(counts);

        BatchCountsResponse response = controller
                .getCountsBatch("knowpost", List.of("101", "102"), "like,fav,comment")
                .getBody();

        assertThat(response).isNotNull();
        assertThat(response.countsByEntityId().get("101").get("comment")).isEqualTo(8L);
        verify(counterService).getCountsBatch("knowpost", List.of("101", "102"), metrics);
    }
}

package com.tongji.relation.api;

import com.tongji.auth.token.JwtService;
import com.tongji.counter.service.UserCounterReader;
import com.tongji.counter.service.UserCounters;
import com.tongji.relation.manager.RelationManager;
import com.tongji.relation.service.RelationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationControllerCounterTest {

    @Test
    void counterDelegatesToVerifiedReader() {
        RelationManager relationManager = mock(RelationManager.class);
        RelationService relationService = mock(RelationService.class);
        JwtService jwtService = mock(JwtService.class);
        UserCounterReader reader = mock(UserCounterReader.class);
        UserCounters expected = new UserCounters(1L, 2L, 3L, 4L, 5L);
        when(reader.getVerified(42L)).thenReturn(expected);

        RelationController controller = new RelationController(
                relationManager,
                relationService,
                jwtService,
                reader
        );

        assertThat(controller.counter(42L)).isEqualTo(expected);
        verify(reader).getVerified(42L);
    }
}

package com.tongji.capability.api;

import com.tongji.auth.config.SecurityConfig;
import com.tongji.auth.token.JwtService;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.reconciliation.api.ReconciliationController;
import com.tongji.reconciliation.config.ReconciliationOperatorProperties;
import com.tongji.reconciliation.security.ReconciliationAuthorization;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.wallet.config.ContentRewardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({CapabilityController.class, ReconciliationController.class})
@Import({SecurityConfig.class, ReconciliationAuthorization.class, ReconciliationOperatorProperties.class})
@TestPropertySource(properties = "reconciliation.operator-user-ids=7")
class CapabilityAuthorizationContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private PromotionBPrimeProperties promotionProperties;

    @MockBean
    private ContentRewardProperties contentRewardProperties;

    @MockBean
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        when(jwtService.extractUserId(any(Jwt.class)))
                .thenAnswer(invocation -> ((Jwt) invocation.getArgument(0)).getClaim("uid"));
    }

    @Test
    void capabilitiesExposeRuntimeBackendStateWithoutAuthentication() throws Exception {
        when(promotionProperties.isEnabled()).thenReturn(true);
        when(contentRewardProperties.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotionAuction.enabled").value(true))
                .andExpect(jsonPath("$.promotionAuction.transport").value("native-websocket"))
                .andExpect(jsonPath("$.contentReward.enabled").value(false));
    }

    @Test
    void reconciliationRejectsAnonymousAndOrdinaryUsers() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliation/tasks"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/reconciliation/tasks")
                        .with(jwt().jwt(token -> token.claim("uid", 8L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconciliationAllowsConfiguredOperator() throws Exception {
        when(reconciliationService.query(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/reconciliation/tasks")
                        .with(jwt().jwt(token -> token.claim("uid", 7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}

package com.tongji.wallet.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.wallet.api.dto.WalletLedgerItemResponse;
import com.tongji.wallet.api.dto.WalletLedgerResponse;
import com.tongji.wallet.model.WalletAccount;
import com.tongji.wallet.model.WalletAccountStatus;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerEntry;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletControllerTest {

    private static final long USER_ID = 1001L;

    private WalletService walletService;
    private JwtService jwtService;
    private MockMvc mockMvc;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        walletService = mock(WalletService.class);
        jwtService = mock(JwtService.class);
        WalletController controller = new WalletController(walletService, jwtService);

        jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("uid", USER_ID)
        );
        when(jwtService.extractUserId(jwt)).thenReturn(USER_ID);

        HandlerMethodArgumentResolver jwtResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(Jwt.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                return jwt;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(jwtResolver)
                .build();
    }

    @Test
    void meReturnsBalancesForAuthenticatedUser() throws Exception {
        when(walletService.getAccountOrThrow(USER_ID)).thenReturn(account(100L, 20L, 30L));

        mockMvc.perform(get("/api/v1/wallet/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(100))
                .andExpect(jsonPath("$.heldBalance").value(20))
                .andExpect(jsonPath("$.escrowedBalance").value(30))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(jwtService).extractUserId(jwt);
        verify(walletService).getAccountOrThrow(USER_ID);
    }

    @Test
    void meReturnsBadRequestWhenWalletMissing() throws Exception {
        when(walletService.getAccountOrThrow(USER_ID)).thenThrow(new BusinessException(ErrorCode.WALLET_NOT_FOUND));

        mockMvc.perform(get("/api/v1/wallet/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    void ledgerReturnsItemsForFirstPage() throws Exception {
        Instant created = Instant.now();
        WalletLedgerEntry entry = WalletLedgerEntry.builder()
                .id(7001L)
                .businessType(WalletBusinessType.REGISTRATION)
                .businessRef("registration-grant:user:1001")
                .direction(WalletLedgerDirection.CREDIT)
                .reason(WalletLedgerReason.REGISTRATION_GRANT)
                .amount(100L)
                .availableDelta(100L)
                .heldDelta(0L)
                .escrowedDelta(0L)
                .balanceAvailableAfter(100L)
                .balanceHeldAfter(0L)
                .balanceEscrowedAfter(0L)
                .createdAt(created)
                .build();
        when(walletService.listLedger(eq(USER_ID), eq(20), eq(0))).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/wallet/me/ledger")
                        .queryParam("page", "1")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amount").value(100))
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.items[0].reason").value("REGISTRATION_GRANT"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20));

        verify(walletService).listLedger(eq(USER_ID), eq(20), eq(0));
    }

    @Test
    void ledgerAppliesPagingOffsetForSecondPage() throws Exception {
        when(walletService.listLedger(eq(USER_ID), eq(10), eq(10)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/wallet/me/ledger")
                        .queryParam("page", "2")
                        .queryParam("pageSize", "10"))
                .andExpect(status().isOk());

        verify(walletService).listLedger(eq(USER_ID), anyInt(), anyInt());
    }

    private WalletAccount account(long available, long held, long escrowed) {
        Instant now = Instant.now();
        return WalletAccount.builder()
                .ownerUserId(USER_ID)
                .availableBalance(available)
                .heldBalance(held)
                .escrowedBalance(escrowed)
                .status(WalletAccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}

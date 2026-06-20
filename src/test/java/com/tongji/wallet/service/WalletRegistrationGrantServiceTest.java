package com.tongji.wallet.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.user.domain.User;
import com.tongji.user.service.UserService;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WalletRegistrationGrantServiceTest {

    private static final long NEW_USER_ID = 42L;

    private UserService userService;
    private WalletService walletService;
    private WalletRegistrationGrantService grantService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        walletService = mock(WalletService.class);
        grantService = new WalletRegistrationGrantService(userService, walletService);
    }

    @Test
    void createUserAndGrantCreatesUserInitializesWalletAndGrants() {
        User input = User.builder().nickname("u").build();
        when(userService.createUser(input)).thenReturn(User.builder().id(NEW_USER_ID).nickname("u").build());

        User created = grantService.createUserAndGrant(input, 100L);

        assertThat(created.getId()).isEqualTo(NEW_USER_ID);
        verify(userService).createUser(input);
        verify(walletService).initializeIfAbsent(NEW_USER_ID);
        verify(walletService).grant(eq(NEW_USER_ID), eq(100L), eq(WalletLedgerReason.REGISTRATION_GRANT),
                eq(WalletBusinessType.REGISTRATION), eq("registration-grant:user:" + NEW_USER_ID));
    }

    @Test
    void createUserAndGrantSkipsGrantWhenAmountNotPositive() {
        User input = User.builder().nickname("u").build();
        when(userService.createUser(input)).thenReturn(User.builder().id(NEW_USER_ID).build());

        grantService.createUserAndGrant(input, 0L);

        verify(walletService).initializeIfAbsent(NEW_USER_ID);
        verify(walletService, never()).grant(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void duplicateIdentifierFailsBeforeWalletGrant() {
        User input = User.builder().nickname("u").build();
        when(userService.createUser(input)).thenThrow(new BusinessException(ErrorCode.IDENTIFIER_EXISTS));

        assertThatThrownBy(() -> grantService.createUserAndGrant(input, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.IDENTIFIER_EXISTS));

        verify(walletService, never()).initializeIfAbsent(anyLong());
        verify(walletService, never()).grant(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void grantFailurePropagatesAndAbortsUserCreationPath() {
        User input = User.builder().nickname("u").build();
        when(userService.createUser(input)).thenReturn(User.builder().id(NEW_USER_ID).build());
        when(walletService.grant(eq(NEW_USER_ID), eq(100L), eq(WalletLedgerReason.REGISTRATION_GRANT),
                eq(WalletBusinessType.REGISTRATION), eq("registration-grant:user:" + NEW_USER_ID)))
                .thenThrow(new IllegalStateException("grant write failed"));

        assertThatThrownBy(() -> grantService.createUserAndGrant(input, 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("grant write failed");
    }
}

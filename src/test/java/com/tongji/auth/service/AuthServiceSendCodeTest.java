package com.tongji.auth.service;

import com.tongji.auth.api.dto.SendCodeRequest;
import com.tongji.auth.api.dto.SendCodeResponse;
import com.tongji.auth.audit.LoginLogService;
import com.tongji.auth.config.AuthProperties;
import com.tongji.auth.model.IdentifierType;
import com.tongji.auth.token.JwtService;
import com.tongji.auth.token.RefreshTokenStore;
import com.tongji.auth.verification.SendCodeResult;
import com.tongji.auth.verification.VerificationScene;
import com.tongji.auth.verification.VerificationService;
import com.tongji.user.service.UserService;
import com.tongji.wallet.config.WalletProperties;
import com.tongji.wallet.service.WalletRegistrationGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceSendCodeTest {

    private static final String PHONE = "13800138000";
    private static final String CODE = "123456";

    @Mock
    private UserService userService;
    @Mock
    private VerificationService verificationService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private LoginLogService loginLogService;
    @Mock
    private WalletRegistrationGrantService walletRegistrationGrantService;

    private AuthProperties authProperties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authService = new AuthService(
                userService,
                verificationService,
                passwordEncoder,
                jwtService,
                refreshTokenStore,
                loginLogService,
                authProperties,
                walletRegistrationGrantService,
                new WalletProperties()
        );
    }

    @Test
    void shouldReturnDemoCodeForRegistrationWhenEnabled() {
        authProperties.getVerification().setDemoCodeEnabled(true);
        when(userService.existsByPhone(PHONE)).thenReturn(false);
        when(verificationService.sendCode(VerificationScene.REGISTER, PHONE))
                .thenReturn(new SendCodeResult(PHONE, VerificationScene.REGISTER, 300, CODE));

        SendCodeResponse response = authService.sendCode(
                new SendCodeRequest(VerificationScene.REGISTER, IdentifierType.PHONE, PHONE)
        );

        assertThat(response.demoCode()).isEqualTo(CODE);
    }

    @Test
    void shouldNotReturnDemoCodeForRegistrationWhenDisabled() {
        authProperties.getVerification().setDemoCodeEnabled(false);
        when(userService.existsByPhone(PHONE)).thenReturn(false);
        when(verificationService.sendCode(VerificationScene.REGISTER, PHONE))
                .thenReturn(new SendCodeResult(PHONE, VerificationScene.REGISTER, 300, CODE));

        SendCodeResponse response = authService.sendCode(
                new SendCodeRequest(VerificationScene.REGISTER, IdentifierType.PHONE, PHONE)
        );

        assertThat(response.demoCode()).isNull();
    }

    @Test
    void shouldReturnDemoCodeForLoginWhenEnabled() {
        authProperties.getVerification().setDemoCodeEnabled(true);
        when(userService.existsByPhone(PHONE)).thenReturn(true);
        when(verificationService.sendCode(VerificationScene.LOGIN, PHONE))
                .thenReturn(new SendCodeResult(PHONE, VerificationScene.LOGIN, 300, CODE));

        SendCodeResponse response = authService.sendCode(
                new SendCodeRequest(VerificationScene.LOGIN, IdentifierType.PHONE, PHONE)
        );

        assertThat(response.demoCode()).isEqualTo(CODE);
    }

    @Test
    void shouldNotReturnDemoCodeForPasswordResetWhenEnabled() {
        authProperties.getVerification().setDemoCodeEnabled(true);
        when(userService.existsByPhone(PHONE)).thenReturn(true);
        when(verificationService.sendCode(VerificationScene.RESET_PASSWORD, PHONE))
                .thenReturn(new SendCodeResult(PHONE, VerificationScene.RESET_PASSWORD, 300, CODE));

        SendCodeResponse response = authService.sendCode(
                new SendCodeRequest(VerificationScene.RESET_PASSWORD, IdentifierType.PHONE, PHONE)
        );

        assertThat(response.demoCode()).isNull();
    }
}

package com.tongji.wallet.api;

import com.tongji.auth.token.JwtService;
import com.tongji.wallet.api.dto.WalletBalanceResponse;
import com.tongji.wallet.api.dto.WalletLedgerItemResponse;
import com.tongji.wallet.api.dto.WalletLedgerResponse;
import com.tongji.wallet.model.WalletAccount;
import com.tongji.wallet.model.WalletLedgerEntry;
import com.tongji.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 钱包只读查询 API：当前用户余额与流水。本期不暴露充值 / 提现 / 转账写接口。
 */
@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
public class WalletController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WalletService walletService;
    private final JwtService jwtService;

    /**
     * 查询当前登录用户钱包余额。
     */
    @GetMapping("/me")
    public WalletBalanceResponse me(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        WalletAccount account = walletService.getAccountOrThrow(userId);
        return new WalletBalanceResponse(
                account.getAvailableBalance(),
                account.getHeldBalance(),
                account.getEscrowedBalance(),
                account.getStatus().name()
        );
    }

    /**
     * 分页查询当前登录用户流水（倒序）。
     */
    @GetMapping("/me/ledger")
    public WalletLedgerResponse ledger(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        long userId = jwtService.extractUserId(jwt);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;
        List<WalletLedgerEntry> entries = walletService.listLedger(userId, safePageSize, offset);
        List<WalletLedgerItemResponse> items = entries.stream().map(WalletController::toItem).toList();
        return new WalletLedgerResponse(items, safePage, safePageSize);
    }

    private static WalletLedgerItemResponse toItem(WalletLedgerEntry entry) {
        return new WalletLedgerItemResponse(
                entry.getId(),
                entry.getBusinessType().name(),
                entry.getDirection().name(),
                entry.getReason().name(),
                entry.getAmount(),
                entry.getAvailableDelta(),
                entry.getHeldDelta(),
                entry.getEscrowedDelta(),
                entry.getBalanceAvailableAfter(),
                entry.getBalanceHeldAfter(),
                entry.getBalanceEscrowedAfter(),
                entry.getBusinessRef(),
                entry.getCreatedAt()
        );
    }
}

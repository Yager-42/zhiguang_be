package com.tongji.wallet;

import com.tongji.wallet.mapper.WalletAccountMapper;
import com.tongji.wallet.mapper.WalletEscrowMapper;
import com.tongji.wallet.mapper.WalletLedgerMapper;
import com.tongji.wallet.model.WalletAccount;
import com.tongji.wallet.model.WalletAccountStatus;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletEscrow;
import com.tongji.wallet.model.WalletEscrowStatus;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerEntry;
import com.tongji.wallet.model.WalletLedgerReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钱包持久化层 MySQL 集成测试：account / ledger / escrow 的 delta 更新、FOR UPDATE、条件状态迁移与约束。
 * <p>
 * 风格对齐 {@link com.tongji.common.id.IdServiceMysqlIntegrationTest}；
 * 无 Docker 时通过 {@code @EnabledIf} 跳过。
 */
@SpringBootTest(classes = WalletMysqlIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/zhiguang?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=zhiguang",
        "spring.datasource.password=zhiguang123456",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@EnabledIf("mysqlReachable")
class WalletMysqlIntegrationTest {

    @Autowired
    private WalletAccountMapper walletAccountMapper;

    @Autowired
    private WalletLedgerMapper walletLedgerMapper;

    @Autowired
    private WalletEscrowMapper walletEscrowMapper;

    @Test
    void applyBalanceDeltasUsesDeltaUpdateAndNonNegativeGuard() {
        long owner = uniqueOwnerUserId();
        insertAccount(owner, 100L, 0L, 0L);

        assertThat(walletAccountMapper.applyBalanceDeltas(owner, 50L, 0L, 0L)).isEqualTo(1);
        assertThat(walletAccountMapper.findByOwnerUserId(owner).getAvailableBalance()).isEqualTo(150L);

        // 会让余额变负的 delta 被非负 WHERE 拦截，返回 0，余额不变
        assertThat(walletAccountMapper.applyBalanceDeltas(owner, -9999L, 0L, 0L)).isEqualTo(0);
        assertThat(walletAccountMapper.findByOwnerUserId(owner).getAvailableBalance()).isEqualTo(150L);

        assertThat(walletAccountMapper.findByOwnerUserIdForUpdate(owner)).isNotNull();
    }

    @Test
    void ledgerFindByOwnerAndRefAndListRoundTrips() {
        long owner = uniqueOwnerUserId();
        long ledgerId = uniqueLedgerId();
        String ref = "it-ledger:" + ledgerId;
        Instant now = Instant.now();
        WalletLedgerEntry entry = WalletLedgerEntry.builder()
                .id(ledgerId).ownerUserId(owner).counterpartyUserId(0L).escrowId(null)
                .businessType(WalletBusinessType.REGISTRATION).businessRef(ref)
                .direction(WalletLedgerDirection.CREDIT).reason(WalletLedgerReason.REGISTRATION_GRANT)
                .amount(100L).availableDelta(100L).heldDelta(0L).escrowedDelta(0L)
                .balanceAvailableAfter(100L).balanceHeldAfter(0L).balanceEscrowedAfter(0L)
                .createdAt(now).build();
        assertThat(walletLedgerMapper.insert(entry)).isEqualTo(1);

        assertThat(walletLedgerMapper.findByOwnerUserIdAndBusinessRef(owner, ref)).isNotNull();
        List<WalletLedgerEntry> byRef = walletLedgerMapper.findByBusinessRef(ref);
        assertThat(byRef).extracting(WalletLedgerEntry::getOwnerUserId).contains(owner);
        assertThat(walletLedgerMapper.listByOwnerUserId(owner, 10, 0))
                .extracting(WalletLedgerEntry::getBusinessRef).contains(ref);
    }

    @Test
    void ledgerRejectsNonPositiveAmount() {
        long owner = uniqueOwnerUserId();
        long ledgerId = uniqueLedgerId();
        WalletLedgerEntry entry = WalletLedgerEntry.builder()
                .id(ledgerId).ownerUserId(owner).counterpartyUserId(0L).escrowId(null)
                .businessType(WalletBusinessType.SYSTEM).businessRef("it-zero:" + ledgerId)
                .direction(WalletLedgerDirection.CREDIT).reason(WalletLedgerReason.PLATFORM_SUBSIDY)
                .amount(0L).availableDelta(0L).heldDelta(0L).escrowedDelta(0L)
                .balanceAvailableAfter(0L).balanceHeldAfter(0L).balanceEscrowedAfter(0L)
                .createdAt(Instant.now()).build();
        assertThatThrownBy(() -> walletLedgerMapper.insert(entry)).isInstanceOf(Exception.class);
    }

    @Test
    void ledgerUniqueKeyOwnerBusinessRefRejectsDuplicate() {
        long owner = uniqueOwnerUserId();
        long ledgerId = uniqueLedgerId();
        String ref = "it-uniq:" + ledgerId;
        Instant now = Instant.now();
        WalletLedgerEntry first = WalletLedgerEntry.builder()
                .id(ledgerId).ownerUserId(owner).counterpartyUserId(0L).escrowId(null)
                .businessType(WalletBusinessType.REGISTRATION).businessRef(ref)
                .direction(WalletLedgerDirection.CREDIT).reason(WalletLedgerReason.REGISTRATION_GRANT)
                .amount(100L).availableDelta(100L).heldDelta(0L).escrowedDelta(0L)
                .balanceAvailableAfter(100L).balanceHeldAfter(0L).balanceEscrowedAfter(0L)
                .createdAt(now).build();
        assertThat(walletLedgerMapper.insert(first)).isEqualTo(1);

        // 同 owner_user_id + 同 business_ref 第二条被唯一键拒绝（幂等判等回读的持久化前提）
        WalletLedgerEntry second = WalletLedgerEntry.builder()
                .id(uniqueLedgerId()).ownerUserId(owner).counterpartyUserId(0L).escrowId(null)
                .businessType(WalletBusinessType.REGISTRATION).businessRef(ref)
                .direction(WalletLedgerDirection.CREDIT).reason(WalletLedgerReason.REGISTRATION_GRANT)
                .amount(100L).availableDelta(100L).heldDelta(0L).escrowedDelta(0L)
                .balanceAvailableAfter(200L).balanceHeldAfter(0L).balanceEscrowedAfter(0L)
                .createdAt(now).build();
        assertThatThrownBy(() -> walletLedgerMapper.insert(second)).isInstanceOf(Exception.class);
    }

    @Test
    void escrowTransitionStatusIsConditional() {
        long escrowId = uniqueLedgerId();
        String ref = "it-escrow:" + escrowId;
        Instant now = Instant.now();
        WalletEscrow escrow = WalletEscrow.builder()
                .id(escrowId).businessType(WalletBusinessType.BOUNTY).businessRef(ref)
                .payerUserId(uniqueOwnerUserId()).payeeUserId(uniqueOwnerUserId()).amount(500L)
                .status(WalletEscrowStatus.CREATED).expiresAt(null).createdAt(now).updatedAt(now).build();
        assertThat(walletEscrowMapper.insert(escrow)).isEqualTo(1);

        assertThat(walletEscrowMapper.transitionStatus(escrowId, WalletEscrowStatus.CREATED, WalletEscrowStatus.LOCKED)).isEqualTo(1);
        assertThat(walletEscrowMapper.findById(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.LOCKED);
        assertThat(walletEscrowMapper.findByIdForUpdate(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.LOCKED);

        // 状态已非 CREATED，重复条件迁移返回 0，不双改
        assertThat(walletEscrowMapper.transitionStatus(escrowId, WalletEscrowStatus.CREATED, WalletEscrowStatus.LOCKED)).isEqualTo(0);
        assertThat(walletEscrowMapper.findById(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.LOCKED);
    }

    private void insertAccount(long owner, long available, long held, long escrowed) {
        Instant now = Instant.now();
        walletAccountMapper.insert(WalletAccount.builder()
                .ownerUserId(owner).availableBalance(available).heldBalance(held).escrowedBalance(escrowed)
                .status(WalletAccountStatus.ACTIVE).createdAt(now).updatedAt(now).build());
    }

    private long uniqueOwnerUserId() {
        return 9_900_000_000L + (System.nanoTime() % 1_000_000L);
    }

    private long uniqueLedgerId() {
        return 8_800_000_000L + (System.nanoTime() % 1_000_000L);
    }

    static boolean mysqlReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 3306), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Configuration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    @MapperScan(basePackageClasses = WalletAccountMapper.class)
    static class TestConfig {
    }
}

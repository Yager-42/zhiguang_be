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

/**
 * 钱包持久化层 MySQL 集成测试：account / ledger / escrow 三张表的最小 insert/select 往返。
 * <p>
 * 风格对齐 {@link com.tongji.common.id.IdServiceMysqlIntegrationTest}：
 * 真实 MySQL 切片 + MyBatis 自动装配，无 Docker 时通过 {@code @EnabledIf} 跳过。
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
    void accountInsertAndFindByOwnerRoundTrips() {
        long ownerUserId = uniqueOwnerUserId();
        Instant now = Instant.now();
        WalletAccount account = WalletAccount.builder()
                .ownerUserId(ownerUserId)
                .availableBalance(100L)
                .heldBalance(20L)
                .escrowedBalance(30L)
                .status(WalletAccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        assertThat(walletAccountMapper.insert(account)).isEqualTo(1);

        WalletAccount loaded = walletAccountMapper.findByOwnerUserId(ownerUserId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAvailableBalance()).isEqualTo(100L);
        assertThat(loaded.getHeldBalance()).isEqualTo(20L);
        assertThat(loaded.getEscrowedBalance()).isEqualTo(30L);
        assertThat(loaded.getStatus()).isEqualTo(WalletAccountStatus.ACTIVE);
    }

    @Test
    void ledgerInsertAndFindByBusinessRefRoundTrips() {
        long ownerUserId = uniqueOwnerUserId();
        long ledgerId = uniqueLedgerId();
        String businessRef = "it-ledger:" + ledgerId;
        Instant now = Instant.now();
        WalletLedgerEntry entry = WalletLedgerEntry.builder()
                .id(ledgerId)
                .ownerUserId(ownerUserId)
                .counterpartyUserId(0L)
                .escrowId(null)
                .businessType(WalletBusinessType.REGISTRATION)
                .businessRef(businessRef)
                .direction(WalletLedgerDirection.CREDIT)
                .reason(WalletLedgerReason.REGISTRATION_GRANT)
                .amount(100L)
                .availableDelta(100L)
                .heldDelta(0L)
                .escrowedDelta(0L)
                .balanceAvailableAfter(100L)
                .balanceHeldAfter(0L)
                .balanceEscrowedAfter(0L)
                .createdAt(now)
                .build();
        assertThat(walletLedgerMapper.insert(entry)).isEqualTo(1);

        WalletLedgerEntry loaded = walletLedgerMapper.findByBusinessRef(businessRef);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOwnerUserId()).isEqualTo(ownerUserId);
        assertThat(loaded.getDirection()).isEqualTo(WalletLedgerDirection.CREDIT);
        assertThat(loaded.getReason()).isEqualTo(WalletLedgerReason.REGISTRATION_GRANT);
        assertThat(loaded.getBusinessType()).isEqualTo(WalletBusinessType.REGISTRATION);
        assertThat(loaded.getBalanceAvailableAfter()).isEqualTo(100L);

        List<WalletLedgerEntry> listed = walletLedgerMapper.listByOwnerUserId(ownerUserId, 10, 0);
        assertThat(listed).extracting(WalletLedgerEntry::getBusinessRef).contains(businessRef);
    }

    @Test
    void escrowInsertAndFindRoundTrips() {
        long escrowId = uniqueLedgerId();
        String businessRef = "it-escrow:" + escrowId;
        Instant now = Instant.now();
        WalletEscrow escrow = WalletEscrow.builder()
                .id(escrowId)
                .businessType(WalletBusinessType.BOUNTY)
                .businessRef(businessRef)
                .payerUserId(uniqueOwnerUserId())
                .payeeUserId(uniqueOwnerUserId())
                .amount(500L)
                .status(WalletEscrowStatus.CREATED)
                .expiresAt(now.plusSeconds(3600))
                .createdAt(now)
                .updatedAt(now)
                .build();
        assertThat(walletEscrowMapper.insert(escrow)).isEqualTo(1);

        WalletEscrow byId = walletEscrowMapper.findById(escrowId);
        assertThat(byId).isNotNull();
        assertThat(byId.getAmount()).isEqualTo(500L);
        assertThat(byId.getStatus()).isEqualTo(WalletEscrowStatus.CREATED);

        WalletEscrow byRef = walletEscrowMapper.findByBusinessRef(businessRef);
        assertThat(byRef).isNotNull();
        assertThat(byRef.getId()).isEqualTo(escrowId);

        byRef.setStatus(WalletEscrowStatus.LOCKED);
        byRef.setUpdatedAt(Instant.now());
        assertThat(walletEscrowMapper.updateStatus(byRef)).isEqualTo(1);
        assertThat(walletEscrowMapper.findById(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.LOCKED);
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

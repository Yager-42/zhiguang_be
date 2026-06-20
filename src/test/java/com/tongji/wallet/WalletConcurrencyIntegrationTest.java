package com.tongji.wallet;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.wallet.config.WalletProperties;
import com.tongji.wallet.mapper.WalletAccountMapper;
import com.tongji.wallet.mapper.WalletEscrowMapper;
import com.tongji.wallet.mapper.WalletLedgerMapper;
import com.tongji.wallet.model.WalletAccount;
import com.tongji.wallet.model.WalletAccountStatus;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletEscrow;
import com.tongji.wallet.model.WalletEscrowStatus;
import com.tongji.wallet.model.WalletLedgerEntry;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletEscrowService;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真·多线程并发幂等集成测试：多线程同 {@code businessRef} 重试 grant / hold / direct release，
 * 必须最终只产生一次 movement、对所有线程表现为幂等成功，不报唯一键错误、不报余额错误、不重复转账。
 * <p>
 * 这是对单线程 Mockito 两段式模拟 + 行锁/唯一键前提之外的并行级证据。
 */
@SpringBootTest(classes = WalletConcurrencyIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/zhiguang?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=zhiguang",
        "spring.datasource.password=zhiguang123456",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.maximum-pool-size=32",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@EnabledIf("mysqlReachable")
class WalletConcurrencyIntegrationTest {

    private static final int THREADS = 16;
    private static final long PAYER_BASE = 7_700_000_000L;
    private static final long PAYEE_BASE = 8_800_000_000L;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletEscrowService escrowService;

    @Autowired
    private WalletAccountMapper walletAccountMapper;

    @Autowired
    private WalletLedgerMapper walletLedgerMapper;

    @Autowired
    private WalletEscrowMapper walletEscrowMapper;

    @Test
    void walletServiceBeanIsTransactionProxy() {
        // 诊断：确认 @Transactional 在该切片上下文里真的被代理；若不是代理，FOR UPDATE 不会跨语句持锁
        assertThat(walletService.getClass().getName())
                .as("WalletService 必须是事务代理（CGLIB），否则 @Transactional 不生效")
                .contains("SpringCGLIB");
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentGrantSameBusinessRefProducesSingleLedgerAndNoErrors() throws Exception {
        long owner = uniqueOwnerUserId(PAYER_BASE);
        insertAccount(owner, 0L, 0L, 0L);
        String ref = "concur:grant:" + owner;

        runConcurrently(() -> walletService.grant(owner, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, ref));

        WalletLedgerEntry single = walletLedgerMapper.findByOwnerUserIdAndBusinessRef(owner, ref);
        assertThat(single).as("并发同 ref 只应产生一条 ledger").isNotNull();
        assertThat(single.getAmount()).isEqualTo(100L);
        WalletAccount after = walletAccountMapper.findByOwnerUserId(owner);
        assertThat(after.getAvailableBalance()).as("grant 只生效一次").isEqualTo(100L);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentHoldSameBusinessRefProducesSingleLedgerAndNoErrors() throws Exception {
        long owner = uniqueOwnerUserId(PAYER_BASE);
        insertAccount(owner, 1000L, 0L, 0L);
        String ref = "concur:hold:" + owner;

        runConcurrently(() -> walletService.hold(owner, 100L, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, ref));

        assertThat(walletLedgerMapper.findByOwnerUserIdAndBusinessRef(owner, ref)).isNotNull();
        WalletAccount after = walletAccountMapper.findByOwnerUserId(owner);
        assertThat(after.getAvailableBalance()).isEqualTo(900L);
        assertThat(after.getHeldBalance()).isEqualTo(100L);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentCreateEscrowSameBusinessRefProducesSingleEscrowAndNoErrors() throws Exception {
        long payer = uniqueOwnerUserId(PAYER_BASE);
        long payee = uniqueOwnerUserId(PAYEE_BASE);
        insertAccount(payer, 1000L, 0L, 0L);
        String ref = "concur:create:" + payer;

        runConcurrently(() -> escrowService.createEscrow(payer, payee, 100L,
                WalletBusinessType.BOUNTY, ref, null));

        WalletEscrow single = walletEscrowMapper.findByBusinessRef(ref);
        assertThat(single).as("并发同 ref 只应创建一个 escrow").isNotNull();
        // create 只 hold 一次：payer 可用减 100、冻结加 100
        assertThat(walletLedgerMapper.findByOwnerUserIdAndBusinessRef(payer, ref)).isNotNull();
        WalletAccount after = walletAccountMapper.findByOwnerUserId(payer);
        assertThat(after.getAvailableBalance()).isEqualTo(900L);
        assertThat(after.getHeldBalance()).isEqualTo(100L);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentDirectReleaseSameBusinessRefProducesSinglePairAndNoErrors() throws Exception {
        long payer = uniqueOwnerUserId(PAYER_BASE);
        long payee = uniqueOwnerUserId(PAYEE_BASE);
        long escrowId = uniqueLedgerId();
        insertAccount(payer, 0L, 0L, 1000L);
        insertAccount(payee, 0L, 0L, 0L);
        String ref = "concur:release:" + escrowId;

        runConcurrently(() -> walletService.releaseEscrowToPayee(payer, payee, escrowId, 100L,
                WalletBusinessType.BOUNTY, ref));

        // direct release 同 ref 产生 payer/payee 各一条，不重复
        List<WalletLedgerEntry> entries = walletLedgerMapper.findByBusinessRef(ref);
        assertThat(entries).hasSize(2);
        WalletAccount payerAfter = walletAccountMapper.findByOwnerUserId(payer);
        WalletAccount payeeAfter = walletAccountMapper.findByOwnerUserId(payee);
        assertThat(payerAfter.getEscrowedBalance()).as("payer 托管只扣一次").isEqualTo(900L);
        assertThat(payeeAfter.getAvailableBalance()).as("payee 可用只加一次").isEqualTo(100L);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentDifferentOwnersSameBusinessRefOnlyOneSucceeds() throws Exception {
        // 跨 owner 并发复用同一 business_ref：只能有一组 ledger 生效，其余 owner 被 duplicate-business-ref 拒绝
        int owners = 8;
        long[] ownerIds = new long[owners];
        for (int i = 0; i < owners; i++) {
            ownerIds[i] = uniqueOwnerUserId(PAYER_BASE);
            insertAccount(ownerIds[i], 0L, 0L, 0L);
        }
        String ref = "concur:xowner:" + ownerIds[0];

        ExecutorService pool = Executors.newFixedThreadPool(owners);
        CountDownLatch ready = new CountDownLatch(owners);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> rejections = new ConcurrentLinkedQueue<>();
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < owners; i++) {
            long owner = ownerIds[i];
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    walletService.grant(owner, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                            WalletBusinessType.REGISTRATION, ref);
                    successes.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    rejections.add(t);
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdownNow();

        // 只有 1 个 owner 的 grant 成功
        assertThat(successes.get()).as("跨 owner 同 ref 只能 1 个成功").isEqualTo(1);
        // 其余全是 WALLET_DUPLICATE_BUSINESS_REF，无脏成功/其它错误
        assertThat(rejections).hasSize(owners - 1);
        for (Throwable t : rejections) {
            assertThat(t).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) t).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF);
        }
        // 同 ref 只有一组 ledger（1 条）
        assertThat(walletLedgerMapper.findByBusinessRef(ref)).as("同 ref 只产生一组 ledger").hasSize(1);
        // 只有赢家余额 +100，其余 owner 余额为 0（不变）
        int changed = 0;
        for (long owner : ownerIds) {
            long bal = walletAccountMapper.findByOwnerUserId(owner).getAvailableBalance();
            if (bal == 100L) {
                changed++;
            } else {
                assertThat(bal).as("失败 owner 余额不变").isZero();
            }
        }
        assertThat(changed).as("只有 1 个 owner 余额变化").isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentLockEscrowSameTransitionRefMovesHeldToEscrowedOnce() throws Exception {
        long payer = uniqueOwnerUserId(PAYER_BASE);
        insertAccount(payer, 1000L, 0L, 0L);
        long escrowId = escrowCreated(payer, null, 100L); // CREATED: payer available=900, held=100
        String ref = "tlock:" + escrowId + ":" + System.nanoTime();

        runConcurrently(() -> escrowService.lockEscrow(escrowId, ref));

        assertThat(walletAccountMapper.findByOwnerUserId(payer).getHeldBalance()).isZero();
        assertThat(walletAccountMapper.findByOwnerUserId(payer).getEscrowedBalance()).isEqualTo(100L);
        assertThat(walletEscrowMapper.findById(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.LOCKED);
        assertThat(walletLedgerMapper.findByBusinessRef(ref)).hasSize(1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentReleaseEscrowSameTransitionRefMovesEscrowedToPayeeOnce() throws Exception {
        long payer = uniqueOwnerUserId(PAYER_BASE);
        long payee = uniqueOwnerUserId(PAYEE_BASE);
        insertAccount(payer, 1000L, 0L, 0L);
        insertAccount(payee, 0L, 0L, 0L);
        long escrowId = escrowLocked(payer, payee, 100L); // LOCKED: payer escrowed=100, payee available=0
        String ref = "trel:" + escrowId + ":" + System.nanoTime();

        runConcurrently(() -> escrowService.releaseEscrow(escrowId, ref));

        assertThat(walletAccountMapper.findByOwnerUserId(payer).getEscrowedBalance()).isZero();
        assertThat(walletAccountMapper.findByOwnerUserId(payee).getAvailableBalance()).isEqualTo(100L);
        assertThat(walletEscrowMapper.findById(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.RELEASED);
        assertThat(walletLedgerMapper.findByBusinessRef(ref)).hasSize(2); // payer + payee 各一条
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentRefundEscrowLockedSameTransitionRefMovesEscrowedToAvailableOnce() throws Exception {
        // refund(LOCKED) 走 releaseEscrowToAvailable（escrowed -> available）。
        // refund(CREATED) 与 cancel 走同一条 releaseHold 路径，由 concurrentCancelEscrow 覆盖，此处不重复。
        long payer = uniqueOwnerUserId(PAYER_BASE);
        insertAccount(payer, 1000L, 0L, 0L);
        long escrowId = escrowLocked(payer, null, 100L); // LOCKED: payer escrowed=100, available=900
        String ref = "trefund:" + escrowId + ":" + System.nanoTime();

        runConcurrently(() -> escrowService.refundEscrow(escrowId, ref));

        assertThat(walletAccountMapper.findByOwnerUserId(payer).getEscrowedBalance()).isZero();
        assertThat(walletAccountMapper.findByOwnerUserId(payer).getAvailableBalance()).isEqualTo(1000L);
        assertThat(walletEscrowMapper.findById(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.REFUNDED);
        assertThat(walletLedgerMapper.findByBusinessRef(ref)).hasSize(1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentForfeitEscrowSameTransitionRefMovesEscrowedToPlatformOnce() throws Exception {
        long payer = uniqueOwnerUserId(PAYER_BASE);
        insertAccount(payer, 1000L, 0L, 0L);
        long escrowId = escrowLocked(payer, null, 100L);
        String ref = "tforfeit:" + escrowId + ":" + System.nanoTime();

        runConcurrently(() -> escrowService.forfeitEscrow(escrowId, ref));

        assertThat(walletAccountMapper.findByOwnerUserId(payer).getEscrowedBalance()).isZero();
        assertThat(walletEscrowMapper.findById(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.FORFEITED);
        assertThat(walletLedgerMapper.findByBusinessRef(ref)).hasSize(1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentCancelEscrowSameTransitionRefMovesHeldToAvailableOnce() throws Exception {
        long payer = uniqueOwnerUserId(PAYER_BASE);
        insertAccount(payer, 1000L, 0L, 0L);
        long escrowId = escrowCreated(payer, null, 100L); // CREATED: payer available=900, held=100
        String ref = "tcancel:" + escrowId + ":" + System.nanoTime();

        runConcurrently(() -> escrowService.cancelEscrow(escrowId, ref));

        assertThat(walletAccountMapper.findByOwnerUserId(payer).getHeldBalance()).isZero();
        assertThat(walletAccountMapper.findByOwnerUserId(payer).getAvailableBalance()).isEqualTo(1000L);
        assertThat(walletEscrowMapper.findById(escrowId).getStatus()).isEqualTo(WalletEscrowStatus.CANCELLED);
        assertThat(walletLedgerMapper.findByBusinessRef(ref)).hasSize(1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentDifferentTransitionRefsSameEscrowOnlyOneMovesFunds() throws Exception {
        long payer = uniqueOwnerUserId(PAYER_BASE);
        insertAccount(payer, 1000L, 0L, 0L);
        long escrowId = escrowCreated(payer, null, 100L); // CREATED
        String refA = "diffA:" + escrowId + ":" + System.nanoTime();
        String refB = "diffB:" + escrowId + ":" + System.nanoTime();

        AtomicInteger successes = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        runTasksConcurrently(List.of(
                () -> escrowService.lockEscrow(escrowId, refA),
                () -> escrowService.lockEscrow(escrowId, refB)), successes, errors);

        assertThat(successes.get()).as("不同 ref 撞同一 escrow 只能 1 个赢").isEqualTo(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.peek()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) errors.peek()).getErrorCode()).isEqualTo(ErrorCode.ESCROW_INVALID_STATUS);
        // 资金只动一次：held -> escrowed 一次
        assertThat(walletAccountMapper.findByOwnerUserId(payer).getEscrowedBalance()).isEqualTo(100L);
        assertThat(walletAccountMapper.findByOwnerUserId(payer).getHeldBalance()).isZero();
        int ledgers = walletLedgerMapper.findByBusinessRef(refA).size() + walletLedgerMapper.findByBusinessRef(refB).size();
        assertThat(ledgers).as("两个 ref 合计只产生一条 ledger").isEqualTo(1);
    }

    private long escrowCreated(long payer, Long payee, long amount) {
        return escrowService.createEscrow(payer, payee, amount, WalletBusinessType.BOUNTY,
                "setup:" + payer + ":" + System.nanoTime(), null).getId();
    }

    private long escrowLocked(long payer, Long payee, long amount) {
        String setupRef = "setup:" + payer + ":" + System.nanoTime();
        long id = escrowService.createEscrow(payer, payee, amount, WalletBusinessType.BOUNTY, setupRef, null).getId();
        escrowService.lockEscrow(id, setupRef + ":lock");
        return id;
    }

    private void runTasksConcurrently(List<Runnable> tasks, AtomicInteger successes,
                                      ConcurrentLinkedQueue<Throwable> errors) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                    successes.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdownNow();
    }

    /** 并发跑 {@code task} THREADS 次，全部同时起跑；事务由服务层 @Transactional(REQUIRED, READ_COMMITTED) 管理，
     *  与生产一致；任一线程抛错收集到 errors，断言为空。 */
    private void runConcurrently(Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdownNow();
        assertThat(errors).as("并发同 ref 不得有任何线程抛错（含唯一键/余额错误）").isEmpty();
    }

    private void insertAccount(long owner, long available, long held, long escrowed) {
        Instant now = Instant.now();
        walletAccountMapper.insert(WalletAccount.builder()
                .ownerUserId(owner).availableBalance(available).heldBalance(held).escrowedBalance(escrowed)
                .status(WalletAccountStatus.ACTIVE).createdAt(now).updatedAt(now).build());
    }

    /** 每次测试运行（每个 JVM）的唯一基数 + 跨测试方法累加序列，避免与容器里历史行或同运行内其他方法的主键冲突。 */
    private static final long RUN_BASE = System.currentTimeMillis();
    private static final AtomicLong ID_SEQ = new AtomicLong();

    private long uniqueOwnerUserId(long base) {
        return RUN_BASE + base + ID_SEQ.incrementAndGet();
    }

    private long uniqueLedgerId() {
        return RUN_BASE + 6_600_000_000L + ID_SEQ.incrementAndGet();
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
    @EnableTransactionManagement
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    @MapperScan(basePackageClasses = WalletAccountMapper.class)
    static class TestConfig {

        @Bean
        WalletProperties walletProperties() {
            WalletProperties properties = new WalletProperties();
            properties.setPlatformUserId(0L);
            properties.setRegistrationGrantAmount(100L);
            return properties;
        }

        @Bean
        IdService idService() {
            // 用墙钟作为运行唯一基数（每次运行都更大），跨运行不与历史 wallet_ledger 主键冲突
            long base = 5_000_000_000L + System.currentTimeMillis();
            AtomicLong counter = new AtomicLong(base);
            return namespace -> counter.getAndIncrement();
        }

        @Bean
        WalletService walletService(WalletAccountMapper accountMapper, WalletLedgerMapper ledgerMapper,
                                    IdService idService, WalletProperties properties) {
            return new WalletService(accountMapper, ledgerMapper, idService, properties);
        }

        @Bean
        WalletEscrowService walletEscrowService(WalletEscrowMapper escrowMapper, WalletService walletService,
                                                IdService idService) {
            return new WalletEscrowService(escrowMapper, walletService, idService);
        }
    }
}

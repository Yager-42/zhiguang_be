# Wallet And Escrow 实现计划

> **给 agent 工作者：** REQUIRED SUB-SKILL: 使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务逐项执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**Goal:** 构建 zhiguang 原生钱包账本与通用托管流程，并把注册赠币接入用户注册链路，同时不引入第二套商业身份。

**Architecture:** 保持在当前模块化单体内实现。复用现有 `user.id`、`IdService`、MyBatis XML mapper、`BusinessException` 与 Spring 事务。新增最小 `com.tongji.wallet` 包集，包含只追加流水、余额快照表、平台账本主体配置，以及一条从 auth 触发的注册赠币路径。

**Tech Stack:** Java 21、Spring Boot 3.2.4、MyBatis、MySQL 8、Maven、JUnit 5、Mockito、OpenSpec。

---

## 必读上下文

编辑前先读：

- `openspec/changes/add-wallet-and-escrow/proposal.md`
- `openspec/changes/add-wallet-and-escrow/design.md`
- `openspec/changes/add-wallet-and-escrow/specs/wallet-ledger/spec.md`
- `openspec/changes/add-wallet-and-escrow/specs/escrow-lifecycle/spec.md`
- `openspec/changes/add-wallet-and-escrow/tasks.md`
- `CONTEXT.md`
- `db/schema.sql`
- `src/main/java/com/tongji/auth/service/AuthService.java`
- `src/main/java/com/tongji/user/service/UserService.java`
- `src/main/java/com/tongji/user/service/impl/UserServiceImpl.java`
- `src/main/java/com/tongji/common/exception/ErrorCode.java`
- `src/main/resources/application.yml`
- `src/main/resources/mapper/UserMapper.xml`
- `src/main/resources/mapper/ReconciliationTaskMapper.xml`
- `/Volumes/lexar/revive/bytedance/backend/live-auction-domain/src/main/java/cn/revive/liveauction/domain/wallet/WalletAccount.java`
- `/Volumes/lexar/revive/bytedance/backend/live-auction-domain/src/main/java/cn/revive/liveauction/domain/wallet/WalletReservation.java`
- `/Volumes/lexar/revive/bytedance/backend/live-auction-domain/src/main/java/cn/revive/liveauction/domain/wallet/WalletLedgerEntry.java`
- `/Volumes/lexar/revive/bytedance/backend/live-auction-infrastructure/src/main/resources/schema/wallet-settlement.sql`

## Senior Review

**Altitude diagnosis:** mixed。OpenSpec 已定对领域边界，但任务列表对真正难点仍然有雾：精确文件布局、注册赠币事务边界、平台账本主体落地方式、以及 `bytedance` 钱包代码到底是移植还是重写。

### Blockers

- [B1] `AuthService.register()` 现在在一个 service 方法里完成建用户、签 JWT、存 refresh token、写审计，但没有钱包钩子，也没有一个事务边界把 `UserService.createUser()` 和赠币写入包住。证据：[AuthService.java](/Volumes/lexar/revive/zhiguang_be/src/main/java/com/tongji/auth/service/AuthService.java)、[UserServiceImpl.java](/Volumes/lexar/revive/zhiguang_be/src/main/java/com/tongji/user/service/impl/UserServiceImpl.java)。修正：引入专用注册编排服务，在一个事务里做 user row + wallet bootstrap + grant ledger row，token 签发放到事务成功之后。
- [B2] 当前仓库已经有 `IdService` 和 MyBatis XML 约定，原“adapt bytedance wallet/settlement code”任务太虚，极容易把错误的身份假设一并带进来。证据：[IdNamespace.java](/Volumes/lexar/revive/zhiguang_be/src/main/java/com/tongji/common/id/IdNamespace.java)、[UserMapper.xml](/Volumes/lexar/revive/zhiguang_be/src/main/resources/mapper/UserMapper.xml)，以及 `bytedance` wallet 使用字符串 `AccountId` 和强拍卖绑定字段。修正：围绕 `long userId` 和 `long escrowId` 重写 zhiguang 钱包模型，只借鉴余额变换逻辑。

### Major

- [M1] `db/schema.sql` 当前就是统一 schema 入口，MySQL 表直接写在这里，且已经在用少量 CHECK 约束。钱包计划应顺着这个模式走，不要现在引入 Flyway/Liquibase。证据：[schema.sql](/Volumes/lexar/revive/zhiguang_be/db/schema.sql)。修正：直接扩 `schema.sql`，新增配套 MyBatis mapper XML。
- [M2] 仓库测试风格是聚焦型 JUnit/Mockito 单测，加一个可选 MySQL 集成测试做 infra 兜底。证据：[RelationManagerImplTest.java](/Volumes/lexar/revive/zhiguang_be/src/test/java/com/tongji/relation/manager/RelationManagerImplTest.java)、[IdServiceMysqlIntegrationTest.java](/Volumes/lexar/revive/zhiguang_be/src/test/java/com/tongji/common/id/IdServiceMysqlIntegrationTest.java)。修正：钱包计划也按这个风格，大部分单测，外加一个 MySQL 集成测试；不引新测试框架。

### Minor

- [m1] `ErrorCode` 里还没有钱包相关稳定错误码。证据：[ErrorCode.java](/Volumes/lexar/revive/zhiguang_be/src/main/java/com/tongji/common/exception/ErrorCode.java)。修正：API 接入前先加钱包/托管错误码。
- [m2] `CONTEXT.md` 里托管定义还写着“作比稿补偿”，但 PRD v0.2 已删补偿池。证据：[CONTEXT.md](/Volumes/lexar/revive/zhiguang_be/CONTEXT.md:25)。修正：实现时顺手清 glossary 漂移。

### 初稿做对的点

- 钱包与托管必须先从后续 promotion / bounty 业务里拆出来。
- `user` 继续是唯一业务身份。
- 必须有平台账本主体。
- “只追加流水 + 余额快照”很适合当前代码库。

## 文件地图

- Create: `src/main/java/com/tongji/wallet/model/WalletAccount.java`
- Create: `src/main/java/com/tongji/wallet/model/WalletLedgerEntry.java`
- Create: `src/main/java/com/tongji/wallet/model/WalletEscrow.java`
- Create: `src/main/java/com/tongji/wallet/model/WalletLedgerDirection.java`
- Create: `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java`
- Create: `src/main/java/com/tongji/wallet/model/WalletAccountStatus.java`
- Create: `src/main/java/com/tongji/wallet/model/WalletEscrowStatus.java`
- Create: `src/main/java/com/tongji/wallet/model/WalletBusinessType.java`
- Create: `src/main/java/com/tongji/wallet/api/WalletController.java`
- Create: `src/main/java/com/tongji/wallet/api/dto/WalletBalanceResponse.java`
- Create: `src/main/java/com/tongji/wallet/api/dto/WalletLedgerItemResponse.java`
- Create: `src/main/java/com/tongji/wallet/api/dto/WalletLedgerResponse.java`
- Create: `src/main/java/com/tongji/wallet/api/dto/CreateEscrowRequest.java`
- Create: `src/main/java/com/tongji/wallet/api/dto/EscrowResponse.java`
- Create: `src/main/java/com/tongji/wallet/service/WalletService.java`
- Create: `src/main/java/com/tongji/wallet/service/WalletEscrowService.java`
- Create: `src/main/java/com/tongji/wallet/service/WalletRegistrationGrantService.java`
- Create: `src/main/java/com/tongji/wallet/mapper/WalletAccountMapper.java`
- Create: `src/main/java/com/tongji/wallet/mapper/WalletLedgerMapper.java`
- Create: `src/main/java/com/tongji/wallet/mapper/WalletEscrowMapper.java`
- Create: `src/main/resources/mapper/WalletAccountMapper.xml`
- Create: `src/main/resources/mapper/WalletLedgerMapper.xml`
- Create: `src/main/resources/mapper/WalletEscrowMapper.xml`
- Create: `src/test/java/com/tongji/wallet/service/WalletServiceTest.java`
- Create: `src/test/java/com/tongji/wallet/service/WalletEscrowServiceTest.java`
- Create: `src/test/java/com/tongji/wallet/service/WalletRegistrationGrantServiceTest.java`
- Create: `src/test/java/com/tongji/wallet/api/WalletControllerTest.java`
- Create: `src/test/java/com/tongji/wallet/WalletMysqlIntegrationTest.java`
- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/tongji/common/exception/ErrorCode.java`
- Modify: `src/main/java/com/tongji/auth/service/AuthService.java`
- Modify: `src/main/java/com/tongji/user/service/UserService.java`
- Modify: `src/main/java/com/tongji/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/tongji/user/mapper/UserMapper.java`
- Modify: `src/main/resources/mapper/UserMapper.xml`
- Modify: `CONTEXT.md`
- Modify after verification only: `openspec/changes/add-wallet-and-escrow/tasks.md`

## 关键决策

1. 钱包包结构保持扁平，放在 `com.tongji.wallet` 下，不拆伪 DDD 模块。  
原因：当前仓库按 feature 分包，service/mapper/model/API 分组，不是 domain/application/infrastructure 多模块形态。

2. 平台账本主体先落成保留值 `owner_user_id = 0`。  
原因：最短路径，单列即可表达主体，不用现在就引第二张主体表。未来真不够，再升 `wallet_subject` 表。  
证据：当前 repo 已在多处使用 sentinel enum / sentinel ID，且很多业务表本就没有强 FK；钱包表可以先自洽。

3. 钱包余额快照表直接保留 `available_balance`、`held_balance`、`escrowed_balance`。  
原因：spec 已明确三态；这样读余额不用每次从 ledger 重放。

4. ledger 只用一个 `business_ref` 做幂等唯一键。  
原因：最懒也够用。当前业务里已普遍用去重/幂等字符串，例如 `publish_attempt.idempotent_key`、`reconciliation_task.dedupe_scope`。

5. 注册赠币放在新编排服务里，不直接塞进 `UserServiceImpl`。  
原因：`UserServiceImpl` 应保持通用用户 CRUD 角色；注册赠币是 auth 链路特有业务，应靠独立编排服务拿事务边界。

6. 托管表这期保持通用，但 API 面先收窄。  
原因：后续 bounty 会复用通用 escrow；但当前阶段只需要 create/read/transition service 方法和内部测试，不必急着暴露全量运营写接口。

## Promoted Plan (v2)

### 目标与非目标

这期先打通一条真钱路之前的完整虚拟账务链：用户注册成功，钱包初始化，平台赠币到账，余额可查询，流水可查询，通用托管对象可创建并做状态迁移。  
这期不做充值、提现、打款、promotion 结算、bounty 业务流程。

### 合适粒度的设计

核心数据模型：

```sql
wallet_account(
  owner_user_id BIGINT UNSIGNED PRIMARY KEY,
  available_balance BIGINT NOT NULL,
  held_balance BIGINT NOT NULL,
  escrowed_balance BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL
)

wallet_ledger(
  id BIGINT UNSIGNED PRIMARY KEY,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  counterparty_user_id BIGINT UNSIGNED NULL,
  escrow_id BIGINT UNSIGNED NULL,
  business_type VARCHAR(32) NOT NULL,
  business_ref VARCHAR(128) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  reason VARCHAR(32) NOT NULL,
  amount BIGINT NOT NULL,
  available_delta BIGINT NOT NULL,
  held_delta BIGINT NOT NULL,
  escrowed_delta BIGINT NOT NULL,
  balance_available_after BIGINT NOT NULL,
  balance_held_after BIGINT NOT NULL,
  balance_escrowed_after BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_wallet_ledger_business_ref (business_ref)
)

wallet_escrow(
  id BIGINT UNSIGNED PRIMARY KEY,
  business_type VARCHAR(32) NOT NULL,
  business_ref VARCHAR(128) NOT NULL,
  payer_user_id BIGINT UNSIGNED NOT NULL,
  payee_user_id BIGINT UNSIGNED NULL,
  amount BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  expires_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_wallet_escrow_business_ref (business_ref)
)
```

服务边界：

- `WalletService`
  - `initializeIfAbsent(long ownerUserId)`
  - `grant(long ownerUserId, long amount, WalletLedgerReason reason, String businessRef)`
  - `hold(long ownerUserId, long amount, WalletLedgerReason reason, String businessRef)`
  - `releaseHold(long ownerUserId, long amount, WalletLedgerReason reason, String businessRef)`
  - `moveHoldToEscrow(long ownerUserId, long escrowId, long amount, String businessRef)`
  - `releaseEscrowToAvailable(long ownerUserId, long escrowId, long amount, WalletLedgerReason reason, String businessRef)`
  - `forfeitEscrowToPlatform(long ownerUserId, long escrowId, long amount, String businessRef)`

- `WalletEscrowService`
  - `createEscrow(...)`
  - `lockEscrow(...)`
  - `releaseEscrow(...)`
  - `refundEscrow(...)`
  - `forfeitEscrow(...)`
  - `cancelEscrow(...)`

- `WalletRegistrationGrantService`
  - `registerUserWithInitialGrant(RegisterRequest request, ClientInfo clientInfo)`
  - 包装：create user -> initialize wallet -> grant -> 返回可供 auth 继续签 token 的结果

失败规则：

- 所有钱包变更都在单个 DB 事务里写账户快照 + ledger。
- 重复 `business_ref` 返回既有有效结果，不重复扣/加钱。
- 非法状态迁移抛 `BusinessException`，使用新增钱包错误码。
- 注册赠币失败时，用户创建事务一起回滚。token 签发放在事务成功后。

### 执行顺序

### Task 1: 修 glossary 漂移并补钱包错误码

**Files:**
- Modify: `CONTEXT.md`
- Modify: `src/main/java/com/tongji/common/exception/ErrorCode.java`
- Test: none

- [ ] **Step 1: 删除 glossary 里过时的补偿池表述**

把 [CONTEXT.md](/Volumes/lexar/revive/zhiguang_be/CONTEXT.md) 中 `托管（escrow）` 词条从“释放给中标者 / 没收 / 作比稿补偿”改成符合当前 PRD 的表述：只保留 release、refund、forfeiture。

- [ ] **Step 2: 给 `ErrorCode` 增加钱包错误码**

在 `ErrorCode` 中加入：

```java
    WALLET_NOT_FOUND("WALLET_NOT_FOUND", "钱包不存在"),
    WALLET_BALANCE_NOT_ENOUGH("WALLET_BALANCE_NOT_ENOUGH", "余额不足"),
    WALLET_HELD_BALANCE_NOT_ENOUGH("WALLET_HELD_BALANCE_NOT_ENOUGH", "冻结余额不足"),
    WALLET_ESCROW_BALANCE_NOT_ENOUGH("WALLET_ESCROW_BALANCE_NOT_ENOUGH", "托管余额不足"),
    WALLET_DUPLICATE_BUSINESS_REF("WALLET_DUPLICATE_BUSINESS_REF", "账务请求重复"),
    ESCROW_NOT_FOUND("ESCROW_NOT_FOUND", "托管单不存在"),
    ESCROW_INVALID_STATUS("ESCROW_INVALID_STATUS", "托管单状态不合法");
```

- [ ] **Step 3: 跑编译检查**

Run: `mvn -q -DskipTests compile`  
Expected: compile success.

- [ ] **Step 4: Commit**

```bash
git add CONTEXT.md src/main/java/com/tongji/common/exception/ErrorCode.java
git commit -m "feat: add wallet glossary and error codes"
```

### Task 2: 加 schema 和配置

**Files:**
- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/tongji/wallet/WalletMysqlIntegrationTest.java`

- [ ] **Step 1: 先写失败的集成测试骨架**

创建 `WalletMysqlIntegrationTest.java`，参考 [IdServiceMysqlIntegrationTest.java](/Volumes/lexar/revive/zhiguang_be/src/test/java/com/tongji/common/id/IdServiceMysqlIntegrationTest.java)，加载 datasource + MyBatis，并通过 mapper 调用断言钱包表存在。

- [ ] **Step 2: 运行测试，确认先失败**

Run: `mvn -Dtest=WalletMysqlIntegrationTest test`  
Expected: fail，因为 wallet mappers / tables 还不存在。

- [ ] **Step 3: 在 `application.yml` 里增加钱包配置**

加入：

```yaml
wallet:
  platform-user-id: ${WALLET_PLATFORM_USER_ID:0}
  registration-grant-amount: ${WALLET_REGISTRATION_GRANT_AMOUNT:100}
```

- [ ] **Step 4: 扩展 `db/schema.sql`**

加入：

```sql
CREATE TABLE IF NOT EXISTS wallet_account (
    owner_user_id BIGINT UNSIGNED NOT NULL,
    available_balance BIGINT NOT NULL DEFAULT 0,
    held_balance BIGINT NOT NULL DEFAULT 0,
    escrowed_balance BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_user_id),
    CHECK (available_balance >= 0),
    CHECK (held_balance >= 0),
    CHECK (escrowed_balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wallet_ledger (
    id BIGINT UNSIGNED NOT NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    counterparty_user_id BIGINT NULL,
    escrow_id BIGINT UNSIGNED NULL,
    business_type VARCHAR(32) NOT NULL,
    business_ref VARCHAR(128) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    available_delta BIGINT NOT NULL,
    held_delta BIGINT NOT NULL,
    escrowed_delta BIGINT NOT NULL,
    balance_available_after BIGINT NOT NULL,
    balance_held_after BIGINT NOT NULL,
    balance_escrowed_after BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_ledger_business_ref (business_ref),
    KEY idx_wallet_ledger_owner_created (owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wallet_escrow (
    id BIGINT UNSIGNED NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_ref VARCHAR(128) NOT NULL,
    payer_user_id BIGINT UNSIGNED NOT NULL,
    payee_user_id BIGINT UNSIGNED NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_escrow_business_ref (business_ref),
    KEY idx_wallet_escrow_payer_status (payer_user_id, status),
    KEY idx_wallet_escrow_payee_status (payee_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 5: 再跑一次集成测试**

Run: `mvn -Dtest=WalletMysqlIntegrationTest test`  
Expected: 仍然 fail，但失败原因应变成缺 Java mapper / model 层，而不是缺 schema。

- [ ] **Step 6: Commit**

```bash
git add db/schema.sql src/main/resources/application.yml src/test/java/com/tongji/wallet/WalletMysqlIntegrationTest.java
git commit -m "feat: add wallet schema and config"
```

### Task 3: 实现 wallet model 和 mapper 层

**Files:**
- Create: `src/main/java/com/tongji/wallet/model/*.java`
- Create: `src/main/java/com/tongji/wallet/mapper/*.java`
- Create: `src/main/resources/mapper/WalletAccountMapper.xml`
- Create: `src/main/resources/mapper/WalletLedgerMapper.xml`
- Create: `src/main/resources/mapper/WalletEscrowMapper.xml`
- Test: `src/test/java/com/tongji/wallet/WalletMysqlIntegrationTest.java`

- [ ] **Step 1: 先把 mapper 驱动的集成断言写失败**

在 `WalletMysqlIntegrationTest` 里加入 account、ledger、escrow 最小 insert/select 断言。

- [ ] **Step 2: 运行测试，确认先失败**

Run: `mvn -Dtest=WalletMysqlIntegrationTest test`  
Expected: fail，因为 mapper / model 类还不存在。

- [ ] **Step 3: 创建枚举和 model**

创建：

```java
public enum WalletAccountStatus { ACTIVE, DISABLED }
public enum WalletEscrowStatus { CREATED, LOCKED, RELEASED, REFUNDED, FORFEITED, CANCELLED }
public enum WalletLedgerDirection { CREDIT, DEBIT }
public enum WalletLedgerReason {
    REGISTRATION_GRANT,
    PLATFORM_SUBSIDY,
    HOLD_RESERVE,
    HOLD_RELEASE,
    HOLD_TO_ESCROW,
    ESCROW_RELEASE,
    ESCROW_REFUND,
    ESCROW_FORFEIT
}
public enum WalletBusinessType { REGISTRATION, PROMOTION, BOUNTY, CONSULTATION, SYSTEM }
```

model 风格跟 [User.java](/Volumes/lexar/revive/zhiguang_be/src/main/java/com/tongji/user/domain/User.java) 一致：`@Data @Builder @NoArgsConstructor @AllArgsConstructor`。

- [ ] **Step 4: 创建 mapper interface**

最小方法集：

```java
@Mapper
public interface WalletAccountMapper {
    WalletAccount findByOwnerUserId(@Param("ownerUserId") long ownerUserId);
    int insert(WalletAccount account);
    int updateBalancesAndStatus(WalletAccount account);
}

@Mapper
public interface WalletLedgerMapper {
    int insert(WalletLedgerEntry entry);
    WalletLedgerEntry findByBusinessRef(@Param("businessRef") String businessRef);
    List<WalletLedgerEntry> listByOwnerUserId(@Param("ownerUserId") long ownerUserId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);
}

@Mapper
public interface WalletEscrowMapper {
    int insert(WalletEscrow escrow);
    WalletEscrow findById(@Param("id") long id);
    WalletEscrow findByBusinessRef(@Param("businessRef") String businessRef);
    int updateStatus(WalletEscrow escrow);
}
```

- [ ] **Step 5: 创建 XML mapper**

风格对齐 [UserMapper.xml](/Volumes/lexar/revive/zhiguang_be/src/main/resources/mapper/UserMapper.xml) 和 [ReconciliationTaskMapper.xml](/Volumes/lexar/revive/zhiguang_be/src/main/resources/mapper/ReconciliationTaskMapper.xml)。使用显式 `resultMap`，不要注解 SQL。

- [ ] **Step 6: 跑集成测试，确认通过**

Run: `mvn -Dtest=WalletMysqlIntegrationTest test`  
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/wallet src/main/resources/mapper/Wallet*.xml src/test/java/com/tongji/wallet/WalletMysqlIntegrationTest.java
git commit -m "feat: add wallet persistence layer"
```

### Task 4: 实现 wallet balance service

**Files:**
- Create: `src/main/java/com/tongji/wallet/service/WalletService.java`
- Create: `src/test/java/com/tongji/wallet/service/WalletServiceTest.java`

- [ ] **Step 1: 先写失败的单元测试**

覆盖这些用例：
- initialize missing wallet
- duplicate `businessRef` grant returns existing effect
- hold moves available -> held
- release moves held -> available
- move hold to escrow moves held -> escrowed
- release escrow to available moves escrowed -> available
- insufficient available throws `WALLET_BALANCE_NOT_ENOUGH`

Mockito 风格跟 [RelationManagerImplTest.java](/Volumes/lexar/revive/zhiguang_be/src/test/java/com/tongji/relation/manager/RelationManagerImplTest.java) 一致。

- [ ] **Step 2: 跑单测，确认先失败**

Run: `mvn -Dtest=WalletServiceTest test`  
Expected: fail，因为 service 尚未实现。

- [ ] **Step 3: 实现最小 service**

规则：
- `IdService.nextId(IdNamespace.ADMIN_OPERATION)` 先拿来做 ledger ID，直到有专用 wallet namespace
- `initializeIfAbsent()` 缺失时插入 zero-balance account
- 每个 mutating method 先按 `businessRef` 查既有 ledger
- 重复 `businessRef` 返回已有结果
- 每次变更都在同一事务里更新账户快照并插入一条 ledger

- [ ] **Step 4: 跑单测，确认通过**

Run: `mvn -Dtest=WalletServiceTest test`  
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/wallet/service/WalletService.java src/test/java/com/tongji/wallet/service/WalletServiceTest.java
git commit -m "feat: add wallet balance service"
```

### Task 5: 实现通用 escrow service

**Files:**
- Create: `src/main/java/com/tongji/wallet/service/WalletEscrowService.java`
- Create: `src/test/java/com/tongji/wallet/service/WalletEscrowServiceTest.java`

- [ ] **Step 1: 先写失败的 escrow 生命周期测试**

覆盖：
- create escrow from available balance
- lock created escrow
- release locked escrow to payee available balance
- refund created or locked escrow to payer available balance
- forfeit locked escrow to platform ledger subject
- invalid transition throws `ESCROW_INVALID_STATUS`
- duplicate `businessRef` create returns existing escrow

- [ ] **Step 2: 跑单测，确认先失败**

Run: `mvn -Dtest=WalletEscrowServiceTest test`  
Expected: fail，因为 service 尚未实现。

- [ ] **Step 3: 实现最小 escrow service**

实现约束：
- create escrow = 调 `WalletService.hold(...)`，然后创建 `CREATED` escrow row
- lock escrow = 只改 row state
- release / refund / forfeit = 做状态守卫 + 钱包转账 + row 终态
- 先用 `IdService.nextId(IdNamespace.ADMIN_OPERATION)` 生成 escrow ID
- `ponytail:` 不上状态机框架；`switch` enum 足够

- [ ] **Step 4: 跑单测，确认通过**

Run: `mvn -Dtest=WalletEscrowServiceTest test`  
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/wallet/service/WalletEscrowService.java src/test/java/com/tongji/wallet/service/WalletEscrowServiceTest.java
git commit -m "feat: add generic escrow service"
```

### Task 6: 接入注册赠币

**Files:**
- Create: `src/main/java/com/tongji/wallet/service/WalletRegistrationGrantService.java`
- Modify: `src/main/java/com/tongji/auth/service/AuthService.java`
- Modify: `src/main/java/com/tongji/user/service/UserService.java`
- Modify: `src/main/java/com/tongji/user/service/impl/UserServiceImpl.java`
- Create: `src/test/java/com/tongji/wallet/service/WalletRegistrationGrantServiceTest.java`

- [ ] **Step 1: 先写失败的注册赠币单测**

测试用例：
- successful registration creates user, initializes wallet, writes registration grant ledger
- duplicate identifier still fails before wallet grant
- grant failure aborts user creation path

- [ ] **Step 2: 跑测试，确认先失败**

Run: `mvn -Dtest=WalletRegistrationGrantServiceTest test`  
Expected: fail，因为 orchestration service 不存在。

- [ ] **Step 3: 只在必要时补 user-create 边界**

如有需要，调整 `UserService` / `UserServiceImpl` 暴露清晰的 `createUser(User user)` 边界；不要把钱包逻辑塞进 user service。

- [ ] **Step 4: 实现 `WalletRegistrationGrantService`**

形状：

```java
@Service
@RequiredArgsConstructor
public class WalletRegistrationGrantService {
    private final UserService userService;
    private final WalletService walletService;

    @Transactional
    public User createUserAndGrant(User user, long grantAmount) {
        User created = userService.createUser(user);
        walletService.initializeIfAbsent(created.getId());
        walletService.grant(
            created.getId(),
            grantAmount,
            WalletLedgerReason.REGISTRATION_GRANT,
            "registration-grant:user:" + created.getId()
        );
        return created;
    }
}
```

- [ ] **Step 5: 修改 `AuthService.register()` 调编排服务**

把直接的 `userService.createUser(user);` 替换成 `walletRegistrationGrantService.createUserAndGrant(user, config-backed grant amount);`

token issue 和 refresh-token store 继续放在成功创建/赠币之后。

- [ ] **Step 6: 跑单测，确认通过**

Run: `mvn -Dtest=WalletRegistrationGrantServiceTest test`  
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/auth/service/AuthService.java src/main/java/com/tongji/wallet/service/WalletRegistrationGrantService.java src/test/java/com/tongji/wallet/service/WalletRegistrationGrantServiceTest.java
git commit -m "feat: grant wallet balance on registration"
```

### Task 7: 暴露钱包读 API

**Files:**
- Create: `src/main/java/com/tongji/wallet/api/WalletController.java`
- Create: `src/main/java/com/tongji/wallet/api/dto/WalletBalanceResponse.java`
- Create: `src/main/java/com/tongji/wallet/api/dto/WalletLedgerItemResponse.java`
- Create: `src/main/java/com/tongji/wallet/api/dto/WalletLedgerResponse.java`
- Create: `src/test/java/com/tongji/wallet/api/WalletControllerTest.java`

- [ ] **Step 1: 先写失败的 controller test**

接口：
- `GET /api/v1/wallet/me`
- `GET /api/v1/wallet/me/ledger?page=1&pageSize=20`

使用当前安全测试常见的 mocked authenticated principal 模式。

- [ ] **Step 2: 跑测试，确认先失败**

Run: `mvn -Dtest=WalletControllerTest test`  
Expected: fail，因为 controller 不存在。

- [ ] **Step 3: 实现 controller 和 DTO**

只做 read-only API。不要加 recharge / withdraw endpoint。

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -Dtest=WalletControllerTest test`  
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/wallet/api src/test/java/com/tongji/wallet/api/WalletControllerTest.java
git commit -m "feat: expose wallet query api"
```

### Task 8: 全量验证并关闭 OpenSpec task

**Files:**
- Modify after pass: `openspec/changes/add-wallet-and-escrow/tasks.md`

- [ ] **Step 1: 跑聚焦钱包测试集**

Run:

```bash
mvn -Dtest=WalletServiceTest,WalletEscrowServiceTest,WalletRegistrationGrantServiceTest,WalletControllerTest,WalletMysqlIntegrationTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: 跑更宽一点的回归切片**

Run:

```bash
mvn -Dtest=JwtServiceTest,RelationManagerImplTest,ReconciliationServiceTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: 跑应用编译检查**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: compile success.

- [ ] **Step 4: 勾掉 OpenSpec 已完成任务**

更新 [openspec/changes/add-wallet-and-escrow/tasks.md](/Volumes/lexar/revive/zhiguang_be/openspec/changes/add-wallet-and-escrow/tasks.md) 中真正已完成的 checkbox。

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/add-wallet-and-escrow/tasks.md
git commit -m "docs: close wallet and escrow tasks"
```

## 风险与回滚

- 最难回退选择：`platform-user-id = 0` 哨兵值。回滚路径：后续引入 `wallet_subject` 表并迁移 `owner_user_id/counterparty_user_id`。
- 最大运行时风险：注册链路现在依赖钱包写入。回滚路径：把注册赠币金额 feature-flag 到 `0`，保留钱包表但停掉 grant write。
- 数据风险：重试导致重复 ledger。缓解：唯一 `business_ref`；回滚靠 replay-safe ledger read 和补偿分录，不改历史行。

## 需要你拍板的问题

- `registration-grant-amount` 默认值是否就用 `100`
- `platform-user-id = 0` 是否接受为第一阶段方案，还是 day 1 就上 `wallet_subject` 表

## Delta summary

- 把含糊的 “adapt bytedance wallet” 改成了可执行的 zhiguang 文件地图和本地模型。
- 把注册赠币放进专用编排服务，token 签发放到事务之后。
- 选了最懒可行数据模型：3 张表、1 个幂等键、暂不上主体表。
- 把公开 API 收窄到只读钱包查询；escrow 暂时主要内部用。

# Wallet And Escrow 实现计划

> **给 agent 工作者：** REQUIRED SUB-SKILL: 使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务逐项执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**Goal:** 构建 zhiguang 原生钱包账本与通用托管流程，并把注册赠币接入用户注册链路，同时不引入第二套商业身份。

**Architecture:** 保持在当前模块化单体内实现。复用现有 `user.id`、`IdService`、MyBatis XML mapper、`BusinessException` 与 Spring 事务。新增最小 `com.tongji.wallet` 包集，包含只追加流水、余额快照表、`platform-user-id=0` 账务 counterparty sentinel，以及一条从 auth 触发的注册赠币路径。本期只追加账务事实源；严格复式记账、平台余额对账和补洞归后续 `add-data-reconciliation`。

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
- [B3] 钱包余额快照双写若按“读余额 -> 算新绝对值 -> update”实现，会在并发 grant/hold/refund 下丢账。修正：每个 mutating wallet operation 必须在同一事务内锁住 `wallet_account` 行（`SELECT ... FOR UPDATE`）或使用等价原子条件更新；余额更新必须用 delta，不许用陈旧读值覆盖绝对余额。
- [B4] 托管资金语义必须跟 spec 三态一致：`createEscrow` 只 `hold + CREATED`，`lockEscrow` 才 `moveHoldToEscrow + LOCKED`；`release` 是 payer escrowed -> payee available 直接转移，不得伪装成平台罚没再平台赠款。

### Major

- [M1] `db/schema.sql` 当前就是统一 schema 入口，MySQL 表直接写在这里，且已经在用少量 CHECK 约束。钱包计划应顺着这个模式走，不要现在引入 Flyway/Liquibase。证据：[schema.sql](/Volumes/lexar/revive/zhiguang_be/db/schema.sql)。修正：直接扩 `schema.sql`，新增配套 MyBatis mapper XML。
- [M2] 仓库测试风格是聚焦型 JUnit/Mockito 单测，加一个可选 MySQL 集成测试做 infra 兜底。证据：[RelationManagerImplTest.java](/Volumes/lexar/revive/zhiguang_be/src/test/java/com/tongji/relation/manager/RelationManagerImplTest.java)、[IdServiceMysqlIntegrationTest.java](/Volumes/lexar/revive/zhiguang_be/src/test/java/com/tongji/common/id/IdServiceMysqlIntegrationTest.java)。修正：钱包计划也按这个风格，大部分单测，外加一个 MySQL 集成测试；不引新测试框架。

### Minor

- [m1] `ErrorCode` 里还没有钱包相关稳定错误码。证据：[ErrorCode.java](/Volumes/lexar/revive/zhiguang_be/src/main/java/com/tongji/common/exception/ErrorCode.java)。修正：API 接入前先加钱包/托管错误码。
- [m2] glossary 已删除补偿池、咨询预约和套餐漂移表述；后续实现继续按 `CONTEXT.md` 当前词表命名。

### 初稿做对的点

- 钱包与托管必须先从后续 promotion / bounty 业务里拆出来。
- `user` 继续是唯一业务身份。
- 必须有平台账本主体。
- “只追加流水 + 余额快照”很适合当前代码库。

## 文件地图

当前仓库已有一版 wallet 实现，但审查发现它和 spec/本计划不一致。下面文件若已存在，执行者必须按本计划改造；不得因为 OpenSpec tasks 曾被勾选就跳过。

- Modify/Create: `src/main/java/com/tongji/wallet/model/WalletAccount.java`
- Modify/Create: `src/main/java/com/tongji/wallet/model/WalletLedgerEntry.java`
- Modify/Create: `src/main/java/com/tongji/wallet/model/WalletEscrow.java`
- Modify/Create: `src/main/java/com/tongji/wallet/model/WalletLedgerDirection.java`
- Modify/Create: `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java`
- Modify/Create: `src/main/java/com/tongji/wallet/model/WalletAccountStatus.java`
- Modify/Create: `src/main/java/com/tongji/wallet/model/WalletEscrowStatus.java`
- Modify/Create: `src/main/java/com/tongji/wallet/model/WalletBusinessType.java`
- Modify/Create: `src/main/java/com/tongji/wallet/api/WalletController.java`
- Modify/Create: `src/main/java/com/tongji/wallet/api/dto/WalletBalanceResponse.java`
- Modify/Create: `src/main/java/com/tongji/wallet/api/dto/WalletLedgerItemResponse.java`
- Modify/Create: `src/main/java/com/tongji/wallet/api/dto/WalletLedgerResponse.java`
- Modify/Create: `src/main/java/com/tongji/wallet/service/WalletService.java`
- Modify/Create: `src/main/java/com/tongji/wallet/service/WalletEscrowService.java`
- Modify/Create: `src/main/java/com/tongji/wallet/service/WalletRegistrationGrantService.java`
- Modify/Create: `src/main/java/com/tongji/wallet/mapper/WalletAccountMapper.java`
- Modify/Create: `src/main/java/com/tongji/wallet/mapper/WalletLedgerMapper.java`
- Modify/Create: `src/main/java/com/tongji/wallet/mapper/WalletEscrowMapper.java`
- Modify/Create: `src/main/resources/mapper/WalletAccountMapper.xml`
- Modify/Create: `src/main/resources/mapper/WalletLedgerMapper.xml`
- Modify/Create: `src/main/resources/mapper/WalletEscrowMapper.xml`
- Modify/Create: `src/test/java/com/tongji/wallet/service/WalletServiceTest.java`
- Modify/Create: `src/test/java/com/tongji/wallet/service/WalletEscrowServiceTest.java`
- Modify/Create: `src/test/java/com/tongji/wallet/service/WalletRegistrationGrantServiceTest.java`
- Modify/Create: `src/test/java/com/tongji/wallet/api/WalletControllerTest.java`
- Modify/Create: `src/test/java/com/tongji/wallet/WalletMysqlIntegrationTest.java`
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

2. 平台账本主体先落成保留值 `platform-user-id = 0`。
原因：`0` 只是账务 `counterparty_user_id` sentinel，不是普通 `user`，不创建用户行，不进入内容社区身份模型，也不要求这期做第二套主体表。未来若要严格复式和平台余额对账，再由 `add-data-reconciliation` 引入 `wallet_subject` / reconciliation 能力。

3. 钱包余额快照表直接保留 `available_balance`、`held_balance`、`escrowed_balance`。  
原因：spec 已明确三态；这样读余额不用每次从 ledger 重放。

4. ledger `business_ref` 是钱包操作幂等键，但不是“见到重复就静默返回”。
规则：ledger 唯一约束用 `(owner_user_id, business_ref)`，允许 direct transfer 的 payer/payee 两条 ledger 共用同一个 transition ref；服务层查同 `business_ref` 的既有 ledger 组，只有 `owner_user_id`、`amount`、`reason`、`business_type`、`escrow_id`、`available_delta`、`held_delta`、`escrowed_delta` 全部符合本次预期时返回既有结果，任一参数不同必须 reject。

5. 注册赠币放在新编排服务里，不直接塞进 `UserServiceImpl`。  
原因：`UserServiceImpl` 应保持通用用户 CRUD 角色；注册赠币是 auth 链路特有业务，应靠独立编排服务拿事务边界。

6. 托管表这期保持通用，但 API 面先收窄。  
原因：后续 bounty 会复用通用 escrow；但当前阶段只需要 create/read/transition service 方法和内部测试，不必急着暴露全量运营写接口。

7. 所有钱包变更必须在事务内锁账户行或用原子条件更新。
原因：余额快照是可变视图，不能用读后绝对值覆盖。推荐 `findByOwnerUserIdForUpdate()` + delta update；若不用行锁，则 SQL 必须是 `available_balance = available_balance + #{delta}` 且带余额非负条件。

8. 托管状态迁移必须显式带 `transitionBusinessRef`。
规则：`lock`、`release`、`refund`、`forfeit`、`cancel` 都要有独立 transition ref。重复同 ref 返回既有迁移结果；不同 ref 撞到已迁移/终态必须 reject。不要只靠 `wallet_escrow` 上一个 `last_transition_business_ref` 字段表达历史重试；用 transition ledger（本期可复用 wallet ledger 的 `business_ref + escrow_id + reason`）作为幂等事实源。

9. `expires_at` 不是 timeout 方案本身。
规则：本期不要求 scheduler，但必须提供业务可调用 resolver/hook，例如 `resolveExpiredEscrow(escrowId, resolution, transitionBusinessRef, now)`，并测试过期后 auto-release / auto-refund 这类允许终态。

10. 本 change 不做咨询、专家预约或任何预约业务流程。
规则：`WalletBusinessType` 示例只保留 `REGISTRATION`、`PROMOTION`、`BOUNTY`、`SYSTEM`，不得加入预约相关枚举。

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
  amount BIGINT NOT NULL CHECK (amount > 0),
  available_delta BIGINT NOT NULL,
  held_delta BIGINT NOT NULL,
  escrowed_delta BIGINT NOT NULL,
  balance_available_after BIGINT NOT NULL,
  balance_held_after BIGINT NOT NULL,
  balance_escrowed_after BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_wallet_ledger_owner_business_ref (owner_user_id, business_ref)
)

wallet_escrow(
  id BIGINT UNSIGNED PRIMARY KEY,
  business_type VARCHAR(32) NOT NULL,
  business_ref VARCHAR(128) NOT NULL,
  payer_user_id BIGINT UNSIGNED NOT NULL,
  payee_user_id BIGINT UNSIGNED NULL,
  amount BIGINT NOT NULL CHECK (amount > 0),
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
  - `grant(long ownerUserId, long amount, WalletLedgerReason reason, WalletBusinessType businessType, String businessRef)`
  - `hold(long ownerUserId, long amount, WalletLedgerReason reason, WalletBusinessType businessType, String businessRef)`
  - `releaseHold(long ownerUserId, long amount, WalletLedgerReason reason, WalletBusinessType businessType, String businessRef)`
  - `moveHoldToEscrow(long ownerUserId, long escrowId, long amount, WalletBusinessType businessType, String businessRef)`
  - `releaseEscrowToPayee(long payerUserId, long payeeUserId, long escrowId, long amount, WalletBusinessType businessType, String businessRef)`
  - `releaseEscrowToAvailable(long ownerUserId, long escrowId, long amount, WalletLedgerReason reason, WalletBusinessType businessType, String businessRef)`
  - `forfeitEscrowToPlatform(long ownerUserId, long escrowId, long amount, WalletBusinessType businessType, String businessRef)`

- `WalletEscrowService`
  - `createEscrow(..., String businessRef)` -> `hold + CREATED row`
  - `lockEscrow(long escrowId, String transitionBusinessRef)` -> `moveHoldToEscrow + LOCKED`
  - `releaseEscrow(long escrowId, String transitionBusinessRef)` -> direct transfer escrow -> payee
  - `refundEscrow(long escrowId, String transitionBusinessRef)`
  - `forfeitEscrow(long escrowId, String transitionBusinessRef)`
  - `cancelEscrow(long escrowId, String transitionBusinessRef)`
  - `resolveExpiredEscrow(long escrowId, EscrowTimeoutResolution resolution, String transitionBusinessRef, Instant now)`

- `WalletRegistrationGrantService`
  - `createUserAndGrant(User user, long grantAmount)`
  - 包装：create user -> initialize wallet -> optional grant -> 返回可供 auth 继续签 token 的 created user；不接收 auth DTO / client info

失败规则：

- 所有钱包变更都在单个 DB 事务里写账户快照 + ledger，并锁定被修改的 `wallet_account` 行（`SELECT ... FOR UPDATE` 或等价原子条件更新）。
- 禁止“读余额 -> 计算绝对新余额 -> update 绝对值”覆盖；必须用锁后 delta 或 SQL 原子 delta 更新。
- 重复 `business_ref` 只有 owner/amount/reason/businessType/escrowId/deltas 组成的 ledger 组完全一致时返回既有有效结果；同 ref 不同参数必须 reject。
- `amount <= 0` 在 schema、service、test 三层都必须 reject。
- 非法状态迁移抛 `BusinessException`，使用新增钱包错误码。
- 托管迁移幂等键使用 `transitionBusinessRef`；同 ref 同参数可重试，不同 ref 撞终态必须 reject。
- 托管迁移幂等事实源用对应 wallet ledger，不在 `wallet_escrow` 上只存最后一个 transition ref；否则旧 transition 无法重试判等。
- 注册赠币失败时，用户创建事务一起回滚。token 签发放在事务成功后。

### 执行顺序

### Task 1: 修 glossary 漂移并补钱包错误码

**Files:**
- Modify: `CONTEXT.md`
- Modify: `src/main/java/com/tongji/common/exception/ErrorCode.java`
- Test: none

- [ ] **Step 1: 删除 glossary 里过时的补偿池 / 咨询预约表述**

把 [CONTEXT.md](/Volumes/lexar/revive/zhiguang_be/CONTEXT.md) 中 `托管（escrow）` 词条从“释放给中标者 / 没收 / 作比稿补偿”改成符合当前 PRD 的表述：保留 release、refund、forfeiture、cancel。
同时删掉商业化 glossary 中专家预约 / 咨询作为当前 change 场景的表述；当前四个 changes 不包含专家预约。

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
Expected: fail 或暴露 schema drift，因为当前 schema 尚未满足 `amount > 0`、`owner_user_id + business_ref`、钱包/托管幂等约束。

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
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_ledger_owner_business_ref (owner_user_id, business_ref),
    KEY idx_wallet_ledger_business_ref (business_ref),
    KEY idx_wallet_ledger_owner_created (owner_user_id, created_at),
    CHECK (amount > 0)
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
    KEY idx_wallet_escrow_payee_status (payee_user_id, status),
    CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Schema 注意：
- `platform-user-id=0` 只作为 `counterparty_user_id` sentinel；不要插入 `user.id=0`。
- 本期 ledger 是事实源，不声称已可完整复式平账；平台余额对账归后续 `add-data-reconciliation`。

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
- Modify/Create: `src/main/java/com/tongji/wallet/model/*.java`
- Modify/Create: `src/main/java/com/tongji/wallet/mapper/*.java`
- Modify/Create: `src/main/resources/mapper/WalletAccountMapper.xml`
- Modify/Create: `src/main/resources/mapper/WalletLedgerMapper.xml`
- Modify/Create: `src/main/resources/mapper/WalletEscrowMapper.xml`
- Test: `src/test/java/com/tongji/wallet/WalletMysqlIntegrationTest.java`

- [ ] **Step 1: 先把 mapper 驱动的集成断言写失败**

在 `WalletMysqlIntegrationTest` 里加入 account、ledger、escrow 最小 insert/select 断言。

- [ ] **Step 2: 运行测试，确认先失败**

Run: `mvn -Dtest=WalletMysqlIntegrationTest test`  
Expected: fail 或暴露 mapper drift，因为当前 mapper 尚未满足锁行、delta update、幂等组查询与条件状态迁移要求。

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
public enum WalletBusinessType { REGISTRATION, PROMOTION, BOUNTY, SYSTEM }
```

model 风格跟 [User.java](/Volumes/lexar/revive/zhiguang_be/src/main/java/com/tongji/user/domain/User.java) 一致：`@Data @Builder @NoArgsConstructor @AllArgsConstructor`。

- [ ] **Step 4: 创建 mapper interface**

最小方法集：

```java
@Mapper
public interface WalletAccountMapper {
    WalletAccount findByOwnerUserId(@Param("ownerUserId") long ownerUserId);
    WalletAccount findByOwnerUserIdForUpdate(@Param("ownerUserId") long ownerUserId);
    int insert(WalletAccount account);
    int applyBalanceDeltas(@Param("ownerUserId") long ownerUserId,
                           @Param("availableDelta") long availableDelta,
                           @Param("heldDelta") long heldDelta,
                           @Param("escrowedDelta") long escrowedDelta);
}

@Mapper
public interface WalletLedgerMapper {
    int insert(WalletLedgerEntry entry);
    List<WalletLedgerEntry> findByBusinessRef(@Param("businessRef") String businessRef);
    WalletLedgerEntry findByOwnerUserIdAndBusinessRef(@Param("ownerUserId") long ownerUserId,
                                                      @Param("businessRef") String businessRef);
    List<WalletLedgerEntry> listByOwnerUserId(@Param("ownerUserId") long ownerUserId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);
}

@Mapper
public interface WalletEscrowMapper {
    int insert(WalletEscrow escrow);
    WalletEscrow findById(@Param("id") long id);
    WalletEscrow findByIdForUpdate(@Param("id") long id);
    WalletEscrow findByBusinessRef(@Param("businessRef") String businessRef);
    int transitionStatus(@Param("id") long id,
                         @Param("fromStatus") WalletEscrowStatus fromStatus,
                         @Param("toStatus") WalletEscrowStatus toStatus);
}
```

- [ ] **Step 5: 创建 XML mapper**

风格对齐 [UserMapper.xml](/Volumes/lexar/revive/zhiguang_be/src/main/resources/mapper/UserMapper.xml) 和 [ReconciliationTaskMapper.xml](/Volumes/lexar/revive/zhiguang_be/src/main/resources/mapper/ReconciliationTaskMapper.xml)。使用显式 `resultMap`，不要注解 SQL。

`WalletAccountMapper.xml` 必须包含 `SELECT ... FOR UPDATE` 查询：

```sql
SELECT ... FROM wallet_account WHERE owner_user_id = #{ownerUserId} FOR UPDATE
```

`applyBalanceDeltas` 必须用 delta 表达式更新余额，并带非负保护；不要传入业务层算好的绝对余额覆盖 DB 当前值。

`WalletEscrowMapper.xml` 必须提供 `findByIdForUpdate` 或等价条件迁移保护；`transitionBusinessRef` 不写入 `wallet_escrow`，只作为 wallet ledger 的 `business_ref` 幂等键。

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
- Modify/Create: `src/main/java/com/tongji/wallet/service/WalletService.java`
- Modify/Create: `src/test/java/com/tongji/wallet/service/WalletServiceTest.java`

- [ ] **Step 1: 先写失败的单元测试**

覆盖这些用例：
- initialize missing wallet
- duplicate `businessRef` grant returns existing effect
- same `businessRef` with different owner/amount/reason/businessType/escrowId/deltas rejects
- hold moves available -> held
- release moves held -> available
- move hold to escrow moves held -> escrowed
- release escrow to available moves escrowed -> available
- direct release escrow to payee writes payer/payee `ESCROW_RELEASE` ledger entries with cross counterparty
- insufficient available throws `WALLET_BALANCE_NOT_ENOUGH`
- `amount <= 0` rejects before ledger insert
- concurrent mutating operations lock account row or use atomic conditional update; no lost update from stale absolute balance

Mockito 风格跟 [RelationManagerImplTest.java](/Volumes/lexar/revive/zhiguang_be/src/test/java/com/tongji/relation/manager/RelationManagerImplTest.java) 一致。

- [ ] **Step 2: 跑单测，确认先失败**

Run: `mvn -Dtest=WalletServiceTest test`  
Expected: fail，因为当前 service 尚未满足并发锁、幂等参数校验、direct transfer 和金额校验要求。

- [ ] **Step 3: 实现最小 service**

规则：
- `IdService.nextId(IdNamespace.ADMIN_OPERATION)` 先拿来做 ledger ID，直到有专用 wallet namespace
- `initializeIfAbsent()` 缺失时插入 zero-balance account
- 每个 mutating method 先校验 `amount > 0`
- 每个 mutating method 先按 `businessRef` 查既有 ledger 组；若存在，必须比对本次预期 ledger 组的 `owner_user_id`、`amount`、`reason`、`business_type`、`escrow_id`、三个 delta，完全一致才返回既有结果，任一不同抛 `WALLET_DUPLICATE_BUSINESS_REF`
- 每次变更都在同一事务里：查幂等 ledger -> 锁 `wallet_account` 行（`findByOwnerUserIdForUpdate`）或执行原子条件 delta update -> 插入 ledger
- 禁止用读到的旧余额生成绝对新余额后更新；余额快照只能由锁内当前值 + delta，或 SQL `balance = balance + delta` 产生
- `grant` 和 `PLATFORM_SUBSIDY` 的 `counterparty_user_id` 使用 `platform-user-id=0` sentinel
- `releaseEscrowToPayee` 必须是 direct transfer：同一个 `businessRef` 下写 payer/payee 两条 ledger；payer ledger `ESCROW_RELEASE` debit `escrowed_delta=-amount`、counterparty=payee；payee ledger `ESCROW_RELEASE` credit `available_delta=+amount`、counterparty=payer；不得 `forfeitEscrowToPlatform + grant(payee)`
- `forfeitEscrowToPlatform` 只用于罚没到平台 sentinel，不可复用为 release 给 payee

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
- Modify/Create: `src/main/java/com/tongji/wallet/service/WalletEscrowService.java`
- Modify/Create: `src/test/java/com/tongji/wallet/service/WalletEscrowServiceTest.java`

- [ ] **Step 1: 先写失败的 escrow 生命周期测试**

覆盖：
- create escrow = payer available -> held, then `CREATED` escrow row
- lock created escrow = payer held -> escrowed, then `LOCKED`
- release locked escrow = direct payer escrowed -> payee available; payer/payee ledger reason 都是 `ESCROW_RELEASE`，counterparty 互指
- refund `CREATED` escrow = `releaseHold` to payer available
- refund `LOCKED` escrow = `releaseEscrowToAvailable` to payer available
- cancel `CREATED` escrow = `releaseHold` to payer available
- forfeit locked escrow to platform ledger subject
- invalid transition throws `ESCROW_INVALID_STATUS`
- duplicate `businessRef` create returns existing escrow
- lock/release/refund/forfeit/cancel duplicate same `transitionBusinessRef` returns existing transition outcome
- different `transitionBusinessRef` hitting terminal state rejects
- concurrent transition attempts cannot double-move funds; escrow row must be locked or conditionally transitioned before/with wallet movement
- timeout resolver can resolve expired escrow through configured release/refund path and records ledger
- `amount <= 0` create rejects

- [ ] **Step 2: 跑单测，确认先失败**

Run: `mvn -Dtest=WalletEscrowServiceTest test`  
Expected: fail，因为当前 service 尚未满足 create/lock 资金落点、direct release、transitionBusinessRef、timeout resolver 和并发迁移要求。

- [ ] **Step 3: 实现最小 escrow service**

实现约束：
- create escrow = 调 `WalletService.hold(...)`，然后创建 `CREATED` escrow row；不写 escrowed balance
- lock escrow = 状态守卫 `CREATED`，调 `WalletService.moveHoldToEscrow(...)`，然后 row -> `LOCKED`
- release escrow = 状态守卫 `LOCKED`，调 `WalletService.releaseEscrowToPayee(...)` 做 direct transfer，row -> `RELEASED`
- refund escrow = `CREATED` 用 `WalletService.releaseHold(...)`，`LOCKED` 用 `WalletService.releaseEscrowToAvailable(...)`，row -> `REFUNDED`
- cancel escrow = 只允许 `CREATED`，用 `WalletService.releaseHold(...)`，row -> `CANCELLED`
- forfeit escrow = 只允许 `LOCKED`，用 `WalletService.forfeitEscrowToPlatform(...)`，row -> `FORFEITED`
- `lockEscrow`、`releaseEscrow`、`refundEscrow`、`forfeitEscrow`、`cancelEscrow` 方法签名必须带 `transitionBusinessRef`
- transition 幂等必须可从 wallet ledger 查出历史迁移；同 `transitionBusinessRef` + 同 escrow/目标状态/金额返回既有结果，不同 ref 撞终态或不同参数必须 reject
- 每个终态迁移必须锁定 escrow row 或使用带 `fromStatus` 的条件更新，确保两个不同 transition 不能并发重复转账；条件更新失败时不得保留已执行的钱包 movement
- timeout 不止存 `expires_at`：提供业务可调用 `resolveExpiredEscrow(escrowId, resolution, transitionBusinessRef, now)`，检查 `expires_at <= now` 后走允许的 release/refund/forfeit/cancel 终态
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
- Modify/Create: `src/main/java/com/tongji/wallet/service/WalletRegistrationGrantService.java`
- Modify: `src/main/java/com/tongji/auth/service/AuthService.java`
- Modify: `src/main/java/com/tongji/user/service/UserService.java`
- Modify: `src/main/java/com/tongji/user/service/impl/UserServiceImpl.java`
- Modify/Create: `src/test/java/com/tongji/wallet/service/WalletRegistrationGrantServiceTest.java`

- [ ] **Step 1: 先写失败的注册赠币单测**

测试用例：
- successful registration creates user, initializes wallet, writes registration grant ledger
- duplicate identifier still fails before wallet grant
- grant failure aborts user creation path
- `AuthService.register()` signs JWT and stores refresh token using orchestration service returned created `User`

- [ ] **Step 2: 跑测试，确认先失败**

Run: `mvn -Dtest=WalletRegistrationGrantServiceTest test`  
Expected: fail，因为当前注册赠币链路仍需满足返回 created user、`grantAmount <= 0` 跳过赠币、以及新的 `grant(..., businessType, businessRef)` 签名。

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
        if (grantAmount <= 0) {
            return created;
        }
        walletService.grant(
            created.getId(),
            grantAmount,
            WalletLedgerReason.REGISTRATION_GRANT,
            WalletBusinessType.REGISTRATION,
            "registration-grant:user:" + created.getId()
        );
        return created;
    }
}
```

- [ ] **Step 5: 修改 `AuthService.register()` 调编排服务**

把直接的 `userService.createUser(user);` 替换成 `walletRegistrationGrantService.createUserAndGrant(user, config-backed grant amount);`

token issue 和 refresh-token store 继续放在成功创建/赠币之后，并且必须使用编排服务返回的 `created` user（含最终 `id`/字段）来签 token、生成响应、存 refresh token；不要继续使用调用前的 transient `user` 对象。

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
- Modify/Create: `src/main/java/com/tongji/wallet/api/WalletController.java`
- Modify/Create: `src/main/java/com/tongji/wallet/api/dto/WalletBalanceResponse.java`
- Modify/Create: `src/main/java/com/tongji/wallet/api/dto/WalletLedgerItemResponse.java`
- Modify/Create: `src/main/java/com/tongji/wallet/api/dto/WalletLedgerResponse.java`
- Modify/Create: `src/test/java/com/tongji/wallet/api/WalletControllerTest.java`

- [ ] **Step 1: 先写失败的 controller test**

接口：
- `GET /api/v1/wallet/me`
- `GET /api/v1/wallet/me/ledger?page=1&pageSize=20`

使用当前安全测试常见的 mocked authenticated principal 模式。

- [ ] **Step 2: 跑测试，确认先失败**

Run: `mvn -Dtest=WalletControllerTest test`  
Expected: fail 或暴露 API drift，因为当前 controller/DTO 必须跟最终 wallet service 查询语义对齐。

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
- 最大运行时风险：注册链路现在依赖钱包写入。回滚路径：把注册赠币金额 feature-flag 到 `0`，保留钱包初始化但跳过 grant write；`amount <= 0` 禁止的是账务 movement，不禁止注册编排层把 `0` 解释为“不发赠币”。
- 数据风险：并发钱包变更丢账。缓解：事务内 `SELECT ... FOR UPDATE` / 原子条件 delta update；测试覆盖并发 mutating operation。
- 数据风险：重试导致重复 ledger 或同 ref 不同参数被吞。缓解：唯一 `business_ref` + 参数一致性校验；回滚靠 replay-safe ledger read 和补偿分录，不改历史行。
- 账务边界：`platform-user-id=0` 是 counterparty sentinel，不是普通用户。本期只追加事实源，不承诺完整复式平账；严格复式/平台余额对账后续 `add-data-reconciliation`。

## 已定配置

- `registration-grant-amount` 默认值使用 `100`，可配置为 `0` 关闭赠币写入。
- `platform-user-id = 0` 已按第一阶段方案保留为账务 counterparty sentinel；day 1 不上 `wallet_subject` 表。

## Delta summary

- 把含糊的 “adapt bytedance wallet” 改成了可执行的 zhiguang 文件地图和本地模型。
- 把注册赠币放进专用编排服务，token/refresh 使用编排服务返回的 created user，在事务成功后处理。
- 选了最小可行数据模型：3 张表、`owner_user_id + business_ref` 幂等约束、`platform-user-id=0` counterparty sentinel，严格对账后续做。
- 补齐并发锁、幂等参数匹配、`amount > 0`、托管 CREATED/LOCKED 资金落点、direct release、transitionBusinessRef、timeout resolver。
- 把公开 API 收窄到只读钱包查询；escrow 暂时主要内部用。

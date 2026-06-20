## Why

zhiguang 当前没有任何商业化账务基础设施，但后续位竞价推广、付费加权推广与悬赏问答都依赖统一的虚拟货币、冻结和托管语义。先建立钱包与托管能力，才能让后续 change 在同一套账务语言下落地。

## What Changes

- 新增用户钱包、平台账本主体、可用余额、冻结余额与托管余额语义。
- 新增只追加交易流水，覆盖发币、补贴、冻结、释放、扣减、退款、没收等账务动作。
- 新增通用托管状态机，供悬赏等 P2P 业务驱动。
- 明确不引入第二套商业身份或认证体系，钱包归属仍绑定现有 `user`。
- 明确本期不支持充值、提现、真实支付网关和平台抽成。

## Capabilities

### New Capabilities
- `wallet-ledger`: 虚拟货币钱包、平台账本主体、余额状态与账务流水。
- `escrow-lifecycle`: 通用托管单据与状态机，承载锁定、放款、退款、没收。

### Modified Capabilities

## Impact

- 新增商业化账务表、托管表与交易流水表。
- 新增钱包与托管 API、领域模型、MyBatis mapper 与对账输入事实源。
- 注册赠币、平台补贴、推广冻结扣减、悬赏托管等后续业务统一依赖本 change。
- `bytedance` 现有 wallet/settlement 代码只保留可复用账务能力，不引入其独立 `account/auth_session` 体系。

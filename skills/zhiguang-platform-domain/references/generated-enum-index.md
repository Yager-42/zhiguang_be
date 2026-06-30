# 平台域枚举索引（自动生成）

该文档从 `promotion` / `wallet` / `moderation` / `reconciliation` 包下的 enum 自动提取，用于标记高漂移的状态常量与流程字面量。改 enum 后请重新刷新生成资产。

```yaml
asset_metadata:
  asset_id: platform-domain.enum-index
  skill: zhiguang-platform-domain
  generated_at: '2026-06-30T06:04:20Z'
  generator: python scripts/skills/refresh_generated_knowledge.py --asset platform-domain.enum-index
  source_inputs:
  - src/main/java/com/tongji/promotion/**/*.java
  - src/main/java/com/tongji/wallet/**/*.java
  - src/main/java/com/tongji/moderation/**/*.java
  - src/main/java/com/tongji/reconciliation/**/*.java
  matched_files_count: 141
  source_hash: e9e004ed48c7537117305e7a512302a2de13de65b7338f1d8bd599f642ea1568
  refresh_trigger:
  - enum file change
  - state or status constant change
  manual_boundary: only enum names, constant literals, and owning files
```

| 模块 | 文件 | enum | 常量 |
| --- | --- | --- | --- |
| `promotion` | `src/main/java/com/tongji/promotion/model/PromotionAuctionWindowStatus.java` | `PromotionAuctionWindowStatus` | `OPEN`, `SETTLED` |
| `promotion` | `src/main/java/com/tongji/promotion/model/PromotionBidStatus.java` | `PromotionBidStatus` | `ACTIVE`, `WON`, `LOST` |
| `promotion` | `src/main/java/com/tongji/promotion/model/PromotionCampaignStatus.java` | `PromotionCampaignStatus` | `ACTIVE` |
| `promotion` | `src/main/java/com/tongji/promotion/bprime/model/PromotionDecisionType.java` | `PromotionDecisionType` | `BID_ACCEPTED`, `BID_REJECTED`, `WINDOW_CLOSED` |
| `promotion` | `src/main/java/com/tongji/promotion/model/PromotionResourceType.java` | `PromotionResourceType` | `FEED_TOP_SLOT`, `SEARCH_TOP_SLOT` |
| `wallet` | `src/main/java/com/tongji/wallet/model/EscrowTimeoutResolution.java` | `EscrowTimeoutResolution` | `RELEASE`, `REFUND` |
| `wallet` | `src/main/java/com/tongji/wallet/model/WalletAccountStatus.java` | `WalletAccountStatus` | `ACTIVE`, `DISABLED` |
| `wallet` | `src/main/java/com/tongji/wallet/model/WalletBusinessType.java` | `WalletBusinessType` | `REGISTRATION`, `PROMOTION`, `BOUNTY`, `SYSTEM` |
| `wallet` | `src/main/java/com/tongji/wallet/model/WalletEscrowStatus.java` | `WalletEscrowStatus` | `CREATED`, `LOCKED`, `RELEASED`, `REFUNDED`, `FORFEITED`, `CANCELLED` |
| `wallet` | `src/main/java/com/tongji/wallet/model/WalletLedgerDirection.java` | `WalletLedgerDirection` | `CREDIT`, `DEBIT` |
| `wallet` | `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java` | `WalletLedgerReason` | `REGISTRATION_GRANT`, `PLATFORM_SUBSIDY`, `HOLD_RESERVE`, `HOLD_RELEASE`, `HOLD_TO_ESCROW`, `PROMOTION_BID_CAPTURE`, `PROMOTION_BID_RELEASE`, `PROMOTION_BPRIME_HOLD`, `PROMOTION_BPRIME_CAPTURE`, `PROMOTION_BPRIME_RELEASE`, `ESCROW_RELEASE`, `ESCROW_REFUND`, `ESCROW_CANCEL`, `ESCROW_FORFEIT` |

# 平台治理与商业化边界

## 覆盖包

- `src/main/java/com/tongji/promotion/**`
- `src/main/java/com/tongji/wallet/**`
- `src/main/java/com/tongji/moderation/**`
- `src/main/java/com/tongji/reconciliation/**`

## 主要入口

- `src/main/java/com/tongji/promotion/api/PromotionController.java`
- `src/main/java/com/tongji/promotion/bprime/service/PromotionCommandProcessingService.java`
- `src/main/java/com/tongji/promotion/bprime/service/PromotionDecisionProjectionService.java`
- `src/main/java/com/tongji/wallet/api/WalletController.java`
- `src/main/java/com/tongji/wallet/service/WalletService.java`
- `src/main/java/com/tongji/moderation/api/ModerationReportController.java`
- `src/main/java/com/tongji/moderation/service/impl/ModerationReportServiceImpl.java`
- `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java`
- `src/main/java/com/tongji/reconciliation/service/impl/ReconciliationServiceImpl.java`

## 当前子域划分

- `promotion`：推广位、竞价窗口、B' 竞价链路、decision log、slot allocation
- `wallet`：余额、ledger、hold、escrow
- `moderation`：内容举报、审核动作、审核通知
- `reconciliation`：修复、重试、重建、按任务回放

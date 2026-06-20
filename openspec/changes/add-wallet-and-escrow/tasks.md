## 1. Schema and Domain

- [x] 1.1 Add wallet account, wallet ledger, and escrow tables with idempotency and business-reference constraints.
- [x] 1.2 Introduce zhiguang-native wallet domain terms and IDs based on `user.id` and platform ledger subject.
- [x] 1.3 Adapt reusable `bytedance` wallet/settlement code to remove independent account/auth assumptions.

## 2. Wallet Ledger

- [x] 2.1 Implement wallet account repository, ledger repository, and transactional balance update flow.
- [x] 2.2 Implement append-only ledger writes for grant, subsidy, hold, release, settlement, refund, and forfeiture.
- [x] 2.3 Implement idempotent business-reference handling for wallet-affecting operations.
- [x] 2.4 Expose wallet balance and ledger query APIs.

## 3. Escrow Lifecycle

- [x] 3.1 Implement generic escrow aggregate, status machine, and transition guards.
- [x] 3.2 Implement escrow creation, lock, release, refund, cancel, and forfeit operations.
- [x] 3.3 Implement timeout-ready expiry metadata and resolution hooks for later business drivers.

## 4. Platform Accounting Integration

- [x] 4.1 Implement platform ledger subject bootstrap and configuration.
- [x] 4.2 Hook registration grant and platform subsidy issuance into wallet ledger flow.
- [x] 4.3 Ensure unsupported recharge and withdrawal paths are absent or explicitly rejected.

## 5. Verification

- [x] 5.1 Add wallet balance transition tests for hold, release, escrow, refund, and forfeiture.
- [x] 5.2 Add idempotency tests for duplicate grant, duplicate hold, and duplicate release.
- [x] 5.3 Add persistence tests for wallet and escrow repositories.

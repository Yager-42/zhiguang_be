# 模块路由

## 主域映射

| Skill | 负责包 | 首要入口 |
| --- | --- | --- |
| `zhiguang-auth-user` | `auth` `user` `profile` | `AuthController` `ProfileController` |
| `zhiguang-content-domain` | `knowpost` `storage` `search` `recommendation` `cache` | `KnowPostController` `StorageController` `SearchController` |
| `zhiguang-social-domain` | `relation` `comment` `counter` `notification` | `RelationController` `CommentController` `ActionController` `NotificationController` |
| `zhiguang-platform-domain` | `promotion` `wallet` `moderation` `reconciliation` | `PromotionController` `WalletController` `ModerationReportController` `ReconciliationController` |
| `zhiguang-common-runtime` | `common` `config` `id` `llm` | `GlobalExceptionHandler` `ThreadPoolConfig` `DefaultIdService` |

## 辅助规则

- `storage` 属于内容主链，除非在查共享对象存储接入问题，否则先归 `zhiguang-content-domain`
- `cache` 目前主要为内容 / feed / 热点保护服务，先归 `zhiguang-content-domain`
- `moderation` 与 `reconciliation` 都是平台治理和修复流程的一部分，先归 `zhiguang-platform-domain`
- `common` / `config` / `id` 改动通常是共享约束，先归 `zhiguang-common-runtime`

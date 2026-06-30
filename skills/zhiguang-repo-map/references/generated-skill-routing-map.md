# Skill 路由映射（自动生成）

该文档从 `scripts/skills/skill_registry.yaml` 和各 `SKILL.md` 的边界约定自动生成，用于 child B / child C 共享同一套 ownership 输入。

```yaml
asset_metadata:
  asset_id: repo-map.skill-routing-map
  skill: zhiguang-repo-map
  generated_at: '2026-06-30T06:04:20Z'
  generator: python scripts/skills/refresh_generated_knowledge.py --asset repo-map.skill-routing-map
  source_inputs:
  - scripts/skills/skill_registry.yaml
  - skills/*/SKILL.md
  matched_files_count: 10
  source_hash: 46d4021e57dd2e3be415b71ba8f1c3edd706ab8f619849e7ef2e6058db264922
  refresh_trigger:
  - skill boundary change
  - skill trigger description change
  manual_boundary: only routing ownership and generated asset ownership facts
```

| Skill | package prefixes | config prefixes | manual targets | generated assets |
| --- | --- | --- | --- | --- |
| `zhiguang-repo-map` | `-` | `-` | `controller:skills/zhiguang-repo-map/references/common-entrypoints.md, routing:skills/zhiguang-repo-map/references/request-to-module-map.md` | `repo-map.api-index, repo-map.skill-routing-map` |
| `zhiguang-business-dictionary` | `-` | `-` | `enum:skills/zhiguang-business-dictionary/references/object-dictionary.md, glossary:skills/zhiguang-business-dictionary/references/object-dictionary.md` | `-` |
| `zhiguang-auth-user` | `auth, user, profile` | `auth` | `controller:skills/zhiguang-auth-user/references/identity-and-profile.md, enum:skills/zhiguang-auth-user/references/identity-and-profile.md, config:skills/zhiguang-auth-user/references/identity-and-profile.md` | `-` |
| `zhiguang-content-domain` | `knowpost, storage, search, recommendation, cache` | `storage, recommendation, feed, cache` | `controller:skills/zhiguang-content-domain/references/content-lifecycle.md, enum:skills/zhiguang-content-domain/references/content-lifecycle.md, config:skills/zhiguang-content-domain/references/storage-and-distribution.md` | `-` |
| `zhiguang-social-domain` | `relation, comment, counter, notification` | `comment, counter` | `controller:skills/zhiguang-social-domain/references/interaction-flows.md, enum:skills/zhiguang-social-domain/references/interaction-flows.md, config:skills/zhiguang-social-domain/references/interaction-flows.md` | `-` |
| `zhiguang-platform-domain` | `promotion, wallet, moderation, reconciliation` | `promotion, wallet, moderation, rocketmq` | `controller:skills/zhiguang-platform-domain/references/platform-flows.md, enum:skills/zhiguang-platform-domain/references/platform-flows.md, config:skills/zhiguang-platform-domain/references/platform-flows.md` | `platform-domain.enum-index` |
| `zhiguang-common-runtime` | `common, config, id, llm` | `server, spring, id, management, canal` | `controller:skills/zhiguang-common-runtime/references/shared-runtime.md, enum:skills/zhiguang-common-runtime/references/shared-runtime.md, config:skills/zhiguang-common-runtime/references/shared-runtime.md` | `common-runtime.config-index` |
| `zhiguang-change-playbook` | `-` | `-` | `review:skills/zhiguang-change-playbook/references/cross-domain-checklist.md` | `-` |
| `zhiguang-debug-playbook` | `-` | `-` | `review:skills/zhiguang-debug-playbook/references/failure-routing.md` | `-` |

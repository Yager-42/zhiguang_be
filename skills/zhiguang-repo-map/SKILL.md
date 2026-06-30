---
name: zhiguang-repo-map
description: zhiguang 仓库导航 Skill。用于快速判断需求应该先落到哪个业务域、哪个 controller、哪个 service 或哪批 reference；当入口不清楚、需要先定位主域、接口前缀、控制器入口或影响面时使用。
---

# zhiguang-repo-map

先用这一层做路由，再切到具体业务 skill。

## 使用顺序

- 先看 `references/module-routing.md`，把需求归到一个主域。
- 再看 `references/request-to-module-map.md`，对照常见需求描述找 skill。
- 再看 `references/common-entrypoints.md`，找到第一批应该打开的 controller / service / config。
- ?????????????? controller ????? `references/generated-api-index.md`?
- ?? skill ownership?generated asset ownership ? child C ????? `references/generated-skill-routing-map.md`?
- 一旦已经明确落到某个领域，就切换到对应 skill，不长期停留在 repo-map。

## 路由原则

- 先判断对象是什么，再判断它属于哪个域。
- 如果需求同时影响两个以上域，立即切到 `zhiguang-change-playbook`。
- 如果现象比需求更明确，或已经在查异常、补偿、恢复，立即切到 `zhiguang-debug-playbook`。

## 参考资料

- `references/module-routing.md`
- `references/request-to-module-map.md`
- `references/common-entrypoints.md`
- `references/generated-api-index.md`
- `references/generated-skill-routing-map.md`
- `scripts/extract_api_index.py`
- `scripts/extract_skill_routing_map.py`

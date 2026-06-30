# Skills Diff Check

- generated_at: 2026-06-30T06:05:31Z
- base_ref: `HEAD`
- changed_files: 3
- impacted_skills: zhiguang-common-runtime
- refreshed_assets: -

## Changed Files

- `skills/README-zh.md`
- `scripts/skills/skill_registry.yaml`
- `src/main/resources/application.yml`

## Hard Errors

- none

## Pending Suggestions

### `SUG-zhiguang-common-runtime-config-7bc26be01d`

- skill: `zhiguang-common-runtime`
- priority: `medium`
- title: 配置契约核对
- target_file: `skills/zhiguang-common-runtime/references/shared-runtime.md`

```yaml
id: SUG-zhiguang-common-runtime-config-7bc26be01d
skill: zhiguang-common-runtime
kind: config
priority: medium
status: pending
target_file: skills/zhiguang-common-runtime/references/shared-runtime.md
mode: drift-note
title: 配置契约核对
evidence:
- src/main/resources/application.yml
symbols:
- application.yml
content: '### 2026-06-30 Drift Note / SUG-zhiguang-common-runtime-config-7bc26be01d

  - 触发类型：`config`

  - 代码事实：`application.yml` 来自 `src/main/resources/application.yml`

  - 建议动作：人工确认 `shared-runtime.md` 是否需要补入这批变更的最新语义。

  '
```

## Apply

人工确认后再执行写回。示例：

```bash
python scripts/skills/skills_guard.py apply --task current --ids SUG-xxxx,SUG-yyyy
```

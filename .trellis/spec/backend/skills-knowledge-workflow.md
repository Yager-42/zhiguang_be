# Skills Knowledge Workflow

> `skills/` 承载业务知识，`.trellis/spec/` 承载工程规范。两者都要维护，但职责不同。

---

## Scenario: Skills 驱动的业务知识维护

### 1. Scope / Trigger

- Trigger:
  - 任务改动命中 `src/main/java/com/tongji/**`
  - 任务改动命中 `src/main/resources/application.yml`
  - 任务改动命中 `skills/**`
  - 任务改动命中 `scripts/skills/**`
  - 需要在 Trellis 流程里把业务知识读入、校验、回写建议接上

### 2. Signatures

- 生成生成型知识资产：

```bash
python scripts/skills/refresh_generated_knowledge.py
python scripts/skills/refresh_generated_knowledge.py --changed-only
python scripts/skills/refresh_generated_knowledge.py --list
python scripts/skills/refresh_generated_knowledge.py --skill zhiguang-common-runtime
python scripts/skills/refresh_generated_knowledge.py --asset common-runtime.config-index
```

- 路由当前改动到受影响 skill：

```bash
python scripts/skills/skills_guard.py route --base-ref HEAD
python scripts/skills/skills_guard.py route --base-ref HEAD --paths src/main/resources/application.yml
```

- 在当前 Trellis task 下生成知识差异检查结果：

```bash
python scripts/skills/skills_guard.py diff-check --task current
python scripts/skills/skills_guard.py diff-check --task current --base-ref HEAD
python scripts/skills/skills_guard.py diff-check --task current --paths src/main/resources/application.yml
```

- 查看 gate 状态：

```bash
python scripts/skills/skills_guard.py status --task current
python scripts/skills/skills_guard.py status --task current --fail-on-hard
python scripts/skills/skills_guard.py status --task current --fail-on-hard --fail-on-high
```

- 人工确认后应用建议：

```bash
python scripts/skills/skills_guard.py apply --task current --ids SUG-xxxx
python scripts/skills/skills_guard.py apply --task current --ids SUG-xxxx,SUG-yyyy
```

### 3. Contracts

- `skills/README-zh.md` 是业务知识入口索引。
- `skills/zhiguang-*/SKILL.md` 只保留路由、入口、硬约束；长说明放各自 `references/`。
- `scripts/skills/skill_registry.yaml` 是 skill 边界、manual target、generated asset 的注册表。
- 当前自动生成资产固定为：
  - `skills/zhiguang-repo-map/references/generated-api-index.md`
  - `skills/zhiguang-repo-map/references/generated-skill-routing-map.md`
  - `skills/zhiguang-common-runtime/references/generated-config-index.md`
  - `skills/zhiguang-platform-domain/references/generated-enum-index.md`
- 生成型资产只能由 `refresh_generated_knowledge.py` 刷新，内容来自源码/配置事实，不手写业务语义。
- `skills_guard.py diff-check` 输出固定写到当前 task 的 `skills-review/`：
  - `skills-suggestions.md`
  - `context-recovery.md`
  - `state.json`
- `skills_guard.py apply` 只允许把已经人工确认的 suggestion 追加写入目标知识文件的 `## Drift Notes`，不会自动改写整页语义。
- Trellis 接入约束：
  - `trellis-before-dev` 先读 `.trellis/spec/`，再从 `skills/README-zh.md` 和路由 skill 读业务知识。
  - `trellis-check` 必须跑 `diff-check` 和 `status --fail-on-hard`。
  - `trellis-finish-work` 必须跑 `status --fail-on-hard --fail-on-high`，有未处理高优先级建议时禁止收尾。

### 4. Validation & Error Matrix

| Condition | Behavior |
| --- | --- |
| 当前没有 Trellis task，执行 `diff-check --task current` 或 `apply --task current` | 直接失败，报 `No current Trellis task` |
| `diff-check` 没扫到任何 changed file | 写出 review 文件，并记录 `hard_errors: ["no changed files detected"]` |
| 某个受影响 skill 缺 `SKILL.md` | `hard_errors` 增加 `<skill>: missing SKILL.md` |
| `SKILL.md` 引用了不存在的 `references/` 或 `scripts/` 文件 | `hard_errors` 增加 `missing referenced file ...` |
| 受影响 skill 声明了 generated asset，但目标文件不存在 | `hard_errors` 增加 `missing generated asset ...` |
| `status --fail-on-hard` 且存在 hard error | 返回非零，阻断 check gate |
| `status --fail-on-high` 且存在 high priority pending suggestion | 返回非零，阻断 finish-work |
| `apply` 指定了不存在的 suggestion id | 直接失败，报 `unknown suggestion id` |
| 人工未确认就直接改业务知识正文 | 不允许。只能先产出 suggestion 文本，再人工点头后执行 `apply` |

### 5. Good/Base/Bad Cases

- Good:
  - 改了 `application.yml`
  - 先执行 `diff-check --task current`
  - 查看 `.trellis/tasks/<task>/skills-review/skills-suggestions.md`
  - 人工确认某条建议
  - 再执行 `apply --task current --ids <suggestion-id>`
  - 最后执行 `status --fail-on-hard --fail-on-high`

- Base:
  - 改了 controller 或 enum
  - `diff-check` 自动刷新对应 generated asset
  - 只生成待确认建议，不自动落语义正文

- Bad:
  - 跳过 `skills_guard.py diff-check`，直接结束任务
  - 未经确认手改 generated file 作为业务真相
  - 把 `.trellis/spec/` 写成业务词典，或者把 `skills/` 当工程规范库

### 6. Tests Required

- Python 语法校验：

```bash
python -m py_compile scripts/skills/skilllib.py
python -m py_compile scripts/skills/skills_guard.py
python -m py_compile scripts/skills/refresh_generated_knowledge.py
python -m py_compile skills/zhiguang-repo-map/scripts/extract_api_index.py
python -m py_compile skills/zhiguang-repo-map/scripts/extract_skill_routing_map.py
python -m py_compile skills/zhiguang-common-runtime/scripts/extract_config_index.py
python -m py_compile skills/zhiguang-platform-domain/scripts/extract_enum_index.py
```

- 生成资产：

```bash
python scripts/skills/refresh_generated_knowledge.py
```

  - 断言 4 个 generated file 都存在且可读。

- Gate 校验：

```bash
python scripts/skills/skills_guard.py diff-check --task current
python scripts/skills/skills_guard.py status --task current --fail-on-hard
```

  - 断言 review 文件写到 `.trellis/tasks/<task>/skills-review/`。
  - 断言 hard error 时 `status --fail-on-hard` 非零。

- 人工确认后应用建议：

```bash
python scripts/skills/skills_guard.py apply --task current --ids <approved-id>
python scripts/skills/skills_guard.py status --task current --fail-on-hard --fail-on-high
```

  - 断言目标知识文件出现对应 `Drift Note / <approved-id>`。
  - 断言 `state.json` 更新 `applied_suggestion_ids`，并移除对应 pending id。

### 7. Wrong vs Correct

#### Wrong

- 把 `skills/` 和 `.trellis/spec/` 混成一个库，不区分业务语义和工程规则。
- 在 `diff-check` 里直接自动覆写业务知识正文。
- 为了“避免失败”，跳过 `status --fail-on-high`。

#### Correct

- `.trellis/spec/` 只记工程执行契约，`skills/` 只记业务知识与业务边界。
- `diff-check` 只产出建议文本和恢复上下文，真正写文件必须人工点头后再 `apply`。
- `before-dev` 读知识，`check` 做 diff gate，`finish-work` 做最终阻断，三段都接进 Trellis。

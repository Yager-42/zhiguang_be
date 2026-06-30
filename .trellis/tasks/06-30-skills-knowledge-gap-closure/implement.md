# Implement Plan - Skills Knowledge Gap Closure

## Ordered Steps

1. 收敛文档协议
   - 盘点哪些 skill 页面需要结构化条目边界
   - 定义程序可识别的 entry 格式
   - 明确 glossary schema 与通用 entry schema 的适用面
   - 固定可写页面与不可写页面边界

2. 建立 wiki / index 骨架
   - 新增 indexer 脚本
   - 生成 page / entry / term / backlink 基础索引
   - 固定 `pages.json` / `entries.json` / `terms.json` / `backlinks.json` / `health.json` / `index-state.json` contract

3. 接入 invalidate / rebuild
   - 让 skill 文档变更与 generated asset 变更都能触发索引失效
   - 在 before-dev / diff-check 前自动判断是否需要重建
   - 重建失败要进入 `hard_errors`

4. 扩展 generated assets
   - 保留现有 4 个 asset
   - 新增 workflow / state asset
   - 必要时补 ownership 相关 asset
   - 只抽源码显式存在的状态与入口事实

5. 实现 matching 层
   - 先支持 canonical / alias / keyword
   - 增加中英文匹配、stop words、relationship propagation
   - 让 `route` / `diff-check` 使用新匹配结果作为增强输入

6. 实现 context budget 分级
   - 固定 `L0` ~ `L3` 读取级别
   - before-dev 默认 `L0/L1`
   - diff-check 按命中结果升级预算
   - 将预算级别记录到 task state

7. 实现 append-only 自动约束
   - duplicate-check
   - oversize-redirect
   - 可识别条目建议生成
   - 不覆写正文主体
   - 真正写文件仍只允许走人工确认后的 `apply`

8. 实现 session dedup
   - 扩展 task 下 `state.json`
   - 按 suggestion id + matched term + target file 去重
   - 补 `seen_match_keys` / `emitted_suggestion_keys`

9. 实现 health governance
   - broken links
   - orphan pages / entries
   - stale references
   - low confidence / contested
   - health score
   - 明确哪些进入 `health_blockers`，哪些进入 `health_high_pending`

10. 扩展 `skills_guard.py` 输出与 gate
   - status 消费 health report
   - diff-check 写建议文本 + health report + recovery text
   - 保持人工确认边界不变
   - 把 health severity 映射到 `--fail-on-hard` / `--fail-on-high`

11. 更新 Trellis 挂接点
   - `trellis-before-dev` 消费索引摘要与预算分级
   - `trellis-check` 运行增强版 diff-check
   - `trellis-finish-work` 消费 health / pending suggestion 状态
   - 同步更新 `.trellis/spec/backend/skills-knowledge-workflow.md`

12. 回填与修正 skill 内容骨架
   - 只补程序需要的结构化壳与索引边界
   - 清理当前几个 skill 中残留的乱码/占位行
   - 不做无证据的大规模语义重写

13. 全量验证
   - Python 语法
   - generated asset refresh
   - index rebuild
   - route / diff-check / status / apply
   - Trellis 三段挂接

## Validation Commands

```bash
python -m py_compile scripts/skills/skilllib.py
python -m py_compile scripts/skills/skills_guard.py
python -m py_compile scripts/skills/refresh_generated_knowledge.py
python -m py_compile scripts/skills/skill_indexer.py
python scripts/skills/refresh_generated_knowledge.py
python scripts/skills/skill_indexer.py rebuild
python scripts/skills/skill_indexer.py health
python scripts/skills/skills_guard.py route --base-ref HEAD
python scripts/skills/skills_guard.py diff-check --task current
python scripts/skills/skills_guard.py status --task current --fail-on-hard
```

需要验证高优先级阻断时，再补：

```bash
python scripts/skills/skills_guard.py status --task current --fail-on-hard --fail-on-high
```

## Review Gates Before `task.py start`

- `prd.md`、`design.md`、`implement.md` 已由用户审阅
- 用户确认按单一大任务执行
- 用户确认本轮只在现有主链上补层，不重写为另一套系统
- 已固定以下 contract：
  - index 文件 contract
  - task state 新字段
  - health severity -> gate 规则
  - 自动动作 / 人工确认动作边界

## Risky Files

- `scripts/skills/skilllib.py`
- `scripts/skills/skills_guard.py`
- `scripts/skills/refresh_generated_knowledge.py`
- `scripts/skills/skill_indexer.py`
- `scripts/skills/skill_registry.yaml`
- `skills/README-zh.md`
- 各 `skills/zhiguang-*/SKILL.md`
- 各 `skills/zhiguang-*/references/*.md`
- `.agents/skills/trellis-before-dev/SKILL.md`
- `.agents/skills/trellis-check/SKILL.md`
- `.agents/skills/trellis-finish-work/SKILL.md`
- `.trellis/spec/backend/skills-knowledge-workflow.md`

## Rollback Points

1. 如果 index 层接入后 route 不稳定，先回退到当前包前缀 / 配置前缀路由。
2. 如果 matching 层误报太多，先保留索引输出，不把它接成 gate 阻断输入。
3. 如果 workflow asset 无法稳定抽取，只保留 enum / status 事实清单，不推进入口级流程索引。
4. 如果 append-only 自动约束误伤现有文档，先退回“只产建议、不自动写结构化条目”。
5. 如果 context budget 分级没有带来有效减负，先保留索引与匹配，不强制 before-dev 消费更高预算层级。

## Done Condition

只有同时满足下面几条，这个任务才允许进入实现完成态：

- 索引层可重建
- 新匹配层已真正参与 route 或 diff-check
- health governance 已有实际输出
- session dedup 已落状态文件
- context budget 已有实际分级消费点
- workflow generated asset 已有至少一份真实产物
- 人工确认边界仍未被突破

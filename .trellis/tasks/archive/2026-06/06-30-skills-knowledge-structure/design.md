# Child Design - Structure

## Scope

本子任务只处理 `skills/` 的业务知识结构，不处理自动抽取和自动防腐化，也不改 Trellis 机制。

## Knowledge Split

### `skills/`

负责：

- 业务路由
- 领域词典
- 真相源
- 关键入口
- 变更剧本
- 排障剧本
- skill 内结构化索引条目
- knowhow / reference 分类

### `.trellis/spec/`

继续负责：

- 工程规范
- 测试要求
- logging / error handling / db guideline
- review checklist
- 可复用实现约束

## Imported Protocols From maestro-flow

### 1. Structured Entry Protocol

skill 内索引文档不只写自然语言段落，保留结构化字段，参考 `maestro-flow` 的 `<spec-entry>`：

- `category`
- `keywords`
- `date`
- `ref`
- `confidence`
- `conflict-marker`
- `conflict-note`

必要时允许 `description` 作为搜索摘要字段，允许 `domain` 作为术语锚点字段。

### 2. Knowhow Classification

`references/` 下采用可枚举的文档前缀分类，参考 `maestro-flow`：

- `RCP-`：recipe / 操作剧本
- `TIP-`：tip / 快速提示
- `DCS-`：decision / 设计决策
- `AST-`：asset / 代码资产或契约摘要
- `REF-`：reference / 外部资料摘要
- `DOC-`：document / 长文档兜底

本项目首版不必把全部类型都用满，但命名协议要统一。

### 3. Business Glossary Schema

`zhiguang-business-dictionary` 不再只是散文式词典，而是采用结构化字段，参考 `maestro-flow`：

- `id`
- `canonical`
- `aliases`
- `definition`
- `relationships`
- `keywords`
- `tier`
- `status`
- `source`

### 4. Append-Only Write Model

参考 `maestro-flow` 的 `spec-writer` / `wiki writer`：

- 索引页采用 append-only，不允许随意 body overwrite
- 以标题做 duplicate check
- 长内容不直接塞进索引页，转为 `references/` 文档并在索引条目中只保留摘要 + `ref`
- skill 文档更新要偏向“新增条目”而不是“覆写整页”

### 5. Seed Skeleton Mindset

参考它的 `spec-seeds`：骨架文件不是裸目录占位，而是带 frontmatter / section heading 的稳定模板。对应到本项目，首版 skill 骨架应至少定义：

- 这个 skill 是什么
- 何时加载
- 先看哪些文件 / references
- 该 skill 下有哪些条目类型和文档类型

### 6. Progressive Loading Layout

参考 `comet` 把重复协议从主 skill 拆到 `reference/*.md` 的做法：

- 主 `SKILL.md` 只保留：
  - 适用时机
  - 主线边界
  - 必须先看的少量文件
  - 不可违背的硬约束
- 长说明下沉到 `references/`：
  - 业务流程
  - 术语扩展
  - 场景案例
  - generated 资产说明
  - diff-check 恢复说明

这样做的目标是减少每次技能装载时的固定 token，而不是改变知识结构本身。

## Deliverables

- `skills/README-zh.md`
- 9 个 skill 目录
- 每个 skill 的 `SKILL.md`
- 每个 skill 的 `agents/openai.yaml`
- 初版 `references/` 目录布局
- skill 索引条目字段约定
- knowhow 文档分类约定
- business glossary schema 约定
- append-only / duplicate-check / ref 约定
- 主 skill 与 `references/` 的渐进加载边界约定


## Key Decisions

- `skills/` 是仓库共享资产
- 中文为主，代码锚点保留英文
- 首版全量 9 个 skills
- 先骨架，后内容
- 不修改 `trellis-before-dev` / `trellis-update-spec` / `.trellis/workflow.md`
- 直接吸收 `maestro-flow` 已核实的协议和写入规则，不自行发明新字段集

## Special Migration

- 旧 `CONTEXT.md` 不保留兼容壳
- 有效术语迁移到 `zhiguang-business-dictionary`
- 历史文档坏链不处理







# Child Implement Plan - Structure

## Ordered Steps

1. 创建 `skills/`
2. 初始化 9 个 skill 骨架
3. 写 `README-zh.md`
4. 在 README 中写清 `skills/` 与 `.trellis/spec/` 的职责边界
5. 为 skill 内索引条目定下字段协议：`category`、`keywords`、`date`、`ref`、`confidence`、`conflict-marker`、`conflict-note`
6. 为 `references/` 定下文档前缀分类：`RCP-`、`TIP-`、`DCS-`、`AST-`、`REF-`、`DOC-`
7. 为 `zhiguang-business-dictionary` 定下 glossary schema
8. 定下 append-only / duplicate-check / long-content-ref 规则
9. 迁移 `CONTEXT.md` 有效内容到 `zhiguang-business-dictionary`
10. 删除 `CONTEXT.md`
11. 填充每个 skill 的薄 `SKILL.md`
12. 建立 `references/` 目录占位

## Validation

- 目录存在
- skill 命名一致
- README 路由与 skill 目录一致
- 文档中明确区分业务知识层与工程规范层
- 条目协议字段在所有 skill 中一致
- knowhow 前缀分类在所有 skill 中一致
- glossary schema 能表达旧 `CONTEXT.md` 中的主术语关系
- 索引条目与长文档的职责边界清晰

## Rollback Point

- 若领域拆分不稳定，停在骨架生成后，不继续补内容
- 若 glossary schema 无法承接旧词典，停在 schema 调整前，不删除 `CONTEXT.md`
- 若 append-only 规则与 skill 结构冲突，先保留协议，不进入真实写入实现

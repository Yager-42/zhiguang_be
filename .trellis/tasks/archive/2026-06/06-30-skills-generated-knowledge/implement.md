# Child Implement Plan - Generated Knowledge

## Ordered Steps

1. 确定生成资产清单
2. 确定每个资产的输入源
3. 设计脚本位置与命名
4. 设计 generated 输出文件命名
5. 定义元信息头字段
6. 定义刷新规则和调用时机
7. 定义刷新后的 invalidate / rebuild 动作

## Validation

- generated 资产能映射到 skill
- 输出命名统一
- 不与手写文档职责重叠
- 刷新后能明确说明哪些缓存或索引需要失效

## Dependency

- 依赖 `06-30-skills-knowledge-structure` 的 skill 边界

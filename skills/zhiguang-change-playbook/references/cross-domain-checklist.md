# 跨域变更检查清单

## 先判断是否跨域

满足任意一条就按跨域处理：

- 改一个 controller，但下游还会影响事件、缓存、搜索、推荐或通知
- 改 `status` / `visible` / `hold` / `escrow` / `allocation` 等核心语义字段
- 改 DTO，同时要改消费方、查询视图或重建逻辑
- 改主记录，同时有 counter / notification / reconciliation / index 投影依赖它

## 常见跨域组合

- auth-user -> content / social / platform
- content -> social / discovery
- social -> notification / counter / recommendation
- platform -> content / social

## 每次都要落的检查项

- 主写链改了什么
- 派生链哪些要跟着改
- 哪些 DTO / event / enum / config 会变
- 哪些 skill 文档要同步更新
- 哪个环节是回滚点

## Context

zhiguang 当前已有 `slot-auction-promotions` 主规格和实现：推广活动、竞价窗口、推广出价、窗口关闭后 Java 排序 GSP 结算、MySQL slot allocation、feed/search 读 allocation。这个模型能表达固定位置竞拍，但没有完整接入 `/Volumes/lexar/revive/bytedance` 的 B' 高并发竞价链路。

bytedance 当前权威 B' 链路为 HTTP bid attempt -> RocketMQ ordered commands -> Redis Lua 权威决策 -> Kafka decisions.v2 -> projection consumers -> MySQL facts/checkpoints。Redis Lua 管当前价、领先者、状态、倒计时、Top30 ranking 和 command replay；Kafka decision log 是用户可见确认前的持久决策日志。

`add-paid-boost-promotions` active change 定义的是非拍卖“买排序权重”。用户已决定 promotion 本期完全沿用 bytedance 竞价链路，并改为固定数量商业位置竞拍，所以 paid boost 不再进入本期产品范围。

## Goals / Non-Goals

**Goals:**
- 用 B' 链路替换当前 promotion 出价写路径和窗口决策路径。
- 将 promotion 统一为 fixed position auction：竞拍 feed/search 固定数量商业位置。
- 引入 RocketMQ ordered command、Redis Lua decision、Kafka decision log、projection checkpoint 和 snapshot/recovery。
- 保留 zhiguang 内容社区语义：资源是 `feed_top_slot` / `search_top_slot`，目标是 post，不引入 product/live room/account/auth_session。
- 保留多槽位 GSP 清算：窗口关闭后 TopN 固化 allocation，winner capture clearing price，release excess；loser release hold。
- 移除 paid boost / paid ranking weight / `organic score + boost effect` 本期方向。

**Non-Goals:**
- 不引入 bytedance 商品、直播房间、拍卖运营账号或独立认证域。
- 不让 feed/search 请求内执行竞价。
- 不支持非拍卖 paid boost。
- 不把 WebSocket room 做成商业展示必需路径；它只服务竞价状态可见性和恢复。
- 不承诺生产级多集群 SLA；先按本地 Docker Compose 和单集群可验证链路落地。

## Decisions

### 1. Promotion 统一为 fixed position auction

商业化推广资源是固定数量位置，不是排序权重。`feed_top_slot` 与 `search_top_slot` 每个窗口有 `slotCount`、`reservePrice`、窗口起止时间和展示起止时间。创作者为 post 提交 bid command，窗口内 Redis 维护排名，窗口关闭后 projection 固化 slot allocation。

备选：保留 paid boost 平行产品。拒绝，因为它没有 winner/decision/allocation，与完整 bytedance 竞价链路目标冲突。

### 2. RocketMQ 承担 ordered command input

为了尽量还原 bytedance B'，引入 RocketMQ topic `zhiguang_promotion_auction_commands_v2`。消息 key 使用 `auctionWindowId`，同窗口命令进入有序消费。HTTP submit 只持久化/发送 command，不直接改 MySQL bid ranking。

备选：用 Kafka keyed partition 替代 RocketMQ。技术上可行，但用户选择完整沿用 bytedance 链路，因此不采用。

### 3. Redis Lua 是窗口内决策权威

Redis Lua 脚本按 `auctionWindowId` 管理热状态：
- window status、start/end、slotCount、reservePrice
- command replay hash，保证同 command 幂等
- bidder hold state / bid amount
- TopN ranking ZSet
- accepted/rejected decision payload

脚本只做窗口内 deterministic decision。MySQL projection 落事实，feed/search 仍读 MySQL/Redis allocation cache。

### 4. Kafka decision log 是确认边界

command consumer 执行 Redis Lua 后，必须把 decision append 到 Kafka `zhiguang.promotion.auction.decisions.v2`。用户可见的 accepted/rejected 以 decision log 成功写入为确认边界。MySQL projection 消费 decision log；WebSocket/fanout 不在本期实现。

### 5. GSP 清算在 window close projection 阶段完成

B' 链路只负责高并发 bid command 决策和 ranking 权威；窗口关闭时由 close command / closer job 生成 final decisions。projection consumer 按排名和 reserve price 计算 clearing price：
- TopN winner -> capture clearing price, release bidAmount - clearingPrice
- loser -> release full hold
- below reserve -> rejected or loser，不占位
- allocation effective period = bidding window close 后的展示窗口

### 6. Wallet 以 decision/projection 幂等驱动

HTTP submit 不直接扣减最终费用。accepted bid decision 需要确保最高申报额 hold 已存在或完成增量 hold；outbid/loser/final close 通过 projection 幂等释放/扣减。所有 wallet movement 以 decisionId / commandId / bidId 组成 businessRef，重复消费必须返回相同结果。

### 7. Snapshot/recovery 服务竞价 UI 和运维

新增 auction snapshot API，用 Redis hot state + MySQL projection 回放生成当前窗口状态。本期不实现 WebSocket/SSE；客户端和运维以 snapshot 恢复，不依赖事件重放。

### 8. Archive 不回写，active paid boost 删除

不修改 `openspec/changes/archive/2026-06-21-add-slot-auction-promotions` 历史目录。新 change 修改主规格并取代其实现方向。删除 active `openspec/changes/add-paid-boost-promotions`，避免 apply 列表继续出现已废弃产品。

## Risks / Trade-offs

- [基础设施变重] → 引入 RocketMQ 增加本地和部署复杂度；用 Docker Compose healthcheck、配置开关和集成测试兜底。
- [双日志顺序复杂] → RocketMQ commands 与 Kafka decisions 边界易混；文档和代码命名必须固定 commands.v2 / decisions.v2，禁止把 WebSocket 当权威。
- [Redis/MySQL/Kafka 不一致] → projection checkpoint、decision replay、reconciliation task 必须首期落地。
- [钱包重复扣放风险] → wallet businessRef 判等字段必须包含 owner、amount、reason、businessType、delta，重复消费只允许同参幂等。
- [旧 paid boost 代码/文档残留] → 本 change 先删除 OpenSpec active change 和 PRD/CONTEXT 口径，后续实现阶段删除代码与 schema。
- [GSP 与实时竞价语义差异] → Redis 只维护 TopN 和有效 bid，最终价格窗口关闭后确定；UI 文案必须表达“当前排名/预计清算”，不表达即时成交价。

## Migration Plan

1. 删除 active `add-paid-boost-promotions` OpenSpec change，更新 PRD/CONTEXT 为 fixed position auction-only。
2. 增加 RocketMQ 到本地依赖与配置，但默认仅 promotion auction 使用。
3. 新增 B' command/decision/projection 表和 topics，保留旧 promotion 表用于迁移。
4. 切换 promotion bid submit API 到 command path。
5. projection 同步写旧 allocation 读模型，feed/search 无需一次性重写。
6. 验证 B' 链路稳定后，删除 paid boost 代码/schema 与旧 direct-submit 入口。

## Open Questions

- RocketMQ Java 客户端选 Apache RocketMQ Spring Boot starter 还是原生 client，需要实现阶段按 Spring Boot 3.2 兼容性确认。
- 本期固定为 snapshot API，不暴露 WebSocket/SSE。
- 是否允许同一 creator 对同一 window 多次加价；推荐允许，命令幂等键区分重试和新 bid。

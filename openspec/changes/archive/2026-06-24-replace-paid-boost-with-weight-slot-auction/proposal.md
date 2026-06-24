## Why

当前推广能力名称和部分实现仍残留 `paid boost` / `weight slot auction` 语义，容易把商业化推广误解成“买推荐排序权重”。这会偏离已经确定的产品目标：平台拿 `feed_top_slot` / `search_top_slot` 等固定广告位作为拍卖标的，创作者竞价获得展示权，把自己的 post 放上去。

BD 链路对齐也不完整：主竞价链路已经使用 command、Redis Lua、Kafka decision log 和 projection，但实时展示层没有明确对齐 BD 的 fanout 方式。需要把 Kafka decision log 同时作为 projection 和 WebSocket fanout 的输入，避免把 snapshot polling 当成完整实时竞价体验。

## What Changes

- **BREAKING**: 废弃 active change 名称中的 `paid boost` / `weight` 方向；本变更只定义固定广告位正向拍卖，不支持 paid boost、排序加权或 `organic score + boost effect`。
- 将推广业务语言固定为“广告位拍卖”：平台售卖固定广告位资源，创作者/推广活动竞价，赢家获得 slot allocation 并展示对应 post。
- 保留现有 B' 主链路：HTTP bid command -> RocketMQ ordered command -> Redis Lua decision -> Kafka decision log。
- 补齐 BD-style 实时展示链路：Kafka decision topic 由 projection consumer 和 fanout consumer 并行消费。
- WebSocket/STOMP 只做用户展示消息，不做权威事实源；权威仍是 Kafka decision log，恢复兜底仍是 snapshot API。
- fanout consumer SHALL 推送排名/出价确认/拒绝/窗口关闭等事件；客户端可用事件直接更新 UI，也可在版本缺口或重连时拉 snapshot。
- MySQL projection 只负责最终竞价结果、projection checkpoint、GSP settlement / wallet effects 和 slot allocation；不作为每条 bid decision payload 的高频事件库。
- feed/search 请求仍只读已投影 slot allocation，不在请求路径内执行竞价。

## Capabilities

### New Capabilities
- `promotion-auction-realtime`: Defines BD-style Kafka decision fanout and WebSocket/STOMP realtime event delivery for promotion auction windows.

### Modified Capabilities
- `bprime-position-auctions`: Clarify that Kafka decision log feeds both projection and realtime fanout; snapshot is recovery authority, not the normal realtime delivery path.
- `slot-auction-promotions`: Replace paid boost / weight language with fixed advertising-slot auction semantics where slot resources are auctioned goods.
- `recommendation-feed`: Clarify feed/search consume fixed slot allocations and commercial labels only; no paid boost scoring or ranking weight path.

## Impact

- OpenSpec: adds realtime fanout capability and tightens existing B' / slot auction / recommendation feed requirements.
- Backend: likely affects promotion B' Kafka listeners, WebSocket/STOMP config, event DTOs, snapshot APIs, command/decision services, and tests.
- APIs: may add promotion auction WebSocket topics and client event contracts; snapshot remains available for reconnect/version-gap recovery.
- Infrastructure: reuses existing Kafka, RocketMQ, Redis, MySQL, and Spring WebSocket/STOMP patterns from BD-style chain.
- Product behavior: creator-facing auction UI can show realtime ranking, bid confirmation, bid rejection, and window lifecycle without treating WebSocket as a fact source.

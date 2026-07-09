# Jackson hash round-trip：Map<String,Object> 里的 record 退化导致 hash 不幂等

## 背景

bprime 推广竞价结算链路用 "序列化 → SHA-256 hash → Kafka → 反序列化 → 重算 hash 比对" 做决策防篡改（`PromotionDecisionHasher` + `PromotionDecisionKafkaSupport.requireDecision`）。

`WINDOW_CLOSED` decision 投到 Kafka 后，projection 消费者校验 hash 失败抛 `decision hash mismatch` → 消费停滞 → `settleWindow` 不执行 → `promotion_slot_allocation` 不生成 → 置顶无效。`BID_ACCEPTED` decision 却一切正常。

## 结论

**`Map<String,Object>` 里的 record value，序列化-反序列化 round-trip 后字段顺序会变，导致 hash 不幂等。**

`PromotionDecisionHasher.CANONICAL_MAPPER` 配了 `ORDER_MAP_ENTRIES_BY_KEYS=true`（Map 按 key 字母序序列化）。但这个开关**对 record 无效**——record 始终按声明顺序序列化。

`PromotionAuctionDecision.payload` 是 `Map<String,Object>`，`WINDOW_CLOSED` 往里塞了两个 record list：
- `payload.walletEffects` = `List<PromotionWalletEffect>`
- `payload.finalRanking` = `List<PromotionRankingItem>`

| 侧 | payload 里 value 的运行时类型 | 序列化字段顺序 |
|---|---|---|
| 生产者（Java 构造） | record | record 声明顺序（ORDER_MAP 对 record 无效） |
| 消费者（反序列化 `Map<String,Object>`） | LinkedHashMap（record 退化） | key 字母序（ORDER_MAP 生效） |

两边 JSON 字符串不同 → SHA-256 不同 → hash mismatch。

`BID_ACCEPTED` 不受影响，因为它的 `ranking` 是 decision **顶层强类型字段** `List<PromotionRankingItem>`（不是 Map 的 value），生产者消费者两侧都是 record，序列化顺序一致。

**修复**：给 record 加 `@JsonPropertyOrder(alphabetic = true)`，强制 record 也按字母序序列化，对齐 LinkedHashMap 退化后的顺序。

**通用教训**：任何 "序列化 → hash → 反序列化 → 重算 hash 比对" 的链路，若中间载体是 `Map<String,Object>`（弱类型），里头的强类型对象（record/POJO）反序列化时会退化成 Map，序列化顺序可能与原对象不一致。两个解法：
1. 用强类型 record 替代 `Map<String,Object>`（彻底，但改动大）
2. 给 record 显式 `@JsonPropertyOrder(alphabetic=true)`，与 `ORDER_MAP_ENTRIES_BY_KEYS` 对齐（最小改动）

## 证据

- 根因代码：`src/main/java/com/tongji/promotion/bprime/model/PromotionDecisionHasher.java`（CANONICAL_MAPPER 配 ORDER_MAP_ENTRIES_BY_KEYS）
- 修复：`PromotionWalletEffect.java` + `PromotionRankingItem.java` 加 `@JsonPropertyOrder(alphabetic = true)`
- 复现测试：`src/test/java/com/tongji/promotion/bprime/model/PromotionDecisionHashRoundTripReproTest.java`——修复前 `windowClosedDecisionHashSurvivesRoundTrip` FAILED（`d04a5527 ≠ 5b7fa332`），`bidAccepted` PASSED；修复后两者均 PASSED
- issue 完整分析：`.codestable/issues/2026-07-08-promotion-decision-hash-mismatch/2026-07-08-promotion-decision-hash-mismatch-fix-note.md`
- 端到端验证（2026-07-09）：出价 20 → bid 写入 + 钱包 hold → 窗口结算 → allocation 生成（slot_index=0, clearing_price=1）+ 钱包 capture/release，全链路 0 mismatch

误判澄清：曾怀疑是 snapshot DTO long→String 改动影响 hash，实测 long→String 在 Jackson 循环里幂等（数字 `123` ↔ 字符串 `"123"` 各自稳定），与此 bug 无关。这是 bprime 既有 bug，只是首次走到 WINDOW_CLOSED 路径才暴露。

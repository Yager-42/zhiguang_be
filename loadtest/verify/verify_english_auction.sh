#!/bin/sh
# T2 Lua 行为验证矩阵：initialize/decision/close/snapshot（英式升价迁移）
# 用法：wsl sh /mnt/e/idk/zhiguang_be/loadtest/tmp/verify_english_auction.sh
PASS=0
FAIL=0
D="docker exec zhiguang-redis"
R="redis-cli"

check() { # $1 name, $2 pattern, $3 output
  if echo "$3" | grep -q "$2"; then
    PASS=$((PASS+1)); echo "PASS: $1"
  else
    FAIL=$((FAIL+1)); echo "FAIL: $1 -> output=[$3]"
  fi
}

check_absent() { # $1 name, $2 pattern, $3 output
  if echo "$3" | grep -q "$2"; then
    FAIL=$((FAIL+1)); echo "FAIL: $1 -> unexpected [$2] in [$3]"
  else
    PASS=$((PASS+1)); echo "PASS: $1"
  fi
}

LUA=/mnt/e/idk/zhiguang_be/src/main/resources/redis/lua
for f in promotion-auction-initialize.lua promotion-auction-decision.lua \
         promotion-auction-close.lua promotion-auction-snapshot.lua; do
  docker cp "$LUA/$f" zhiguang-redis:/tmp/
done

P="promotion:auction:{9001}"
reset_window() {
  $D $R DEL "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
    "${P}:campaign:20002" "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" >/dev/null
}
now_ms() { $D $R TIME | awk 'NR==1{s=$1} NR==2{u=$2} END{print s*1000+int(u/1000)}'; }

echo "=== U1 initialize 新建窗口（全字段） ==="
reset_window
NOW=$(now_ms)
END=$((NOW+60000))
OUT=$($D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5)
check "U1 init OK" "^OK$" "$OUT"
check "U1 currentPrice=reserve" "^50000$" "$($D $R HGET "${P}:state" currentPriceCents)"
check "U1 increment=100" "^100$" "$($D $R HGET "${P}:state" incrementCents)"
check "U1 cap=0" "^0$" "$($D $R HGET "${P}:state" capPriceCents)"
check "U1 extendWindowSec=10" "^10$" "$($D $R HGET "${P}:state" extendWindowSec)"
check "U1 extendSec=10" "^10$" "$($D $R HGET "${P}:state" extendSec)"
check "U1 maxExtensions=5" "^5$" "$($D $R HGET "${P}:state" maxExtensions)"
check "U1 winner empty" "^$" "$($D $R HGET "${P}:state" winnerCampaignId)"
check "U1 extendCount=0" "^0$" "$($D $R HGET "${P}:state" extendCount)"
check "U1 bidCount=0" "^0$" "$($D $R HGET "${P}:state" bidCount)"

echo "=== U2 initialize 幂等 / 参数冲突 ==="
OUT=$($D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5)
check "U2 同参幂等 OK" "^OK$" "$OUT"
OUT=$($D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 60000 1 feed_top_slot 0 86400 100 0 10 10 5)
check "U2 参数冲突 MISMATCH" "REDIS_STATE_MISMATCH" "$OUT"

echo "=== U3 HSETNX 回填旧热状态 ==="
reset_window
$D $R HSET "${P}:state" decisionVersion 0 status OPEN windowEndAtEpochMs "$END" \
  reservePrice 50000 slotCount 1 resourceType feed_top_slot >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5)
check "U3 回填 OK" "^OK$" "$OUT"
check "U3 新字段补齐" "^100$" "$($D $R HGET "${P}:state" incrementCents)"
check "U3 currentPrice 回填=reserve" "^50000$" "$($D $R HGET "${P}:state" currentPriceCents)"
check "U3 旧字段保留" "^0$" "$($D $R HGET "${P}:state" decisionVersion)"

echo "=== U4 接受出价（共享价/赢家/版本） ==="
reset_window
NOW=$(now_ms); END=$((NOW+60000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 "20001:currentHold" 0 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-1 h-9001-1 101 50100 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U4 接受" '"type":"BID_ACCEPTED"' "$OUT"
check "U4 版本 1" '"decisionVersion":1' "$OUT"
check "U4 currentPrice=50100" "^50100$" "$($D $R HGET "${P}:state" currentPriceCents)"
check "U4 winner=20001" "^20001$" "$($D $R HGET "${P}:state" winnerCampaignId)"
check "U4 bidCount=1" "^1$" "$($D $R HGET "${P}:state" bidCount)"
OUT=$($D $R XRANGE "${P}:events" - + COUNT 5)
check "U4 Stream v1-0" "^1-0" "$OUT"
check "U4 currentHold=50100" "^50100$" "$($D $R HGET "${P}:escrow" "20001:currentHold")"

echo "=== U5 拒绝：低于共享台阶（带 requiredAmount/currentPriceCents） ==="
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20002" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-2 h-9001-2 102 50050 9001 20002 post-10 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U5 拒绝 BID_NOT_HIGHER" '"rejectionReason":"BID_NOT_HIGHER"' "$OUT"
check "U5 requiredAmount=50200（共享价已被 U4 抬到 50100）" '"requiredAmount":50200' "$OUT"
check "U5 currentPriceCents 载荷" '"currentPriceCents":50100' "$OUT"
OUT=$($D $R XRANGE "${P}:events" - + COUNT 5)
check "U5 拒绝不写 Stream（仍只有 v1）" "^1-0" "$OUT"

echo "=== U6 首价必须 >= reserve+increment（BELOW_RESERVE 并入） ==="
reset_window
NOW=$(now_ms); END=$((NOW+60000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-3 h-9001-3 101 50050 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U6 首价不足拒绝" '"rejectionReason":"BID_NOT_HIGHER"' "$OUT"
check "U6 requiredAmount=50100" '"requiredAmount":50100' "$OUT"

echo "=== U7 反狙击延长（endAt-now<=extendWindowSec → AUCTION_EXTENDED @v+2） ==="
reset_window
NOW=$(now_ms); END=$((NOW+5000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-4 h-9001-4 101 50100 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U7 接受" '"type":"BID_ACCEPTED"' "$OUT"
OUT=$($D $R XRANGE "${P}:events" - + COUNT 5)
check "U7 第二事件 v2" "^2-0" "$OUT"
check "U7 AUCTION_EXTENDED" '"type":"AUCTION_EXTENDED"' "$OUT"
check "U7 extendCount=1" "^1$" "$($D $R HGET "${P}:state" extendCount)"
check "U7 endAt 延长 10s" "^$((END+10000))$" "$($D $R HGET "${P}:state" windowEndAtEpochMs)"
check "U7 version=2" "^2$" "$($D $R HGET "${P}:state" decisionVersion)"

echo "=== U8 超过 maxExtensions 接受但不延长 ==="
reset_window
NOW=$(now_ms); END=$((NOW+5000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 2 >/dev/null
$D $R HSET "${P}:state" extendCount 2 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-5 h-9001-5 101 50100 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U8 接受" '"type":"BID_ACCEPTED"' "$OUT"
OUT=$($D $R XRANGE "${P}:events" - + COUNT 5)
check_absent "U8 无第二事件" "AUCTION_EXTENDED" "$OUT"
check "U8 endAt 不变" "^$END$" "$($D $R HGET "${P}:state" windowEndAtEpochMs)"

echo "=== U9 cap-hit 提前 SOLD（AUCTION_SOLD @v+2） ==="
reset_window
NOW=$(now_ms); END=$((NOW+60000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 50500 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-6 h-9001-6 101 50500 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U9 接受 cap 价" '"type":"BID_ACCEPTED"' "$OUT"
OUT=$($D $R XRANGE "${P}:events" - + COUNT 5)
check "U9 第二事件 v2" "^2-0" "$OUT"
check "U9 AUCTION_SOLD" '"type":"AUCTION_SOLD"' "$OUT"
check "U9 winner 载荷" '"winnerCampaignId":"20001"' "$OUT"
check "U9 winningAmount" '"winningAmount":50500' "$OUT"
check "U9 status=SOLD" "^SOLD$" "$($D $R HGET "${P}:state" status)"

echo "=== U10 MAX_MONEY 守卫（required=0） ==="
reset_window
NOW=$(now_ms); END=$((NOW+60000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 9007199254740991 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-7 h-9001-7 101 9007199254740992 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U10 超限拒绝" '"rejectionReason":"BID_NOT_HIGHER"' "$OUT"
check "U10 required=0" '"requiredAmount":0' "$OUT"

echo "=== U11 幂等重放（同 commandId 返回原裁决，不写新事件） ==="
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-7 h-9001-7 101 9007199254740992 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U11 重放原裁决" '"rejectionReason":"BID_NOT_HIGHER"' "$OUT"
OUT=$($D $R XLEN "${P}:events")
check "U11 Stream 无新增" "^0$" "$OUT"

echo "=== U12 双终态竞争：cap-hit 后 close → ALREADY_TERMINAL ==="
reset_window
NOW=$(now_ms); END=$((NOW+60000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 50500 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
$D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-12 h-9001-12 101 50500 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-close.lua \
  "${P}:state" "${P}:events" "${P}:pub" , 9001 86400)
check "U12 ALREADY_TERMINAL" '"status":"ALREADY_TERMINAL"' "$OUT"

echo "=== U13 close 未到期 NOT_DUE ==="
reset_window
NOW=$(now_ms); END=$((NOW+60000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
$D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-8 h-9001-8 101 50100 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-close.lua \
  "${P}:state" "${P}:events" "${P}:pub" , 9001 86400)
check "U13 NOT_DUE" '"status":"NOT_DUE"' "$OUT"

echo "=== U14 close 到期有 winner → AUCTION_SOLD ==="
$D $R HSET "${P}:state" windowEndAtEpochMs 1 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-close.lua \
  "${P}:state" "${P}:events" "${P}:pub" , 9001 86400)
check "U14 AUCTION_SOLD" '"type":"AUCTION_SOLD"' "$OUT"
check "U14 winnerCampaignId" '"winnerCampaignId":"20001"' "$OUT"
check "U14 winningAmount=50100" '"winningAmount":50100' "$OUT"
check "U14 actualEndAtEpochMs" '"actualEndAtEpochMs"' "$OUT"
check "U14 status=SOLD" "^SOLD$" "$($D $R HGET "${P}:state" status)"
echo "--- U14b close 幂等重放 closeResult ---"
OUT=$($D $R --eval /tmp/promotion-auction-close.lua \
  "${P}:state" "${P}:events" "${P}:pub" , 9001 86400)
check "U14b 重放同一决策" '"type":"AUCTION_SOLD"' "$OUT"

echo "=== U15 close 到期无 winner → AUCTION_NO_BID ==="
reset_window
END=1
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-close.lua \
  "${P}:state" "${P}:events" "${P}:pub" , 9001 86400)
check "U15 AUCTION_NO_BID" '"type":"AUCTION_NO_BID"' "$OUT"
check "U15 status=NO_BID" "^NO_BID$" "$($D $R HGET "${P}:state" status)"

echo "=== U16 snapshot 英式字段 ==="
reset_window
NOW=$(now_ms); END=$((NOW+60000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
$D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-9 h-9001-9 101 50100 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-snapshot.lua \
  "${P}:ranking" "${P}:state" , "${P}:campaign:" 30)
check "U16 snapshot JSON" '"decisionVersion":1' "$OUT"
check "U16 currentPriceCents" '"currentPriceCents":50100' "$OUT"
check "U16 winnerCampaignId" '"winnerCampaignId":"20001"' "$OUT"
check "U16 rules" '"stepCents":100,"capCents":0,"reserveCents":50000,"maxExtensions":5,"antiSnipeWindowMs":10000' "$OUT"
check "U16 ranking 含 20001" '"campaignId":"20001"' "$OUT"

echo "=== U17 旧热状态缺 increment → REDIS_STATE_INCOMPLETE ==="
reset_window
NOW=$(now_ms); END=$((NOW+60000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
$D $R HDEL "${P}:state" incrementCents >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
OUT=$($D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-10 h-9001-10 101 50100 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5)
check "U17 STATE_INCOMPLETE" '"rejectionReason":"REDIS_STATE_INCOMPLETE"' "$OUT"

echo "=== U18 延长后 close 在旧 endAt 时刻 → NOT_DUE（自愈） ==="
reset_window
NOW=$(now_ms); END=$((NOW+5000))
$D $R --eval /tmp/promotion-auction-initialize.lua \
  "${P}:state" "${P}:ranking" "${P}:escrow" "${P}:events" \
  , "$END" 50000 1 feed_top_slot 0 86400 100 0 10 10 5 >/dev/null
$D $R HSET "${P}:escrow" "20001:authorizedAmount" 100000 >/dev/null
$D $R --eval /tmp/promotion-auction-decision.lua \
  "${P}:state" "${P}:commands" "${P}:ranking" "${P}:campaign:20001" \
  "${P}:escrow" "${P}:events" "${P}:pub" "${P}:wakeup" \
  , cmd-9001-11 h-9001-11 101 50100 9001 20001 post-9 feed_top_slot 360 86400 2026-08-10T00:00:00Z 5 >/dev/null
# 模拟扫描器：closingIndex score 是旧 endAt（延长前），close 时 Redis TIME 未到新 endAt
OUT=$($D $R --eval /tmp/promotion-auction-close.lua \
  "${P}:state" "${P}:events" "${P}:pub" , 9001 86400)
check "U18 NOT_DUE（真实 TIME 未到期）" '"status":"NOT_DUE"' "$OUT"

echo ""
echo "===== T2 Lua 验证结果: PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" -eq 0 ]

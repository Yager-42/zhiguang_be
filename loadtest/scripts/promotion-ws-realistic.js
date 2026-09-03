// Stateful single-room auction workload with persistent native WebSocket connections.
// English realistic form: per-VU escrow budget distribution (prices keep climbing, accept path
// stays loaded), end-to-end public delta latency (accept -> RANKING_DELTA arrival), and a short
// window so the run tail drives close/settle under load.
// The request mix models raises, stale views, idempotent retries, double submits, and escrow failures.
import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { vu } from 'k6/execution';
import {
  BASE_URL,
  PASSWORD,
  USER_POOL,
} from './common.js';

const bidderVus = Number(__ENV.VUS || 500);
const campaignPoolSize = Number(__ENV.PROMOTION_CAMPAIGN_POOL_SIZE || bidderVus);
const campaignBase = Number(__ENV.PROMOTION_CAMPAIGN_BASE || 4200000);
const expectedWindowId = String(__ENV.PROMOTION_EXPECTED_WINDOW_ID || '8800001');
// PROMOTION_ESCROW_AMOUNT 显式设置时所有 VU 固定预算（兼容旧场景）；
// 否则每 VU 从 [ESCROW_MIN, ESCROW_MAX] 均匀取样——预算差异让共享价持续爬升、
// 接受/投影/广播路径被全程压力（全等预算下价格 10 秒收敛为纯拒绝形态）
const escrowAmount = __ENV.PROMOTION_ESCROW_AMOUNT !== undefined
  ? Number(__ENV.PROMOTION_ESCROW_AMOUNT) : null;
const escrowMin = Number(__ENV.PROMOTION_ESCROW_MIN || 500);
const escrowMax = Number(__ENV.PROMOTION_ESCROW_MAX || 5000);
function budgetFor(vuIndex) {
  return escrowAmount !== null ? escrowAmount : randomInt(escrowMin, escrowMax);
}
const steadySeconds = Number(__ENV.STEADY_SECONDS || 20);
const rampSeconds = Number(__ENV.RAMP_SECONDS || 20);
const peakSeconds = Number(__ENV.PEAK_SECONDS || 15);
const cooldownSeconds = Number(__ENV.COOLDOWN_SECONDS || 10);
const steadyRate = Number(__ENV.STEADY_RATE || 100);
const rampEndRate = Number(__ENV.RAMP_END_RATE || 400);
const peakRate = Number(__ENV.PEAK_RATE || 9000);
const cooldownRate = Number(__ENV.COOLDOWN_RATE || 200);
const ackDrainSeconds = Number(__ENV.ACK_DRAIN_SECONDS || 5);
const sendIntervalMs = Number(__ENV.SEND_INTERVAL_MS || 20);
const loginBatchSize = Number(__ENV.LOGIN_BATCH_SIZE || 20);
const maximumPending = Number(__ENV.MAX_TRACKED_PENDING || 10000);
const raisePercent = Number(__ENV.RAISE_PERCENT || 55);
const stalePercent = Number(__ENV.STALE_PERCENT || 25);
const retryPercent = Number(__ENV.RETRY_PERCENT || 10);
const doubleSubmitPercent = Number(__ENV.DOUBLE_SUBMIT_PERCENT || 5);
const totalDurationSeconds = steadySeconds + rampSeconds + peakSeconds + cooldownSeconds;
// 知情对抗：raise 出价 = 当前共享价 + randomInt(RAISE_MIN, RAISE_MAX)
// （英式 required=current+increment=100，区间必须覆盖增量才能推进价格）
const raiseMin = Number(__ENV.RAISE_MIN || 100);
const raiseMax = Number(__ENV.RAISE_MAX || 300);

const bidsSent = new Counter('promotion_realistic_bid_sent');
const bidsAcknowledged = new Counter('promotion_realistic_bid_acknowledged');
const bidsAccepted = new Counter('promotion_realistic_bid_accepted');
const bidsFastRejected = new Counter('promotion_realistic_bid_fast_rejected');
const bidsOtherRejected = new Counter('promotion_realistic_bid_other_rejected');
const bidsUnavailable = new Counter('promotion_realistic_bid_unavailable');
const raiseBids = new Counter('promotion_realistic_behavior_raise');
const staleBids = new Counter('promotion_realistic_behavior_stale');
const retryBids = new Counter('promotion_realistic_behavior_retry');
const doubleSubmitBids = new Counter('promotion_realistic_behavior_double_submit');
const insufficientEscrowBids = new Counter('promotion_realistic_behavior_insufficient_escrow');
const steadyBids = new Counter('promotion_realistic_phase_steady');
const rampBids = new Counter('promotion_realistic_phase_ramp');
const peakBids = new Counter('promotion_realistic_phase_peak');
const cooldownBids = new Counter('promotion_realistic_phase_cooldown');
const missingAcks = new Counter('promotion_realistic_missing_ack');
const pendingOverflows = new Counter('promotion_realistic_pending_overflow');
const connectionFailures = new Counter('promotion_ws_connection_failure');
const protocolErrors = new Rate('promotion_ws_protocol_error');
const ackDuration = new Trend('promotion_realistic_ack_duration', true);
const publicEvents = new Counter('promotion_realistic_public_events');
const deltaUpdates = new Counter('promotion_realistic_shared_price_updates');
// 端到端感知延迟：接受 -> 该出价对应的 RANKING_DELTA 到达本连接（竞价体验的核心指标）
const publicDeltaDuration = new Trend('promotion_realistic_public_delta_duration', true);
const settledEvents = new Counter('promotion_realistic_settled_events');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    bidders: {
      executor: 'per-vu-iterations',
      vus: bidderVus,
      iterations: 1,
      maxDuration: `${totalDurationSeconds + ackDrainSeconds + 20}s`,
    },
  },
  thresholds: {
    promotion_realistic_missing_ack: ['count<1'],
    promotion_realistic_pending_overflow: ['count<1'],
    promotion_ws_connection_failure: ['count<1'],
    promotion_ws_protocol_error: ['rate<0.01'],
    promotion_realistic_ack_duration: ['p(95)<200', 'p(99)<500'],
    promotion_realistic_public_delta_duration: ['p(95)<1000', 'p(99)<2000'],
  },
};

export function setup() {
  validateConfiguration();
  const tokens = loginUsers(bidderVus);
  const escrowBudgets = [];
  const auctionWindowId = authorizeEscrows(tokens, escrowBudgets);
  if (auctionWindowId !== expectedWindowId) {
    fail(`expected auction window ${expectedWindowId}, but escrow selected ${auctionWindowId}`);
  }
  return { tokens, runId: String(Date.now()), auctionWindowId, escrowBudgets };
}

export default function (data) {
  const token = data.tokens[(vu.idInTest - 1) % data.tokens.length];
  const campaignId = campaignBase + ((vu.idInTest - 1) % campaignPoolSize) + 1;
  const escrowBudget = data.escrowBudgets[(vu.idInTest - 1) % data.escrowBudgets.length];
  const wsUrl = BASE_URL.replace(/^http/, 'ws') + '/ws/promotion-auction-native';
  const pending = {};
  let pendingCount = 0;
  let sequence = 0;
  // 窗口共享当前价（英式升价）：只由公共事件（RANKING_DELTA / WINDOW_CLOSED）单调更新；
  // 0 表示尚未观察到任何价格（首出价用 reserve+increment 起步区间）
  let sharedPrice = 0;
  let previousBid = 0;
  let lastPayload = null;
  let startedAt = 0;
  let connectionFailureRecorded = false;
  let subscribed = false;
  // 本 VU 待确认出价的发送记录（campaignId -> [{sentAt, bidAmount}]），
  // 与 RANKING_DELTA 中自己 campaign 的 delta 关联测端到端广播延迟
  const pendingByCampaign = {};

  const response = ws.connect(wsUrl, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'promotion.ws.realistic' },
  }, (socket) => {
    socket.on('open', () => {
      startedAt = Date.now();
      // 知情对抗前提：订阅房间以接收 RANKING_DELTA/WINDOW_CLOSED 公共事件
      socket.send(JSON.stringify({ type: 'SUBSCRIBE', auctionWindowId: Number(expectedWindowId) }));
      socket.setInterval(() => {
        // 知情对抗前提：收到 SUBSCRIBED 确认（房间公共事件通道就绪）后才开始出价，
        // 否则早期 RANKING_DELTA 会因未订阅而丢失，共享价认知永久冻结
        if (!subscribed) {
          return;
        }
        const elapsedMs = Date.now() - startedAt;
        if (elapsedMs >= totalDurationSeconds * 1000) {
          return;
        }
        // 尾段停发（留给在途 ack 清空）：避免 socket.close 时在途出价丢 ack（missing_ack）
        if (elapsedMs >= (totalDurationSeconds - 5) * 1000) {
          return;
        }
        const currentRate = rateAt(elapsedMs / 1000);
        const sendProbability = (currentRate / bidderVus) * (sendIntervalMs / 1000);
        if (Math.random() >= sendProbability) {
          return;
        }

        const generated = nextPayload(data.runId, campaignId, sequence + 1,
          sharedPrice, previousBid, lastPayload, escrowBudget);
        sequence++;
        previousBid = generated.previousBid;
        lastPayload = generated.lastPayload;
        recordBehavior(generated.behavior);
        recordPhase(elapsedMs / 1000);

        const idempotencyKey = generated.payload.idempotencyKey;
        if (pendingCount >= maximumPending) {
          pendingOverflows.add(1);
          return;
        }
        if (pending[idempotencyKey] === undefined) {
          pending[idempotencyKey] = [];
        }
        pending[idempotencyKey].push(Date.now());
        pendingCount++;
        if (pendingByCampaign[campaignId] === undefined) {
          pendingByCampaign[campaignId] = [];
        }
        pendingByCampaign[campaignId].push({
          sentAt: Date.now(), bidAmount: generated.payload.bidAmount,
        });
        if (pendingByCampaign[campaignId].length > 100) {
          pendingByCampaign[campaignId].shift();
        }
        socket.send(JSON.stringify(generated.payload));
        bidsSent.add(1);
      }, sendIntervalMs);
    });

    socket.on('message', (raw) => {
      let parsed;
      try {
        parsed = JSON.parse(String(raw));
      } catch (error) {
        console.log('DIAG_PARSE_FAIL=' + String(raw).slice(0, 120));
        protocolErrors.add(true);
        return;
      }
      // 房间订阅确认（SUBSCRIBED）：公共事件通道就绪，解锁出价（ack 字段为 eventType）
      if (parsed.eventType === 'SUBSCRIBED' || parsed.status === 'SUBSCRIBED') {
        subscribed = true;
        return;
      }
      // 公共房间事件：知情对抗的价格源（RANKING_DELTA 增量 / WINDOW_CLOSED 终局排名）
      if (parsed.eventType !== undefined) {
        publicEvents.add(1);
        if (parsed.eventType === 'RANKING_DELTA' && Array.isArray(parsed.bidDeltas)) {
          for (const delta of parsed.bidDeltas) {
            const amount = Number(delta.bidAmount);
            if (Number.isFinite(amount) && amount > sharedPrice) {
              sharedPrice = amount;
              deltaUpdates.add(1);
            }
            // 端到端延迟：自己 campaign 的接受价出现在公共广播中 → 关联最早同额出价
            if (String(delta.campaignId) === String(campaignId)) {
              const mine = pendingByCampaign[campaignId];
              if (mine) {
                for (let index = 0; index < mine.length; index++) {
                  if (mine[index].bidAmount === amount) {
                    publicDeltaDuration.add(Date.now() - mine[index].sentAt);
                    mine.splice(index, 1);
                    break;
                  }
                }
              }
            }
          }
        } else if (parsed.eventType === 'WINDOW_CLOSED') {
          settledEvents.add(1);
          if (Array.isArray(parsed.ranking) && parsed.ranking.length > 0) {
            const amount = Number(parsed.ranking[0].bidAmount);
            if (Number.isFinite(amount) && amount > sharedPrice) {
              sharedPrice = amount;
              deltaUpdates.add(1);
            }
          }
        }
        return;
      }
      const ack = parsed;
      const queue = pending[ack.idempotencyKey];
      const finalStatus = ack.status === 'ACCEPTED' || ack.status === 'REJECTED';
      const valid = Array.isArray(queue) && queue.length > 0
        && (finalStatus || ack.status === 'UNAVAILABLE')
        && ack.resultAvailable === finalStatus;
      protocolErrors.add(!valid);
      if (!valid) {
        return;
      }
      const sentAt = queue.shift();
      if (queue.length === 0) {
        delete pending[ack.idempotencyKey];
      }
      pendingCount--;
      ackDuration.add(Date.now() - sentAt);
      bidsAcknowledged.add(1);
      if (ack.status === 'ACCEPTED') {
        bidsAccepted.add(1);
      } else if (ack.rejectionReason === 'BID_NOT_HIGHER') {
        bidsFastRejected.add(1);
      } else if (ack.status === 'UNAVAILABLE') {
        bidsUnavailable.add(1);
      } else {
        bidsOtherRejected.add(1, { reason: String(ack.rejectionReason || 'unknown') });
      }
    });
    socket.on('error', () => {
      if (!connectionFailureRecorded) {
        connectionFailures.add(1);
        connectionFailureRecorded = true;
      }
    });
    socket.setTimeout(() => socket.close(),
      (totalDurationSeconds + ackDrainSeconds) * 1000);
  });

  const upgraded = response && (response.status === 101 || response.status === 0);
  check(response, { 'promotion realistic WebSocket upgraded': () => upgraded });
  if (!upgraded && !connectionFailureRecorded) {
    connectionFailures.add(1);
  }
  if (pendingCount > 0) {
    missingAcks.add(pendingCount);
  }
}

function nextPayload(runId, campaignId, nextSequence, sharedPrice, previousBid, lastPayload, escrowBudget) {
  const choice = Math.random() * 100;
  const newKey = `real-${runId}-${vu.idInTest}-${nextSequence}`;
  const current = Math.max(sharedPrice, 0);
  if (choice < raisePercent) {
    // 知情对抗：出当前共享价 + 随机台阶；首出价（尚未观察到价格）用 reserve+increment 起步区间
    const basePrice = current === 0 ? randomInt(101, 300) : current;
    const raised = Math.min(escrowBudget, basePrice + randomInt(raiseMin, raiseMax));
    const payload = bidPayload(campaignId, raised, newKey);
    return { behavior: 'raise', payload, knownBid: raised, previousBid: current, lastPayload: payload };
  }
  if (choice < raisePercent + stalePercent) {
    // 陈旧视图：低于当前共享价，必拒（fast-reject 拦截形态）
    const stale = Math.max(1, current - randomInt(1, 5));
    const payload = bidPayload(campaignId, stale, newKey);
    return { behavior: 'stale', payload, knownBid: current, previousBid, lastPayload: payload };
  }
  if (choice < raisePercent + stalePercent + retryPercent && lastPayload !== null) {
    return { behavior: 'retry', payload: lastPayload, knownBid: current, previousBid, lastPayload };
  }
  if (choice < raisePercent + stalePercent + retryPercent + doubleSubmitPercent) {
    // 双提交同价：等于当前共享价，必拒
    const payload = bidPayload(campaignId, Math.max(1, current), newKey);
    return { behavior: 'double', payload, knownBid: current, previousBid, lastPayload: payload };
  }
  // 预算不足：授权额 +1；价格低于授权时仍可接受，高于时 ESCROW_INSUFFICIENT
  const payload = bidPayload(campaignId, escrowBudget + 1, newKey);
  return { behavior: 'insufficient', payload, knownBid: current, previousBid, lastPayload: payload };
}

function bidPayload(campaignId, amount, idempotencyKey) {
  return { campaignId: String(campaignId), bidAmount: amount, idempotencyKey };
}

function rateAt(elapsedSeconds) {
  if (elapsedSeconds < steadySeconds) {
    return steadyRate;
  }
  if (elapsedSeconds < steadySeconds + rampSeconds) {
    const progress = (elapsedSeconds - steadySeconds) / rampSeconds;
    return steadyRate + (rampEndRate - steadyRate) * progress;
  }
  if (elapsedSeconds < steadySeconds + rampSeconds + peakSeconds) {
    return peakRate;
  }
  return cooldownRate;
}

function recordBehavior(behavior) {
  if (behavior === 'raise') raiseBids.add(1);
  else if (behavior === 'stale') staleBids.add(1);
  else if (behavior === 'retry') retryBids.add(1);
  else if (behavior === 'double') doubleSubmitBids.add(1);
  else insufficientEscrowBids.add(1);
}

function recordPhase(elapsedSeconds) {
  if (elapsedSeconds < steadySeconds) steadyBids.add(1);
  else if (elapsedSeconds < steadySeconds + rampSeconds) rampBids.add(1);
  else if (elapsedSeconds < steadySeconds + rampSeconds + peakSeconds) peakBids.add(1);
  else cooldownBids.add(1);
}

function validateConfiguration() {
  if (bidderVus > campaignPoolSize || bidderVus > USER_POOL) {
    fail(`VUS=${bidderVus} requires at least the same campaign and user pool size`);
  }
  if (raisePercent + stalePercent + retryPercent + doubleSubmitPercent > 100) {
    fail('behavior percentages must not exceed 100');
  }
  const maximumProbability = (Math.max(steadyRate, rampEndRate, peakRate, cooldownRate) / bidderVus)
    * (sendIntervalMs / 1000);
  if (maximumProbability > 1) {
    fail(`SEND_INTERVAL_MS is too large for peak rate; probability=${maximumProbability}`);
  }
}

function loginUsers(count) {
  const tokens = [];
  for (let offset = 1; offset <= count; offset += loginBatchSize) {
    const requests = [];
    const batchEnd = Math.min(offset + loginBatchSize, count + 1);
    for (let userNumber = offset; userNumber < batchEnd; userNumber++) {
      requests.push([
        'POST',
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({
          identifierType: 'PHONE',
          identifier: `139${String(userNumber).padStart(8, '0')}`,
          password: PASSWORD,
        }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup.login' } },
      ]);
    }
    for (const response of http.batch(requests)) {
      if (response.status !== 200) {
        fail(`setup login failed: status=${response.status} body=${response.body.slice(0, 200)}`);
      }
      tokens.push(response.json().token.accessToken);
    }
  }
  return tokens;
}

function authorizeEscrows(tokens, escrowBudgets) {
  let selectedWindowId = null;
  for (let offset = 0; offset < tokens.length; offset += loginBatchSize) {
    const requests = [];
    const batchEnd = Math.min(offset + loginBatchSize, tokens.length);
    for (let tokenIndex = offset; tokenIndex < batchEnd; tokenIndex++) {
      const amount = budgetFor(tokenIndex);
      escrowBudgets.push(amount);
      requests.push([
        'POST',
        `${BASE_URL}/api/v1/promotions/campaigns/${campaignBase + tokenIndex + 1}/escrow`,
        JSON.stringify({ amount }),
        {
          headers: { Authorization: `Bearer ${tokens[tokenIndex]}`, 'Content-Type': 'application/json' },
          tags: { name: 'setup.promotion.escrow' },
        },
      ]);
    }
    for (const response of http.batch(requests)) {
      if (response.status !== 200) {
        fail(`setup escrow failed: status=${response.status} body=${response.body.slice(0, 200)}`);
      }
      const responseWindowId = String(response.json().auctionWindowId);
      if (selectedWindowId !== null && selectedWindowId !== responseWindowId) {
        fail(`escrow setup selected multiple windows: ${selectedWindowId}, ${responseWindowId}`);
      }
      selectedWindowId = responseWindowId;
    }
  }
  return selectedWindowId;
}

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

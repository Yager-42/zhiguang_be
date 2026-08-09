// Stateful single-room auction workload with persistent native WebSocket connections.
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
const escrowAmount = Number(__ENV.PROMOTION_ESCROW_AMOUNT || 1000);
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

const bidsSent = new Counter('promotion_realistic_bid_sent');
const bidsAcknowledged = new Counter('promotion_realistic_bid_acknowledged');
const bidsPublished = new Counter('promotion_realistic_bid_published');
const bidsFastRejected = new Counter('promotion_realistic_bid_fast_rejected');
const bidsOtherRejected = new Counter('promotion_realistic_bid_other_rejected');
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
  },
};

export function setup() {
  validateConfiguration();
  const tokens = loginUsers(bidderVus);
  const auctionWindowId = authorizeEscrows(tokens);
  if (auctionWindowId !== expectedWindowId) {
    fail(`expected auction window ${expectedWindowId}, but escrow selected ${auctionWindowId}`);
  }
  return { tokens, runId: String(Date.now()), auctionWindowId };
}

export default function (data) {
  const token = data.tokens[(vu.idInTest - 1) % data.tokens.length];
  const campaignId = campaignBase + ((vu.idInTest - 1) % campaignPoolSize) + 1;
  const wsUrl = BASE_URL.replace(/^http/, 'ws') + '/ws/promotion-auction-native';
  const pending = {};
  let pendingCount = 0;
  let sequence = 0;
  let knownBid = 0;
  let previousBid = 0;
  let lastPayload = null;
  let startedAt = 0;
  let connectionFailureRecorded = false;

  const response = ws.connect(wsUrl, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'promotion.ws.realistic' },
  }, (socket) => {
    socket.on('open', () => {
      startedAt = Date.now();
      socket.setInterval(() => {
        const elapsedMs = Date.now() - startedAt;
        if (elapsedMs >= totalDurationSeconds * 1000) {
          return;
        }
        const currentRate = rateAt(elapsedMs / 1000);
        const sendProbability = (currentRate / bidderVus) * (sendIntervalMs / 1000);
        if (Math.random() >= sendProbability) {
          return;
        }

        const generated = nextPayload(data.runId, campaignId, sequence + 1,
          knownBid, previousBid, lastPayload);
        sequence++;
        knownBid = generated.knownBid;
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
        socket.send(JSON.stringify(generated.payload));
        bidsSent.add(1);
      }, sendIntervalMs);
    });

    socket.on('message', (raw) => {
      let ack;
      try {
        ack = JSON.parse(String(raw));
      } catch (error) {
        protocolErrors.add(true);
        return;
      }
      const queue = pending[ack.idempotencyKey];
      const valid = Array.isArray(queue) && queue.length > 0
        && (ack.status === 'PUBLISHED' || ack.status === 'REJECTED');
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
      if (ack.status === 'PUBLISHED') {
        bidsPublished.add(1);
      } else if (ack.rejectionReason === 'BID_NOT_HIGHER') {
        bidsFastRejected.add(1);
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

function nextPayload(runId, campaignId, nextSequence, knownBid, previousBid, lastPayload) {
  const choice = Math.random() * 100;
  const newKey = `real-${runId}-${vu.idInTest}-${nextSequence}`;
  if (choice < raisePercent) {
    const raised = Math.min(escrowAmount, Math.max(knownBid, 0) + randomInt(1, 5));
    const payload = bidPayload(campaignId, raised, newKey);
    return { behavior: 'raise', payload, knownBid: raised, previousBid: knownBid, lastPayload: payload };
  }
  if (choice < raisePercent + stalePercent) {
    const stale = Math.max(1, previousBid > 0 ? previousBid : knownBid - randomInt(1, 5));
    const payload = bidPayload(campaignId, stale, newKey);
    return { behavior: 'stale', payload, knownBid, previousBid, lastPayload: payload };
  }
  if (choice < raisePercent + stalePercent + retryPercent && lastPayload !== null) {
    return { behavior: 'retry', payload: lastPayload, knownBid, previousBid, lastPayload };
  }
  if (choice < raisePercent + stalePercent + retryPercent + doubleSubmitPercent) {
    const payload = bidPayload(campaignId, Math.max(1, knownBid), newKey);
    return { behavior: 'double', payload, knownBid, previousBid, lastPayload: payload };
  }
  const payload = bidPayload(campaignId, escrowAmount + 1, newKey);
  return { behavior: 'insufficient', payload, knownBid, previousBid, lastPayload: payload };
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

function authorizeEscrows(tokens) {
  let selectedWindowId = null;
  for (let offset = 0; offset < tokens.length; offset += loginBatchSize) {
    const requests = [];
    const batchEnd = Math.min(offset + loginBatchSize, tokens.length);
    for (let tokenIndex = offset; tokenIndex < batchEnd; tokenIndex++) {
      requests.push([
        'POST',
        `${BASE_URL}/api/v1/promotions/campaigns/${campaignBase + tokenIndex + 1}/escrow`,
        JSON.stringify({ amount: escrowAmount }),
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

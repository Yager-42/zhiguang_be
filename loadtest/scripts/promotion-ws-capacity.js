// Closed-loop successor to the removed REST promotion-bid-capacity scenario.
// It preserves the old VU phases and bid distribution while using the native WebSocket endpoint.
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

const measureVus = Number(__ENV.VUS || 100);
const warmupVus = Number(__ENV.WARMUP_VUS || Math.min(40, measureVus));
const warmupSeconds = Number(__ENV.WARMUP_SECONDS || 15);
const settleSeconds = Number(__ENV.SETTLE_SECONDS || 15);
const measureSeconds = Number(__ENV.MEASURE_SECONDS || 30);
const ackGraceSeconds = Number(__ENV.ACK_GRACE_SECONDS || 2);
const campaignPoolSize = Number(__ENV.PROMOTION_CAMPAIGN_POOL_SIZE || 100);
const feedCampaignBase = Number(__ENV.PROMOTION_FEED_CAMPAIGN_BASE || 3300000);
const bidAmount = Number(__ENV.PROMOTION_BID_AMOUNT || 1);
const escrowAmount = Number(__ENV.PROMOTION_ESCROW_AMOUNT || 100000);
const loginBatchSize = Number(__ENV.LOGIN_BATCH_SIZE || 20);

const measuredRequests = new Counter('promotion_bid_measured_requests');
const measuredFailures = new Rate('promotion_bid_measured_failures');
const measuredDuration = new Trend('promotion_bid_measured_duration', true);
const measuredAccepted = new Counter('promotion_bid_measured_accepted');
const measuredFastRejected = new Counter('promotion_bid_measured_fast_rejected');
const measuredUnavailable = new Counter('promotion_bid_measured_unavailable');
const missingAcks = new Counter('promotion_bid_measured_missing_ack');
const connectionFailures = new Counter('promotion_ws_connection_failure');
const protocolErrors = new Rate('promotion_ws_protocol_error');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: {
      executor: 'per-vu-iterations',
      exec: 'warmup',
      vus: warmupVus,
      iterations: 1,
      maxDuration: `${warmupSeconds + ackGraceSeconds + 5}s`,
      tags: { phase: 'warmup' },
    },
    measure: {
      executor: 'per-vu-iterations',
      exec: 'measure',
      vus: measureVus,
      iterations: 1,
      startTime: `${warmupSeconds + settleSeconds}s`,
      maxDuration: `${measureSeconds + ackGraceSeconds + 5}s`,
      tags: { phase: 'measure' },
    },
  },
  thresholds: {
    promotion_bid_measured_failures: ['rate<0.01'],
    promotion_bid_measured_duration: ['p(95)<800'],
    promotion_bid_measured_missing_ack: ['count<1'],
    promotion_ws_connection_failure: ['count<1'],
    promotion_ws_protocol_error: ['rate<0.01'],
  },
};

export function setup() {
  if (measureVus > campaignPoolSize || measureVus > USER_POOL) {
    fail(`VUS=${measureVus} requires at least the same campaign and user pool size`);
  }
  const tokens = loginUsers(measureVus);
  authorizeEscrows(tokens);
  return { tokens, runId: String(Date.now()) };
}

export function warmup(data) {
  runClosedLoop(data, warmupSeconds, false);
}

export function measure(data) {
  runClosedLoop(data, measureSeconds, true);
}

function runClosedLoop(data, durationSeconds, measured) {
  const token = data.tokens[(vu.idInTest - 1) % data.tokens.length];
  const campaignId = feedCampaignBase + ((vu.idInTest - 1) % campaignPoolSize) + 1;
  const wsUrl = BASE_URL.replace(/^http/, 'ws') + '/ws/promotion-auction-native';
  const stopAt = Date.now() + durationSeconds * 1000;
  let sequence = 0;
  let pendingKey = null;
  let sentAt = 0;
  let missingRecorded = false;
  let connectionFailureRecorded = false;

  const response = ws.connect(wsUrl, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'promotion.ws.capacity', phase: measured ? 'measure' : 'warmup' },
  }, (socket) => {
    function sendNext() {
      if (Date.now() >= stopAt) {
        socket.close();
        return;
      }
      sequence++;
      pendingKey = `ws-capacity-${data.runId}-${measured ? 'measure' : 'warmup'}-${vu.idInTest}-${sequence}`;
      sentAt = Date.now();
      socket.send(JSON.stringify({
        campaignId: String(campaignId),
        bidAmount,
        idempotencyKey: pendingKey,
      }));
    }

    function recordMissingAck() {
      if (measured && pendingKey !== null && !missingRecorded) {
        missingAcks.add(1);
        measuredFailures.add(true);
        missingRecorded = true;
      }
    }

    socket.on('open', sendNext);
    socket.on('message', (raw) => {
      let ack;
      try {
        ack = JSON.parse(String(raw));
      } catch (error) {
        if (measured) {
          protocolErrors.add(true);
          measuredFailures.add(true);
        }
        return;
      }

      const finalStatus = ack.status === 'ACCEPTED' || ack.status === 'REJECTED';
      const valid = ack.idempotencyKey === pendingKey
        && (finalStatus || ack.status === 'UNAVAILABLE')
        && ack.resultAvailable === finalStatus;
      if (measured) {
        protocolErrors.add(!valid);
        measuredFailures.add(!valid);
      }
      if (!valid) {
        return;
      }

      if (measured) {
        measuredRequests.add(1);
        measuredDuration.add(Date.now() - sentAt);
        if (ack.status === 'ACCEPTED') {
          measuredAccepted.add(1);
        } else if (ack.rejectionReason === 'BID_NOT_HIGHER') {
          measuredFastRejected.add(1);
        } else if (ack.status === 'UNAVAILABLE') {
          measuredUnavailable.add(1);
        }
      }
      pendingKey = null;
      sendNext();
    });
    socket.on('error', () => {
      if (!connectionFailureRecorded) {
        connectionFailures.add(1);
        connectionFailureRecorded = true;
      }
    });
    socket.setTimeout(() => {
      recordMissingAck();
      socket.close();
    }, (durationSeconds + ackGraceSeconds) * 1000);
  });

  const upgraded = response && (response.status === 101 || response.status === 0);
  check(response, { 'promotion capacity WebSocket upgraded': () => upgraded });
  if (!upgraded && !connectionFailureRecorded) {
    connectionFailures.add(1);
  }
  if (measured && pendingKey !== null && !missingRecorded) {
    missingAcks.add(1);
    measuredFailures.add(true);
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
  for (let offset = 0; offset < tokens.length; offset += loginBatchSize) {
    const requests = [];
    const batchEnd = Math.min(offset + loginBatchSize, tokens.length);
    for (let tokenIndex = offset; tokenIndex < batchEnd; tokenIndex++) {
      requests.push([
        'POST',
        `${BASE_URL}/api/v1/promotions/campaigns/${feedCampaignBase + tokenIndex + 1}/escrow`,
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
    }
  }
}

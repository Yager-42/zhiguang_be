// Eliaaazzz-style high-activity workload: persistent WebSocket bidders repeatedly submit a bid
// that the gateway's monotonic watermark can prove will lose. Accepted bids still use the
// RocketMQ -> Redis Lua -> Kafka reliable path; this script measures the safe fast-reject lane.
import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { vu } from 'k6/execution';
import {
  BASE_URL,
  PASSWORD,
  USER_POOL,
} from './common.js';

const bidderVus = Number(__ENV.VUS || 100);
const targetRate = Number(__ENV.RATE || 20000);
const durationSeconds = Number(__ENV.DURATION_SECONDS || 30);
const ackDrainSeconds = Number(__ENV.ACK_DRAIN_SECONDS || 5);
const sendIntervalMs = Number(__ENV.SEND_INTERVAL_MS || 10);
const transport = String(__ENV.PROMOTION_WS_TRANSPORT || 'native').toLowerCase();
const campaignPoolSize = Number(__ENV.PROMOTION_CAMPAIGN_POOL_SIZE || bidderVus);
const campaignBase = Number(__ENV.PROMOTION_FEED_CAMPAIGN_BASE || 3900000);
const escrowAmount = Number(__ENV.PROMOTION_ESCROW_AMOUNT || 100000);
const bidAmount = Number(__ENV.PROMOTION_FAST_REJECT_BID_AMOUNT || 1);
const primeWaitSeconds = Number(__ENV.PROMOTION_PRIME_WAIT_SECONDS || 10);
const loginBatchSize = Number(__ENV.LOGIN_BATCH_SIZE || 20);
const maxTrackedPending = Number(__ENV.MAX_TRACKED_PENDING || 10000);

const bidsSent = new Counter('promotion_ws_bid_sent');
const bidsAcknowledged = new Counter('promotion_ws_bid_acknowledged');
const bidsFastRejected = new Counter('promotion_ws_bid_fast_rejected');
const bidsPublished = new Counter('promotion_ws_bid_published');
const missingAcks = new Counter('promotion_ws_bid_missing_ack');
const connectionFailures = new Counter('promotion_ws_connection_failure');
const protocolErrors = new Rate('promotion_ws_protocol_error');
const ackDuration = new Trend('promotion_ws_bid_ack_duration', true);

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    bidders: {
      executor: 'per-vu-iterations',
      vus: bidderVus,
      iterations: 1,
      maxDuration: `${durationSeconds + 30}s`,
    },
  },
  thresholds: {
    promotion_ws_connection_failure: ['count<1'],
    promotion_ws_protocol_error: ['rate<0.01'],
    promotion_ws_bid_ack_duration: ['p(95)<100', 'p(99)<300'],
  },
};

export function setup() {
  if (transport !== 'native' && transport !== 'stomp') {
    fail(`PROMOTION_WS_TRANSPORT must be native or stomp, got ${transport}`);
  }
  if (bidderVus > campaignPoolSize || bidderVus > USER_POOL) {
    fail(`VUS=${bidderVus} requires at least the same campaign and user pool size`);
  }
  const tokens = loginUsers(bidderVus);
  authorizeEscrows(tokens);
  const setupRunId = String(Date.now());
  primeAuthoritativeWatermarks(tokens, setupRunId);
  sleep(primeWaitSeconds);
  assertFastRejectReady(tokens, setupRunId);
  return { tokens, runId: String(Date.now()) };
}

export default function (data) {
  const token = data.tokens[(vu.idInTest - 1) % data.tokens.length];
  const campaignId = campaignBase + ((vu.idInTest - 1) % campaignPoolSize) + 1;
  const endpoint = transport === 'native'
    ? '/ws/promotion-auction-native'
    : '/ws/promotion-auction';
  const wsUrl = BASE_URL.replace(/^http/, 'ws') + endpoint;
  const pending = {};
  let pendingCount = 0;
  let sequence = 0;
  let sendCarry = 0;
  let senderStarted = false;
  let sendStartedAt = 0;

  function startSender(socket) {
    if (senderStarted) return;
    senderStarted = true;
    sendStartedAt = Date.now();
    socket.setInterval(() => {
      if (Date.now() - sendStartedAt >= durationSeconds * 1000) return;
      sendCarry += (targetRate / bidderVus) * (sendIntervalMs / 1000);
      const batchSize = Math.floor(sendCarry);
      sendCarry -= batchSize;
      for (let index = 0; index < batchSize; index++) {
        sequence++;
        const idempotencyKey = `ws-${data.runId}-${vu.idInTest}-${sequence}`;
        const payload = JSON.stringify({
          campaignId: String(campaignId),
          bidAmount,
          idempotencyKey,
        });
        if (pendingCount < maxTrackedPending) {
          pending[idempotencyKey] = Date.now();
          pendingCount++;
        }
        if (transport === 'native') {
          socket.send(payload);
        } else {
          socket.send(stompFrame('SEND', {
            destination: '/app/promotion-auctions/bids',
            'content-type': 'application/json',
            'content-length': String(payload.length),
          }, payload));
        }
        bidsSent.add(1);
      }
    }, sendIntervalMs);
  }

  const response = ws.connect(wsUrl, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'promotion.ws.fast-reject' },
  }, (socket) => {
    socket.on('open', () => {
      if (transport === 'native') {
        startSender(socket);
      } else {
        socket.send(stompFrame('CONNECT', {
          'accept-version': '1.2',
          host: 'localhost',
          'heart-beat': '0,0',
        }));
      }
    });

    socket.on('message', (raw) => {
      if (transport === 'native') {
        recordAck(String(raw), pending, () => pendingCount--);
        return;
      }
      for (const frame of parseStompFrames(raw)) {
        if (frame.command === 'CONNECTED' && !senderStarted) {
          socket.send(stompFrame('SUBSCRIBE', {
            id: `promotion-bid-acks-${vu.idInTest}`,
            destination: '/user/queue/promotion-auction-bid-acks',
            ack: 'auto',
          }));
          startSender(socket);
        } else if (frame.command === 'MESSAGE') {
          recordAck(frame.body, pending, () => pendingCount--);
        } else if (frame.command === 'ERROR') {
          protocolErrors.add(true);
        }
      }
    });

    socket.on('error', () => {
      connectionFailures.add(1);
    });
    socket.setTimeout(() => socket.close(),
      Math.max(1000, (durationSeconds + ackDrainSeconds) * 1000));
  });

  if (!response || (response.status !== 101 && response.status !== 0)) {
    connectionFailures.add(1);
  }
  if (pendingCount > 0) {
    missingAcks.add(pendingCount);
  }
}

function recordAck(body, pending, onTrackedAck) {
  let ack;
  try {
    ack = JSON.parse(body);
  } catch (error) {
    protocolErrors.add(true);
    return;
  }
  protocolErrors.add(false);
  bidsAcknowledged.add(1);
  if (ack.status === 'REJECTED' && ack.rejectionReason === 'BID_NOT_HIGHER') {
    bidsFastRejected.add(1);
  } else if (ack.status === 'PUBLISHED') {
    bidsPublished.add(1);
  }
  const sentAt = pending[ack.idempotencyKey];
  if (sentAt !== undefined) {
    ackDuration.add(Date.now() - sentAt);
    delete pending[ack.idempotencyKey];
    onTrackedAck();
  }
}

function stompFrame(command, headers, body = '') {
  const lines = [command];
  for (const [name, value] of Object.entries(headers)) {
    lines.push(`${name}:${value}`);
  }
  lines.push('', body);
  return `${lines.join('\n')}\u0000`;
}

function parseStompFrames(raw) {
  const frames = [];
  for (const value of String(raw).split('\u0000')) {
    const text = value.replace(/^\n+/, '');
    if (!text) continue;
    const separator = text.indexOf('\n\n');
    const headerBlock = separator >= 0 ? text.slice(0, separator) : text;
    const headerLines = headerBlock.split('\n');
    frames.push({
      command: headerLines[0].replace(/\r$/, ''),
      body: separator >= 0 ? text.slice(separator + 2) : '',
    });
  }
  return frames;
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
  forEachTokenBatch(tokens, (token, tokenIndex) => [
    'POST',
    `${BASE_URL}/api/v1/promotions/campaigns/${campaignBase + tokenIndex + 1}/escrow`,
    JSON.stringify({ amount: escrowAmount }),
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      tags: { name: 'setup.promotion.escrow' },
    },
  ], 'escrow');
}

function primeAuthoritativeWatermarks(tokens, setupRunId) {
  for (let tokenIndex = 0; tokenIndex < tokens.length; tokenIndex++) {
    const ack = submitNativeSetupBid(tokens[tokenIndex], campaignBase + tokenIndex + 1,
      `prime-${setupRunId}-${tokenIndex}`, 'prime');
    const primed = ack.status === 'PUBLISHED'
      || (ack.status === 'REJECTED' && ack.rejectionReason === 'BID_NOT_HIGHER');
    if (!primed) {
      fail(`setup prime failed for campaign index=${tokenIndex}: ack=${JSON.stringify(ack)}`);
    }
  }
}

function assertFastRejectReady(tokens, setupRunId) {
  for (let tokenIndex = 0; tokenIndex < tokens.length; tokenIndex++) {
    const ack = submitNativeSetupBid(tokens[tokenIndex], campaignBase + tokenIndex + 1,
      `probe-${setupRunId}-${tokenIndex}`, 'fast-reject-probe');
    const ready = ack.status === 'REJECTED' && ack.rejectionReason === 'BID_NOT_HIGHER';
    if (!ready) {
      fail(`fast-reject cache not ready for campaign index=${tokenIndex}: ack=${JSON.stringify(ack)}; `
        + 'increase PROMOTION_FAST_REJECT_BID_AMOUNT or refresh the promotion seed');
    }
  }
}

function submitNativeSetupBid(token, campaignId, idempotencyKey, operation) {
  const wsUrl = BASE_URL.replace(/^http/, 'ws') + '/ws/promotion-auction-native';
  let ack = null;
  let socketError = null;
  const response = ws.connect(wsUrl, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: `setup.promotion.${operation}` },
  }, (socket) => {
    socket.on('open', () => socket.send(JSON.stringify({ campaignId: String(campaignId), bidAmount, idempotencyKey })));
    socket.on('message', (raw) => {
      try {
        ack = JSON.parse(String(raw));
      } catch (error) {
        socketError = `invalid ACK: ${String(error)}`;
      }
      socket.close();
    });
    socket.on('error', (error) => {
      socketError = String(error);
    });
    socket.setTimeout(() => socket.close(), 5000);
  });
  if (!response || (response.status !== 101 && response.status !== 0) || socketError || ack === null) {
    fail(`setup ${operation} WebSocket failed: status=${response ? response.status : 'none'} `
      + `error=${socketError || 'missing ACK'}`);
  }
  return ack;
}

function forEachTokenBatch(tokens, requestFactory, operation) {
  for (let offset = 0; offset < tokens.length; offset += loginBatchSize) {
    const requests = [];
    const batchEnd = Math.min(offset + loginBatchSize, tokens.length);
    for (let tokenIndex = offset; tokenIndex < batchEnd; tokenIndex++) {
      requests.push(requestFactory(tokens[tokenIndex], tokenIndex));
    }
    for (const response of http.batch(requests)) {
      if (response.status !== 200) {
        fail(`setup ${operation} failed: status=${response.status} body=${response.body.slice(0, 200)}`);
      }
    }
  }
}

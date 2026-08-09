// User-driven auction workload: observe a room price, react, bid above that observed price,
// and wait for the final private outcome before considering another bid.
import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail } from 'k6';
import { vu } from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  PASSWORD,
  USER_ID_BASE,
  USER_POOL,
} from './common.js';

const bidderVus = Number(__ENV.VUS || 500);
const campaignPoolSize = Number(__ENV.PROMOTION_CAMPAIGN_POOL_SIZE || bidderVus);
const campaignBase = Number(__ENV.PROMOTION_CAMPAIGN_BASE || 4600000);
const expectedWindowId = String(__ENV.PROMOTION_EXPECTED_WINDOW_ID || '8800004');
const escrowAmount = Number(__ENV.PROMOTION_ESCROW_AMOUNT || 100000);
const warmupSeconds = Number(__ENV.WARMUP_SECONDS || 10);
const measureSeconds = Number(__ENV.MEASURE_SECONDS || 30);
const outcomeDrainSeconds = Number(__ENV.OUTCOME_DRAIN_SECONDS || 30);
const subscriptionReadyMs = Number(__ENV.SUBSCRIPTION_READY_MS || 1000);
const reactionMinMs = Number(__ENV.REACTION_MIN_MS || 80);
const reactionMaxMs = Number(__ENV.REACTION_MAX_MS || 240);
const reactionJitterPercent = Number(__ENV.REACTION_JITTER_PERCENT || 20);
const incrementMin = Number(__ENV.BID_INCREMENT_MIN || 1);
const incrementMax = Number(__ENV.BID_INCREMENT_MAX || 5);
const activeBidderPercent = Number(__ENV.ACTIVE_BIDDER_PERCENT || 100);
const valuationMinPercent = Number(__ENV.VALUATION_MIN_PERCENT || 80);
const valuationMaxPercent = Number(__ENV.VALUATION_MAX_PERCENT || 100);
const loginBatchSize = Number(__ENV.LOGIN_BATCH_SIZE || 20);
const totalActiveSeconds = warmupSeconds + measureSeconds;

const measuredAttempts = new Counter('promotion_user_bid_attempts');
const measuredPublishedAcks = new Counter('promotion_user_published_acks');
const measuredFastRejects = new Counter('promotion_user_fast_reject_feedbacks');
const measuredFeedbacks = new Counter('promotion_user_final_feedbacks');
const feedbacksInWindow = new Counter('promotion_user_feedbacks_in_measurement_window');
const confirmedFeedbacks = new Counter('promotion_user_confirmed_feedbacks');
const rejectedFeedbacks = new Counter('promotion_user_rejected_feedbacks');
const outcomeTimeouts = new Counter('promotion_user_outcome_timeouts');
const inactiveBidders = new Counter('promotion_user_inactive_bidders');
const valuationDropouts = new Counter('promotion_user_valuation_dropouts');
const connectionFailures = new Counter('promotion_ws_connection_failure');
const protocolErrors = new Rate('promotion_ws_protocol_error');
const ingressAckDuration = new Trend('promotion_user_ingress_ack_duration', true);
const finalFeedbackDuration = new Trend('promotion_user_final_feedback_duration', true);
const priceDeliveryDuration = new Trend('promotion_user_price_delivery_duration', true);

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    bidders: {
      executor: 'per-vu-iterations',
      vus: bidderVus,
      iterations: 1,
      maxDuration: `${totalActiveSeconds + outcomeDrainSeconds + 20}s`,
    },
  },
  thresholds: {
    promotion_user_outcome_timeouts: ['count<1'],
    promotion_ws_connection_failure: ['count<1'],
    promotion_ws_protocol_error: ['rate<0.01'],
    promotion_user_final_feedback_duration: ['p(95)<5000', 'p(99)<10000'],
  },
};

export function setup() {
  validateConfiguration();
  const tokens = loginUsers(bidderVus);
  const auctionWindowId = authorizeEscrows(tokens);
  if (auctionWindowId !== expectedWindowId) {
    fail(`expected auction window ${expectedWindowId}, but escrow selected ${auctionWindowId}`);
  }
  const snapshot = loadSnapshot(tokens[0], auctionWindowId);
  return {
    tokens,
    auctionWindowId,
    runId: String(Date.now()),
    initialPrice: highestBid(snapshot.ranking),
    initialDecisionVersion: Number(snapshot.decisionVersion || 0),
  };
}

export default function (setupData) {
  const bidderIndex = (vu.idInTest - 1) % setupData.tokens.length;
  const token = setupData.tokens[bidderIndex];
  const userId = USER_ID_BASE + bidderIndex + 1;
  const campaignId = campaignBase + (bidderIndex % campaignPoolSize) + 1;
  const profile = bidderProfile(bidderIndex);
  if (!profile.active) {
    inactiveBidders.add(1);
  }

  const wsUrl = BASE_URL.replace(/^http/, 'ws') + '/ws/promotion-auction';
  const ackSubscription = `bid-ack-${vu.idInTest}`;
  const outcomeSubscription = `bid-outcome-${vu.idInTest}`;
  const roomSubscription = `room-${vu.idInTest}`;
  let visiblePrice = setupData.initialPrice;
  let visibleVersion = setupData.initialDecisionVersion;
  let pending = null;
  let scheduled = false;
  let stopped = false;
  let intentionalClose = false;
  let connectionFailureRecorded = false;
  let outcomeTimeoutRecorded = false;
  let sequence = 0;
  let startedAt = 0;
  let measureStartedAt = 0;
  let measureStoppedAt = 0;
  let completedCommandIds = {};
  let completedCommandOrder = [];
  let subscriptionsStarted = false;
  let subscriptionReceipts = {};

  const response = ws.connect(wsUrl, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'promotion.ws.user-feedback' },
  }, (socket) => {
    function startAfterSubscriptions() {
      if (subscriptionsStarted) {
        return;
      }
      subscriptionsStarted = true;
      startedAt = Date.now();
      measureStartedAt = startedAt + warmupSeconds * 1000;
      measureStoppedAt = measureStartedAt + measureSeconds * 1000;
      scheduleNextBid();
      socket.setTimeout(() => {
        stopped = true;
        closeWhenFinished(socket);
      }, totalActiveSeconds * 1000);
    }

    function scheduleNextBid() {
      if (stopped || scheduled || pending !== null || !profile.active) {
        closeWhenFinished(socket);
        return;
      }
      const priceSeen = visiblePrice;
      const versionSeen = visibleVersion;
      const reactionMs = jitteredReactionMs(profile.reactionMs);
      scheduled = true;
      socket.setTimeout(() => {
        scheduled = false;
        if (stopped || pending !== null) {
          closeWhenFinished(socket);
          return;
        }
        sendBid(socket, priceSeen, versionSeen);
      }, reactionMs);
    }

    function sendBid(socket, priceSeen, versionSeen) {
      const bidAmount = priceSeen + randomInt(incrementMin, incrementMax);
      if (bidAmount > profile.maximumBid) {
        valuationDropouts.add(1);
        stopped = true;
        closeWhenFinished(socket);
        return;
      }
      sequence++;
      const now = Date.now();
      const idempotencyKey = `user-${setupData.runId}-${vu.idInTest}-${sequence}`;
      const measured = now >= measureStartedAt && now < measureStoppedAt;
      pending = {
        bidAmount,
        commandId: null,
        idempotencyKey,
        measured,
        priceSeen,
        sentAt: now,
        versionSeen,
      };
      socket.send(stompFrame('SEND', {
        destination: '/app/promotion-auctions/bids',
        'content-type': 'application/json',
      }, JSON.stringify({ campaignId: String(campaignId), bidAmount, idempotencyKey })));
      if (measured) {
        measuredAttempts.add(1);
      }
    }

    function completeFeedback(socket, status, rejectionReason, commandId) {
      if (pending === null) {
        if (commandId && completedCommandIds[commandId]) {
          return;
        }
        protocolErrors.add(true);
        return;
      }
      if (pending.commandId !== null && commandId && pending.commandId !== commandId) {
        if (completedCommandIds[commandId]) {
          return;
        }
        protocolErrors.add(true);
        return;
      }
      const completedAt = Date.now();
      if (pending.measured) {
        measuredFeedbacks.add(1);
        finalFeedbackDuration.add(completedAt - pending.sentAt);
        if (status === 'CONFIRMED') {
          confirmedFeedbacks.add(1);
        } else {
          rejectedFeedbacks.add(1, { reason: String(rejectionReason || 'unknown') });
        }
      }
      if (completedAt >= measureStartedAt && completedAt < measureStoppedAt) {
        feedbacksInWindow.add(1);
      }
      rememberCompleted(commandId || pending.commandId);
      pending = null;
      scheduleNextBid();
    }

    function rememberCompleted(commandId) {
      if (!commandId) {
        return;
      }
      completedCommandIds[commandId] = true;
      completedCommandOrder.push(commandId);
      if (completedCommandOrder.length > 32) {
        const expired = completedCommandOrder.shift();
        delete completedCommandIds[expired];
      }
    }

    function recordAck(socket, body) {
      const ack = parseJson(body);
      if (ack === null) {
        return;
      }
      if (pending === null || ack.idempotencyKey !== pending.idempotencyKey) {
        if (ack.commandId && completedCommandIds[ack.commandId]) {
          return;
        }
        protocolErrors.add(true);
        return;
      }
      protocolErrors.add(false);
      if (pending.measured) {
        ingressAckDuration.add(Date.now() - pending.sentAt);
      }
      if (ack.status === 'PUBLISHED') {
        pending.commandId = ack.commandId;
        if (pending.measured) {
          measuredPublishedAcks.add(1);
        }
        return;
      }
      if (ack.status === 'REJECTED') {
        if (pending.measured && ack.rejectionReason === 'BID_NOT_HIGHER') {
          measuredFastRejects.add(1);
        }
        completeFeedback(socket, 'REJECTED', ack.rejectionReason, ack.commandId);
        return;
      }
      protocolErrors.add(true);
    }

    function recordOutcome(socket, body) {
      const outcome = parseJson(body);
      if (outcome === null) {
        return;
      }
      if (Number(outcome.bidderUserId) !== userId) {
        protocolErrors.add(true);
        return;
      }
      protocolErrors.add(false);
      if (outcome.eventType === 'BID_CONFIRMED') {
        completeFeedback(socket, 'CONFIRMED', null, outcome.commandId);
      } else if (outcome.eventType === 'BID_REJECTED') {
        completeFeedback(socket, 'REJECTED', outcome.rejectionReason, outcome.commandId);
      } else {
        protocolErrors.add(true);
      }
    }

    function recordRoomUpdate(body) {
      const event = parseJson(body);
      if (event === null) {
        return;
      }
      if (String(event.auctionWindowId) !== setupData.auctionWindowId
          || event.eventType !== 'RANKING_UPDATED') {
        return;
      }
      protocolErrors.add(false);
      const eventVersion = Number(event.eventVersion || event.decisionVersion || 0);
      if (eventVersion <= visibleVersion) {
        return;
      }
      visibleVersion = eventVersion;
      visiblePrice = Math.max(visiblePrice, highestBid(event.ranking));
      const occurredAt = Date.parse(event.occurredAt);
      if (Number.isFinite(occurredAt)) {
        priceDeliveryDuration.add(Math.max(0, Date.now() - occurredAt));
      }
    }

    function closeWhenFinished(socket) {
      if (!stopped || pending !== null || intentionalClose) {
        return;
      }
      intentionalClose = true;
      socket.send(stompFrame('DISCONNECT', {}));
      socket.close();
    }

    socket.on('open', () => {
      socket.send(stompFrame('CONNECT', {
        'accept-version': '1.2',
        host: 'localhost',
        'heart-beat': '0,0',
      }));
    });

    socket.on('message', (raw) => {
      for (const frame of parseStompFrames(raw)) {
        if (frame.command === 'CONNECTED') {
          socket.send(stompFrame('SUBSCRIBE', {
            id: ackSubscription,
            destination: '/user/queue/promotion-auction-bid-acks',
            ack: 'auto',
            receipt: `ready-${ackSubscription}`,
          }));
          socket.send(stompFrame('SUBSCRIBE', {
            id: outcomeSubscription,
            destination: '/user/queue/promotion-auction-outcomes',
            ack: 'auto',
            receipt: `ready-${outcomeSubscription}`,
          }));
          socket.send(stompFrame('SUBSCRIBE', {
            id: roomSubscription,
            destination: `/topic/promotion-auctions/${setupData.auctionWindowId}`,
            ack: 'auto',
            receipt: `ready-${roomSubscription}`,
          }));
          socket.setTimeout(startAfterSubscriptions, subscriptionReadyMs);
        } else if (frame.command === 'RECEIPT') {
          const receiptId = frame.headers['receipt-id'];
          if (receiptId && receiptId.startsWith('ready-')) {
            subscriptionReceipts[receiptId] = true;
            if (Object.keys(subscriptionReceipts).length === 3) {
              startAfterSubscriptions();
            }
          }
        } else if (frame.command === 'MESSAGE') {
          if (frame.headers.subscription === ackSubscription) {
            recordAck(socket, frame.body);
          } else if (frame.headers.subscription === outcomeSubscription) {
            recordOutcome(socket, frame.body);
          } else if (frame.headers.subscription === roomSubscription) {
            recordRoomUpdate(frame.body);
          }
        } else if (frame.command === 'ERROR') {
          protocolErrors.add(true);
        }
      }
    });

    socket.on('error', () => {
      if (!connectionFailureRecorded) {
        connectionFailures.add(1);
        connectionFailureRecorded = true;
      }
    });
    socket.on('close', () => {
      if (!intentionalClose && !connectionFailureRecorded) {
        connectionFailures.add(1);
        connectionFailureRecorded = true;
      }
    });
    socket.setTimeout(() => {
      stopped = true;
      if (pending !== null && pending.measured && !outcomeTimeoutRecorded) {
        outcomeTimeouts.add(1);
        outcomeTimeoutRecorded = true;
      }
      intentionalClose = true;
      socket.close();
    }, (subscriptionReadyMs / 1000 + totalActiveSeconds + outcomeDrainSeconds) * 1000);
  });

  const upgraded = response && (response.status === 101 || response.status === 0);
  check(response, { 'promotion user-feedback WebSocket upgraded': () => upgraded });
  if (!upgraded && !connectionFailureRecorded) {
    connectionFailures.add(1);
    connectionFailureRecorded = true;
  }
  if (pending !== null && pending.measured && !outcomeTimeoutRecorded) {
    outcomeTimeouts.add(1);
    outcomeTimeoutRecorded = true;
  }
}

function bidderProfile(index) {
  const reactionRange = reactionMaxMs - reactionMinMs;
  const reactionPosition = ((index * 2654435761) >>> 0) / 0xffffffff;
  const valuationRange = valuationMaxPercent - valuationMinPercent;
  const valuationPosition = ((index * 2246822519 + 3266489917) >>> 0) / 0xffffffff;
  return {
    active: ((index * 37) % 100) < activeBidderPercent,
    maximumBid: Math.floor(escrowAmount
      * (valuationMinPercent + valuationRange * valuationPosition) / 100),
    reactionMs: reactionMinMs + reactionRange * reactionPosition,
  };
}

function jitteredReactionMs(baseReactionMs) {
  const jitter = (Math.random() * 2 - 1) * reactionJitterPercent / 100;
  return Math.max(1, Math.round(baseReactionMs * (1 + jitter)));
}

function highestBid(ranking) {
  if (!Array.isArray(ranking) || ranking.length === 0) {
    return 0;
  }
  return ranking.reduce((highest, item) => Math.max(highest, Number(item.bidAmount || 0)), 0);
}

function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch (error) {
    protocolErrors.add(true);
    return null;
  }
}

function stompFrame(command, headers, body = '') {
  const lines = [command];
  for (const [name, value] of Object.entries(headers)) {
    lines.push(`${name}:${value}`);
  }
  if (body.length > 0 && headers['content-length'] === undefined) {
    lines.push(`content-length:${body.length}`);
  }
  lines.push('', body);
  return `${lines.join('\n')}\u0000`;
}

function parseStompFrames(raw) {
  const frames = [];
  for (const value of String(raw).split('\u0000')) {
    const text = value.replace(/^\n+/, '');
    if (!text) {
      continue;
    }
    const separator = text.indexOf('\n\n');
    const headerBlock = separator >= 0 ? text.slice(0, separator) : text;
    const headerLines = headerBlock.split('\n').map((line) => line.replace(/\r$/, ''));
    const headers = {};
    for (const line of headerLines.slice(1)) {
      const colon = line.indexOf(':');
      if (colon > 0) {
        headers[line.slice(0, colon)] = line.slice(colon + 1);
      }
    }
    frames.push({
      command: headerLines[0],
      headers,
      body: separator >= 0 ? text.slice(separator + 2) : '',
    });
  }
  return frames;
}

function validateConfiguration() {
  if (bidderVus > campaignPoolSize || bidderVus > USER_POOL) {
    fail(`VUS=${bidderVus} requires at least the same campaign and user pool size`);
  }
  if (reactionMinMs <= 0 || reactionMaxMs < reactionMinMs) {
    fail('reaction delay range is invalid');
  }
  if (incrementMin <= 0 || incrementMax < incrementMin) {
    fail('bid increment range is invalid');
  }
  if (activeBidderPercent <= 0 || activeBidderPercent > 100) {
    fail('ACTIVE_BIDDER_PERCENT must be in (0, 100]');
  }
  if (valuationMinPercent <= 0 || valuationMaxPercent > 100
      || valuationMaxPercent < valuationMinPercent) {
    fail('valuation percentage range is invalid');
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

function loadSnapshot(token, auctionWindowId) {
  const response = http.get(`${BASE_URL}/api/v1/promotions/windows/${auctionWindowId}/snapshot`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'setup.promotion.snapshot' },
  });
  if (response.status !== 200) {
    fail(`setup snapshot failed: status=${response.status} body=${response.body.slice(0, 200)}`);
  }
  return response.json();
}

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

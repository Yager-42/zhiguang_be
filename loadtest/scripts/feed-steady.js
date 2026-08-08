import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { vu } from 'k6/execution';
import {
  BASE_URL,
  PASSWORD,
  USER_ID_BASE,
  USER_POOL,
  apiWithAccessToken,
  randomPostId,
} from './common.js';

const VUS = Number(__ENV.VUS || 500);
const RATE = Number(__ENV.RATE || 0);
const WARMUP_SECONDS = Number(__ENV.WARMUP_SECONDS || 45);
const WARMUP_VUS = Number(__ENV.WARMUP_VUS || VUS);
const SETTLE_SECONDS = Number(__ENV.SETTLE_SECONDS || 5);
const MEASURE_SECONDS = Number(__ENV.MEASURE_SECONDS || 60);
const LOGIN_BATCH_SIZE = Number(__ENV.LOGIN_BATCH_SIZE || 20);

const measuredRequests = new Counter('feed_measured_requests');
const measuredFailures = new Rate('feed_measured_failures');
const measuredDuration = new Trend('feed_measured_duration', true);

const measureScenario = RATE > 0
  ? {
      executor: 'constant-arrival-rate',
      exec: 'measure',
      rate: RATE,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(VUS, Math.max(50, Math.ceil(RATE / 10))),
      maxVUs: VUS,
      startTime: `${WARMUP_SECONDS + SETTLE_SECONDS}s`,
      duration: `${MEASURE_SECONDS}s`,
      gracefulStop: '10s',
      tags: { phase: 'measure' },
    }
  : {
      executor: 'constant-vus',
      exec: 'measure',
      vus: VUS,
      startTime: `${WARMUP_SECONDS + SETTLE_SECONDS}s`,
      duration: `${MEASURE_SECONDS}s`,
      gracefulStop: '10s',
      tags: { phase: 'measure' },
    };

export const options = {
  setupTimeout: '10m',
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      exec: 'warmup',
      vus: WARMUP_VUS,
      duration: `${WARMUP_SECONDS}s`,
      gracefulStop: `${SETTLE_SECONDS}s`,
      tags: { phase: 'warmup' },
    },
    measure: measureScenario,
  },
  thresholds: {
    feed_measured_failures: ['rate<0.01'],
    feed_measured_duration: ['p(95)<800'],
    'http_req_failed{scenario:measure}': ['rate<0.01'],
    'http_req_duration{scenario:measure}': ['p(95)<800'],
    'http_req_duration{name:feed.public,phase:measure}': ['p(95)<800'],
    'http_req_duration{name:feed.public.anon,phase:measure}': ['p(95)<800'],
    'http_req_duration{name:feed.follow,phase:measure}': ['p(95)<800'],
    'http_req_duration{name:feed.follow.page2,phase:measure}': ['p(95)<800'],
    'http_req_duration{name:feed.mine,phase:measure}': ['p(95)<800'],
    'http_req_duration{name:knowpost.detail,phase:measure}': ['p(95)<800'],
  },
};

export function setup() {
  const tokens = [];
  for (let offset = 1; offset <= Math.min(VUS, USER_POOL); offset += LOGIN_BATCH_SIZE) {
    const requests = [];
    const batchEnd = Math.min(offset + LOGIN_BATCH_SIZE, Math.min(VUS, USER_POOL) + 1);
    for (let n = offset; n < batchEnd; n++) {
      requests.push([
        'POST',
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({
          identifierType: 'PHONE',
          identifier: `139${String(n).padStart(8, '0')}`,
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
  if (tokens.length === 0) {
    fail('setup produced no access tokens');
  }
  return { tokens };
}

export function warmup(data) {
  executeFeedRequest(tokenFor(data), false);
}

export function measure(data) {
  executeFeedRequest(tokenFor(data), true);
}

function tokenFor(data) {
  return data.tokens[(vu.idInTest - 1) % data.tokens.length];
}

function executeFeedRequest(accessToken, measured) {
  const selector = Math.random();
  if (selector < 0.50) {
    publicFeed(accessToken, measured);
  } else if (selector < 0.75) {
    followFeed(accessToken, measured);
  } else if (selector < 0.90) {
    mineFeed(accessToken, measured);
  } else {
    detail(measured);
  }
}

function publicFeed(accessToken, measured) {
  const anonymous = Math.random() < 0.3;
  const response = anonymous
    ? http.get(`${BASE_URL}/api/v1/knowposts/feed?page=1&size=20`, requestParams('feed.public.anon', measured))
    : apiWithAccessToken(
        'GET',
        '/api/v1/knowposts/feed?page=1&size=20',
        null,
        requestTags('feed.public', measured),
        accessToken
      );
  record(response, measured, 'feed.public');
}

function followFeed(accessToken, measured) {
  const response = apiWithAccessToken(
    'GET',
    '/api/v1/knowposts/feed/follow',
    null,
    requestTags('feed.follow', measured),
    accessToken
  );
  record(response, measured, 'feed.follow');
  if (response.status !== 200) {
    return;
  }
  const nextCursor = response.json().nextCursor;
  if (nextCursor && Math.random() < 0.3) {
    const page2 = apiWithAccessToken(
      'GET',
      `/api/v1/knowposts/feed/follow?cursor=${encodeURIComponent(nextCursor)}`,
      null,
      requestTags('feed.follow.page2', measured),
      accessToken
    );
    record(page2, measured, 'feed.follow.page2');
  }
}

function mineFeed(accessToken, measured) {
  const response = apiWithAccessToken(
    'GET',
    '/api/v1/knowposts/mine?page=1&size=20',
    null,
    requestTags('feed.mine', measured),
    accessToken
  );
  record(response, measured, 'feed.mine');
}

function detail(measured) {
  const response = http.get(
    `${BASE_URL}/api/v1/knowposts/detail/${randomPostId()}`,
    requestParams('knowpost.detail', measured)
  );
  record(response, measured, 'knowpost.detail');
}

function requestTags(name, measured) {
  return { name, phase: measured ? 'measure' : 'warmup' };
}

function requestParams(name, measured) {
  return { tags: requestTags(name, measured) };
}

function record(response, measured, checkName) {
  check(response, { [`${checkName} 200`]: (result) => result.status === 200 });
  if (!measured) {
    return;
  }
  measuredRequests.add(1);
  measuredFailures.add(response.status !== 200);
  measuredDuration.add(response.timings.duration);
}

import { check } from 'k6';
import http from 'k6/http';
import { scenario, vu } from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  apiWithAccessToken,
  BASE_URL,
  HOT_POST_ID,
  PASSWORD,
  POST_COUNT,
  POST_ID_BASE,
  USER_ID_BASE,
  USER_POOL,
  uuid,
} from './common.js';

const mode = __ENV.COMMENT_MODE || 'pure-read';
const readRatio = Number(__ENV.READ_RATIO || (mode === 'pure-read' ? 1 : 0.8));
const hotRatio = Number(__ENV.HOT_RATIO || 0.8);
const rate = Number(__ENV.RATE || 20);
const measurementDuration = __ENV.HOLD || '1m';
const warmupSeconds = Math.max(0, Number(__ENV.WARMUP_SECONDS || 10));
const scenarioDuration = `${durationSeconds(measurementDuration) + warmupSeconds}s`;
const maxVus = Number(__ENV.VUS || Math.max(20, rate));
const preAllocatedVus = Number(__ENV.PREALLOCATED_VUS || maxVus);
const drainWrites = Number(__ENV.DRAIN_WRITES || 100);
const tokenPoolSize = Math.max(1, Math.min(USER_POOL, Number(__ENV.TOKEN_POOL || 32)));

const readSucceeded = new Counter('comment_read_succeeded');
const submitAccepted = new Counter('comment_submit_accepted');
const businessErrors = new Rate('comment_business_errors');
const readDuration = new Trend('comment_read_duration', true);
const submitDuration = new Trend('comment_submit_duration', true);

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: mode === 'drain'
    ? {
        main: {
          executor: 'shared-iterations',
          vus: Math.min(maxVus, drainWrites),
          iterations: drainWrites,
          maxDuration: measurementDuration,
          gracefulStop: '10s',
        },
      }
    : mode === 'mixed'
      ? {
          reads: fixedArrivalScenario('readIteration', Math.round(rate * readRatio)),
          submits: fixedArrivalScenario('submitIteration', rate - Math.round(rate * readRatio)),
        }
      : mode === 'write-only'
        ? {
            submits: fixedArrivalScenario('submitIteration', rate),
          }
        : {
        main: {
          ...fixedArrivalScenario('readIteration', rate),
        },
      },
  thresholds: {
    comment_business_errors: ['rate<0.01'],
    'http_req_failed{phase:steady}': ['rate<0.01'],
  },
};

export function setup() {
  const accessTokens = [];
  for (let index = 1; index <= tokenPoolSize; index += 1) {
    const response = http.post(
      `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({
        identifierType: 'PHONE',
        identifier: `139${String(index).padStart(8, '0')}`,
        password: PASSWORD,
      }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'comment.capacity.setup.login' } },
    );
    if (response.status !== 200 || !response.json().token?.accessToken) {
      throw new Error(`capacity setup login failed for user ${USER_ID_BASE + index}: ${response.status}`);
    }
    accessTokens.push(response.json().token.accessToken);
  }
  return { accessTokens };
}

export default function (data) {
  if (mode === 'drain' || mode === 'write-only') {
    submit(fixedPostId(), tokenForVu(data));
  } else {
    read(fixedPostId(), tokenForVu(data));
  }
}

export function readIteration(data) {
  read(fixedPostId(), tokenForVu(data));
}

export function submitIteration(data) {
  submit(fixedPostId(), tokenForVu(data));
}

function tokenForVu(data) {
  return data.accessTokens[(vu.idInTest - 1) % data.accessTokens.length];
}

function fixedArrivalScenario(exec, scenarioRate) {
  const scenarioVus = Math.max(10, Math.min(maxVus, Math.ceil(maxVus * scenarioRate / rate)));
  const scenarioPreAllocatedVus = Math.max(10,
    Math.min(scenarioVus, Math.ceil(preAllocatedVus * scenarioRate / rate)));
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate: scenarioRate,
    timeUnit: '1s',
    duration: scenarioDuration,
    preAllocatedVUs: scenarioPreAllocatedVus,
    maxVUs: scenarioVus,
    gracefulStop: '30s',
  };
}

function fixedPostId() {
  if (Math.random() < hotRatio) {
    return HOT_POST_ID;
  }
  return String(POST_ID_BASE + 1 + Math.floor(Math.random() * POST_COUNT));
}

function read(postId, accessToken) {
  const measured = isSteadyPhase();
  const response = apiWithAccessToken('GET', `/api/v1/posts/${postId}/comments?limit=20`, null,
    { name: 'comment.throughput.read', phase: measured ? 'steady' : 'warmup' }, accessToken);
  const valid = check(response, {
    'comment read 200': (result) => result.status === 200,
    'comment read items': (result) => Array.isArray(result.json().items),
  });
  if (measured) {
    businessErrors.add(!valid);
    readDuration.add(response.timings.duration);
    if (valid) {
      readSucceeded.add(1);
    }
  }
}

function submit(postId, accessToken) {
  const measured = isSteadyPhase();
  const requestId = uuid();
  const response = apiWithAccessToken('POST', `/api/v1/posts/${postId}/comments`, {
    postId: Number(postId),
    clientRequestId: requestId,
    body: `throughput ${requestId}`,
  }, { name: 'comment.throughput.submit', phase: measured ? 'steady' : 'warmup' }, accessToken);
  const valid = check(response, {
    'comment submit 202': (result) => result.status === 202,
    'comment submit id': (result) => Boolean(result.json().pendingCommentId),
  });
  if (measured) {
    businessErrors.add(!valid);
    submitDuration.add(response.timings.duration);
    if (valid) {
      submitAccepted.add(1);
    }
  }
}

function isSteadyPhase() {
  return mode === 'drain' || Date.now() - scenario.startTime >= warmupSeconds * 1000;
}

function durationSeconds(value) {
  const units = { ms: 0.001, s: 1, m: 60, h: 3600 };
  const pattern = /(\d+(?:\.\d+)?)(ms|s|m|h)/g;
  let seconds = 0;
  let consumed = '';
  let match;
  while ((match = pattern.exec(value)) !== null) {
    seconds += Number(match[1]) * units[match[2]];
    consumed += match[0];
  }
  if (consumed !== value || seconds <= 0) {
    throw new Error(`invalid HOLD duration: ${value}`);
  }
  return seconds;
}

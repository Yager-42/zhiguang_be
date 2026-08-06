// S5 关注关系：follow / unfollow / status / following / counter
// 注意令牌桶限流：容量 100、1 token/s/用户（rl:follow:{userId}），超限返回 200 + body=false。
// 每 VU 最多每秒 follow 一次，并单独统计意外限流命中。
// 运行: k6 run -e VUS=300 scripts/relation.js
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { api, currentUser, randomUserId, followReady, markFollowed, buildOptions } from './common.js';

export const options = buildOptions();

export const rateLimited = new Counter('relation_rate_limited');

export default function () {
  const r = Math.random();

  if (r < 0.5) {
    // follow：按后端令牌桶补充速率节流，避免无 think-time 场景耗尽令牌。
    if (!followReady()) {
      readOnly();
      return;
    }
    follow();
  } else if (r < 0.6) {
    unfollow();
  } else if (r < 0.8) {
    status();
  } else if (r < 0.9) {
    followingList();
  } else {
    counter();
  }
}

function follow() {
  markFollowed();
  const target = randomUserId();
  const res = api('POST', `/api/v1/relation/follow?toUserId=${target}`, null, { name: 'relation.follow' });
  check(res, {
    'relation.follow 200': (r) => r.status === 200,
    // 限流时 body=false（HTTP 仍 200）
    'relation.follow accepted': (r) => r.status === 200 && r.body === 'true',
  });
  if (res.status === 200 && res.body !== 'true') {
    rateLimited.add(1);
  }
}

function unfollow() {
  const target = randomUserId();
  const res = api('POST', `/api/v1/relation/unfollow?toUserId=${target}`, null, { name: 'relation.unfollow' });
  check(res, { 'relation.unfollow 200': (r) => r.status === 200 });
}

function status() {
  const target = randomUserId();
  const res = api('GET', `/api/v1/relation/status?toUserId=${target}`, null, { name: 'relation.status' });
  check(res, {
    'relation.status 200': (r) => r.status === 200,
    'relation.status object': (r) => typeof r.json() === 'object',
  });
}

function followingList() {
  const u = currentUser();
  const res = api('GET', `/api/v1/relation/following?userId=${u.userId}&limit=20&offset=0`, null, { name: 'relation.following' });
  check(res, {
    'relation.following 200': (r) => r.status === 200,
    'relation.following array': (r) => Array.isArray(r.json()),
  });
}

function counter() {
  const u = currentUser();
  const res = api('GET', `/api/v1/relation/counter?userId=${u.userId}`, null, { name: 'relation.counter' });
  check(res, {
    'relation.counter 200': (r) => r.status === 200,
    'relation.counter object': (r) => typeof r.json() === 'object',
  });
}

// follow 节流期间退回只读操作，维持负载形态
function readOnly() {
  status();
}

// 公共配置与工具：所有场景脚本复用。
// 每个 VU 拥有独立的模块级状态（k6 为每个 VU 启动独立运行时），
// 因此登录态、迭代计数等按 VU 隔离，跨迭代保留。
import http from 'k6/http';
import { vu } from 'k6/execution';

// ---------- 环境配置（全部可被 -e 覆盖，默认值与 seed/*.sql 保持一致） ----------

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 种子用户池（seed_users.sql）：ID 从 USER_ID_BASE 起，手机号 = '139' + 8 位序号
export const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 1000000);
export const USER_POOL = Number(__ENV.USER_POOL || 1000);
export const PASSWORD = __ENV.PASSWORD || 'Loadtest@123';

// 帖子池（seed_posts.sql）：ID 从 POST_ID_BASE 起，第一篇（BASE+1）为热帖
export const POST_ID_BASE = Number(__ENV.POST_ID_BASE || 2000000);
export const POST_COUNT = Number(__ENV.POST_COUNT || 500);
export const HOT_POST_ID = __ENV.HOT_POST_ID || String(POST_ID_BASE + 1);

// 大V（seed_follow_graph.sql 可选）
export const LARGE_AUTHOR_ID = __ENV.LARGE_AUTHOR_ID || String(USER_ID_BASE + 1);

// 推广（seed_promotion.sql）
export const PROMO_CAMPAIGN_ID = __ENV.PROMO_CAMPAIGN_ID || '3000001';
export const PROMO_WINDOW_ID = __ENV.PROMO_WINDOW_ID || '3000002';

// 搜索热词（需 ES 已回填帖子）
const HOT_TERMS = (__ENV.HOT_TERMS || 'java,spring,算法,数据库,压测,知光').split(',');

// ---------- 每 VU 状态 ----------

const state = {
  token: '',
  refreshToken: '',
  expiresAt: 0,
  userId: '',
  phone: '',
  loginCount: 0,
  iterCount: 0,
  lastFollowAtMs: 0,
};

// 当前 VU 对应的种子用户（idInTest 从 1 起）
export function currentUser() {
  if (!state.phone) {
    const n = ((vu.idInTest - 1) % USER_POOL) + 1;
    state.userId = String(USER_ID_BASE + n);
    state.phone = '139' + String(n).padStart(8, '0');
  }
  return { userId: state.userId, phone: state.phone };
}

// 固定到指定种子用户；用于所有 VU 必须共享同一资源所有者的场景。
export function selectSeedUser(userNumber) {
  if (state.phone) return;
  const n = Math.max(1, Math.min(USER_POOL, Number(userNumber)));
  state.userId = String(USER_ID_BASE + n);
  state.phone = '139' + String(n).padStart(8, '0');
}

export function iterationCount() {
  return ++state.iterCount;
}

// follow 写入按用户最多每秒一次，与后端令牌桶 1 token/s 的补充速率一致。
export function followReady() {
  return Date.now() - state.lastFollowAtMs >= 1000;
}

export function markFollowed() {
  state.lastFollowAtMs = Date.now();
}

// ---------- 登录与鉴权 ----------

export function ensureLogin() {
  const now = Date.now();
  if (state.token && state.expiresAt - now > 60_000) {
    return;
  }
  const u = currentUser();
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ identifierType: 'PHONE', identifier: u.phone, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'auth.login' } }
  );
  if (res.status !== 200) {
    // 种子用户缺失或服务异常时快速暴露（错误计入 http_req_failed）
    console.error(`login failed status=${res.status} phone=${u.phone} body=${res.body.slice(0, 200)}`);
    return;
  }
  const body = res.json();
  state.token = body.token.accessToken;
  state.refreshToken = body.token.refreshToken;
  state.expiresAt = Date.parse(body.token.accessTokenExpiresAt);
  state.loginCount++;
}

export function authHeaders() {
  ensureLogin();
  return { Authorization: `Bearer ${state.token}`, 'Content-Type': 'application/json' };
}

export function refreshToken() {
  return state.refreshToken;
}

export function forceRelogin() {
  state.token = '';
  state.expiresAt = 0;
}

// 带 401 自动重登一次的请求包装。
// body 为对象时自动 JSON 序列化；extraTags 追加到请求 tag（按 name 分组统计）。
export function api(method, path, body, extraTags) {
  ensureLogin();
  const tags = { name: path.split('?')[0], ...(extraTags || {}) };
  const params = { headers: authHeaders(), tags };
  let res = http.request(method, `${BASE_URL}${path}`, body ? JSON.stringify(body) : null, params);
  if (res.status === 401 && state.loginCount < 5) {
    forceRelogin();
    ensureLogin();
    res = http.request(method, `${BASE_URL}${path}`, body ? JSON.stringify(body) : null, params);
  }
  return res;
}

// ---------- 数据工具 ----------

export function randomPostId() {
  return String(POST_ID_BASE + 1 + Math.floor(Math.random() * POST_COUNT));
}

export function randomUserId() {
  const u = currentUser();
  let target = USER_ID_BASE + 1 + Math.floor(Math.random() * USER_POOL);
  if (String(target) === u.userId) {
    target = USER_ID_BASE + 1 + ((target - USER_ID_BASE) % USER_POOL);
  }
  return String(target);
}

export function hotTerm() {
  return HOT_TERMS[Math.floor(Math.random() * HOT_TERMS.length)];
}

export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function randomInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

// ---------- 场景构建 ----------

// 负载模型选择（压测机资源保障关键）：
//   1) 默认 ramping-vus —— 容量测试：RAMP 内爬升到 VUS 找拐点（拐点=系统容量，VU 数即负载）
//   2) 设置 RATE=N —— constant-arrival-rate：固定 N 次迭代/秒。
//      常态基线/可复现负载用它：同样的 QPS 只需远少于 ramping-vus 的 VU 数，
//      压测机 CPU 与连接占用显著降低，测量更接近"目标 QPS"而非"目标并发"。
//   3) SOAK=1 —— constant-vus 长稳。
// 统一阈值：错误率 < 1%，P95 < P95(默认 800ms)，可用 -e P95=1500 放宽。
export function buildOptions() {
  const peak = Number(__ENV.VUS || 100);
  const hold = __ENV.HOLD || '5m';
  const ramp = __ENV.RAMP || '30s';
  const rampOut = __ENV.RAMP_OUT || '30s';
  const p95 = Number(__ENV.P95 || 800);
  const rate = Number(__ENV.RATE || 0);

  let scenario;
  if (rate > 0) {
    scenario = {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: hold,
      // 预分配 VU 数按目标速率的一半估算；不足时自动升到 maxVUs
      preAllocatedVUs: Math.max(10, Math.min(peak, Math.ceil(rate / 2))),
      maxVUs: peak,
      gracefulStop: '30s',
    };
  } else if (__ENV.SOAK === '1') {
    scenario = { executor: 'constant-vus', vus: peak, duration: hold, gracefulStop: '30s' };
  } else {
    scenario = {
      executor: 'ramping-vus',
      stages: [
        { duration: ramp, target: peak },
        { duration: hold, target: peak },
        { duration: rampOut, target: 0 },
      ],
      gracefulStop: '30s',
    };
  }

  return {
    scenarios: { main: scenario },
    thresholds: {
      http_req_failed: ['rate<0.01'],
      http_req_duration: [`p(95)<${p95}`],
    },
  };
}

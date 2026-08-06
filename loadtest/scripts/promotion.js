// S8 推广竞价 B'：出价提交（RocketMQ → Redis Lua 决策 → Kafka 日志 → MySQL 投影 + 钱包 HOLD）
// 前置：
//   1) PROMOTION_BPRIME_ENABLED=true 且 RocketMQ 正常
//   2) seed_promotion.sql 已灌（campaign=3000001, window=3000002 OPEN）
// 出价全部打同一 campaign/窗口 → 构造收单期单点竞争（ranking ZSET 热写）
// 运行: k6 run -e VUS=300 scripts/promotion.js
import { check } from 'k6';
import { api, randomInt, uuid, HOT_POST_ID, PROMO_CAMPAIGN_ID, PROMO_WINDOW_ID, selectSeedUser, buildOptions } from './common.js';

export const options = buildOptions();

export default function () {
  // 种子 campaign 及 HOT_POST_ID 均属于第 1 个种子用户；所有 VU 共用该所有者才能形成同窗口竞争。
  selectSeedUser(1);
  const r = Math.random();
  if (r < 0.6) {
    submitBid();
  } else if (r < 0.8) {
    snapshot();
  } else if (r < 0.9) {
    activeAllocations();
  } else {
    createCampaign();
  }
}

function submitBid() {
  const res = api('POST', `/api/v1/promotions/campaigns/${PROMO_CAMPAIGN_ID}/bids`, {
    bidAmount: randomInt(1, 1000),
    idempotencyKey: uuid(),
  }, { name: 'promotion.bid.submit' });
  check(res, {
    'promotion.bid.submit 200': (r) => r.status === 200,
    'promotion.bid.submit commandId': (r) => r.json().commandId !== undefined && r.json().commandId !== '',
  });
}

function snapshot() {
  const res = api('GET', `/api/v1/promotions/windows/${PROMO_WINDOW_ID}/snapshot`, null, { name: 'promotion.snapshot' });
  check(res, {
    'promotion.snapshot 200': (r) => r.status === 200,
    'promotion.snapshot object': (r) => typeof r.json() === 'object',
  });
}

function activeAllocations() {
  const res = api('GET', '/api/v1/promotions/allocations/active?resourceType=feed_top_slot', null, { name: 'promotion.allocations' });
  check(res, {
    'promotion.allocations 200': (r) => r.status === 200,
    'promotion.allocations array': (r) => Array.isArray(r.json()),
  });
}

// 建活动：10% 低频；startAt 需在种子窗口有效期内（5 分钟后 ~65 分钟后）
function createCampaign() {
  const now = Date.now();
  const res = api('POST', '/api/v1/promotions/campaigns', {
    postId: Number(HOT_POST_ID),
    resourceType: 'feed_top_slot',
    startAt: new Date(now + 5 * 60_000).toISOString(),
    endAt: new Date(now + 65 * 60_000).toISOString(),
  }, { name: 'promotion.campaign.create' });
  check(res, {
    'promotion.campaign.create 200': (r) => r.status === 200,
    'promotion.campaign.create object': (r) => typeof r.json() === 'object',
  });
}

// S9 混合用户旅程 —— 常态/高压基线主场景
// 每迭代：1 次 Feed 首页 + 按概率触发详情/点赞/计数读/评论/搜索联想/关注/我的 Feed，
// 随机 think-time 0.2~1s，模拟真实用户行为混合负载（各模块互相竞争资源）。
// 运行: k6 run -e VUS=300 -e HOLD=30m scripts/mixed.js
import http from 'k6/http';
import { check } from 'k6';
import { sleep } from 'k6';
import {
  BASE_URL, api, randomPostId, randomUserId, hotTerm, uuid, HOT_POST_ID,
  followReady, markFollowed, buildOptions,
} from './common.js';

export const options = buildOptions();

export default function () {

  // 1. 每迭代必刷 Feed 首页（公共，70% 登录）
  const feed = api('GET', '/api/v1/knowposts/feed?page=1&size=20', null, { name: 'mixed.feed' });
  check(feed, { 'mixed.feed 200': (r) => r.status === 200 });

  const roll = Math.random();

  if (roll < 0.35) {
    // 详情（读到 feed 返回的 id 更真实；取不到就随机）
    const items = feed.json().items || [];
    const id = items.length > 0 ? String(items[0].id) : randomPostId();
    const res = http.get(`${BASE_URL}/api/v1/knowposts/detail/${id}`, { tags: { name: 'mixed.detail' } });
    check(res, { 'mixed.detail 200': (r) => r.status === 200 });
  } else if (roll < 0.55) {
    // 点赞（随机帖，50% 热帖）
    const eid = Math.random() < 0.5 ? HOT_POST_ID : randomPostId();
    const res = api('POST', '/api/v1/action/like', { entityType: 'knowpost', entityId: eid }, { name: 'mixed.like' });
    check(res, { 'mixed.like 200': (r) => r.status === 200 });
  } else if (roll < 0.65) {
    // 计数读
    const res = api('GET', `/api/v1/counter/knowpost/${randomPostId()}?metrics=like,fav`, null, { name: 'mixed.counter.read' });
    check(res, { 'mixed.counter.read 200': (r) => r.status === 200 });
  } else if (roll < 0.70) {
    // 评论列表
    const res = api('GET', `/api/v1/posts/${randomPostId()}/comments?limit=20`, null, { name: 'mixed.comment.list' });
    check(res, { 'mixed.comment.list 200': (r) => r.status === 200 });
  } else if (roll < 0.75) {
    // 评论提交
    const postId = randomPostId();
    const res = api('POST', `/api/v1/posts/${postId}/comments`, {
      postId: Number(postId), clientRequestId: uuid(), body: 'mixed comment ' + uuid(),
    }, { name: 'mixed.comment.submit' });
    check(res, { 'mixed.comment.submit 202': (r) => r.status === 202 });
  } else if (roll < 0.80) {
    // 搜索联想
    const term = hotTerm();
    const res = api('GET', `/api/v1/search/suggest?prefix=${encodeURIComponent(term.slice(0, 2))}&size=10`, null, { name: 'mixed.suggest' });
    check(res, { 'mixed.suggest 200': (r) => r.status === 200 });
  } else if (roll < 0.85) {
    // 关注：按后端令牌桶补充速率，每 VU 最多每秒一次。
    if (followReady()) {
      markFollowed();
      const res = api('POST', `/api/v1/relation/follow?toUserId=${randomUserId()}`, null, { name: 'mixed.follow' });
      check(res, { 'mixed.follow 200': (r) => r.status === 200 && r.body === 'true' });
    }
  } else if (roll < 0.90) {
    // 我的 Feed
    const res = api('GET', '/api/v1/knowposts/mine?page=1&size=20', null, { name: 'mixed.feed.mine' });
    check(res, { 'mixed.feed.mine 200': (r) => r.status === 200 });
  } else {
    // 关注 Feed（含翻页，30%）
    const res = api('GET', '/api/v1/knowposts/feed/follow', null, { name: 'mixed.feed.follow' });
    check(res, { 'mixed.feed.follow 200': (r) => r.status === 200 });
    const body = res.json();
    if (body.nextCursor && Math.random() < 0.3) {
      const page2 = api('GET', `/api/v1/knowposts/feed/follow?cursor=${encodeURIComponent(body.nextCursor)}`, null, { name: 'mixed.feed.follow.page2' });
      check(page2, { 'mixed.feed.follow.page2 200': (r) => r.status === 200 });
    }
  }

  // 随机思考时间 0.2 ~ 1.0s
  sleep(0.2 + Math.random() * 0.8);
}

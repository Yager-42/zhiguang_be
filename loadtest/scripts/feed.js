// S2 Feed 读：公共 Feed / 关注 Feed（含翻页）/ 我的 Feed / 详情
// P0 场景：三级缓存 + hotkey + 单飞 + 关注 Feed 每条目 MySQL 点查
// 运行: k6 run -e VUS=300 scripts/feed.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, api, randomPostId, buildOptions } from './common.js';

export const options = buildOptions();

export default function () {
  const r = Math.random();
  if (r < 0.50) publicFeed();
  else if (r < 0.75) followFeed();
  else if (r < 0.90) mineFeed();
  else detail();
}

// 公共 Feed：30% 匿名（permitAll），70% 登录
function publicFeed() {
  const anon = Math.random() < 0.3;
  const res = anon
    ? http.get(`${BASE_URL}/api/v1/knowposts/feed?page=1&size=20`, { tags: { name: 'feed.public.anon' } })
    : api('GET', '/api/v1/knowposts/feed?page=1&size=20', null, { name: 'feed.public' });
  check(res, {
    'feed.public 200': (r) => r.status === 200,
    'feed.public items': (r) => Array.isArray(r.json().items),
  });
}

// 关注 Feed：首页默认尺寸命中 timeline 缓存；30% 概率用 nextCursor 翻页（旁路缓存，直读 Cassandra）
function followFeed() {
  const res = api('GET', '/api/v1/knowposts/feed/follow', null, { name: 'feed.follow' });
  check(res, {
    'feed.follow 200': (r) => r.status === 200,
    'feed.follow items': (r) => Array.isArray(r.json().items),
  });
  const body = res.json();
  if (body.nextCursor && Math.random() < 0.3) {
    const page2 = api('GET', `/api/v1/knowposts/feed/follow?cursor=${encodeURIComponent(body.nextCursor)}`, null, { name: 'feed.follow.page2' });
    check(page2, {
      'feed.follow.page2 200': (r) => r.status === 200,
      'feed.follow.page2 items': (r) => Array.isArray(r.json().items),
    });
  }
}

function mineFeed() {
  const res = api('GET', '/api/v1/knowposts/mine?page=1&size=20', null, { name: 'feed.mine' });
  check(res, {
    'feed.mine 200': (r) => r.status === 200,
    'feed.mine items': (r) => Array.isArray(r.json().items),
  });
}

// 详情：permitAll；随机帖。注意正文文本需已灌 Cassandra（否则回源 content_url）
function detail() {
  const res = http.get(`${BASE_URL}/api/v1/knowposts/detail/${randomPostId()}`, { tags: { name: 'knowpost.detail' } });
  check(res, {
    'knowpost.detail 200': (r) => r.status === 200,
    'knowpost.detail object': (r) => typeof r.json() === 'object',
  });
}

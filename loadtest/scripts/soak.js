// S10 长稳：恒定负载跑 2 小时（SOAK=1 切 constant-vus），观察定时任务与键空间增长
// 观测点（配合 collect_metrics.sh + 应用日志）：
//   - 通知桶 30s KEYS 扫描是否引入 Redis 延迟抖动
//   - 计数聚合桶 1s 刷写积压
import http from 'k6/http';
import { check } from 'k6';
import { sleep } from 'k6';
import {
  BASE_URL, api, randomPostId, hotTerm, buildOptions,
} from './common.js';

export const options = buildOptions();

export default function () {
  // 与 mixed.js 相同的旅程（长稳阶段随机性稍低，保证负载形态稳定）
  const feed = api('GET', '/api/v1/knowposts/feed?page=1&size=20', null, { name: 'soak.feed' });
  check(feed, { 'soak.feed 200': (r) => r.status === 200 });

  const roll = Math.random();
  if (roll < 0.3) {
    const items = feed.json().items || [];
    const id = items.length > 0 ? String(items[0].id) : randomPostId();
    http.get(`${BASE_URL}/api/v1/knowposts/detail/${id}`, { tags: { name: 'soak.detail' } });
  } else if (roll < 0.55) {
    api('POST', '/api/v1/action/like', { entityType: 'knowpost', entityId: randomPostId() }, { name: 'soak.like' });
  } else if (roll < 0.7) {
    api('GET', `/api/v1/counter/knowpost/${randomPostId()}?metrics=like,fav`, null, { name: 'soak.counter.read' });
  } else if (roll < 0.8) {
    const term = hotTerm();
    http.get(`${BASE_URL}/api/v1/search/suggest?prefix=${encodeURIComponent(term.slice(0, 2))}&size=10`, { tags: { name: 'soak.suggest' } });
  } else {
    const res = api('GET', '/api/v1/knowposts/feed/follow', null, { name: 'soak.feed.follow' });
    const body = res.json();
    if (body.nextCursor && Math.random() < 0.3) {
      api('GET', `/api/v1/knowposts/feed/follow?cursor=${encodeURIComponent(body.nextCursor)}`, null, { name: 'soak.feed.follow.page2' });
    }
  }

  // 与 mixed 相同的 think-time
  sleep(0.2 + Math.random() * 0.8);
}

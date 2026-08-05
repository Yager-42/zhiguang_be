// S3 计数写读：like/unlike/fav/unfav + 计数读（位图 Lua → Kafka 聚合 → SDS）
// P0 场景：热帖（50%）vs 随机帖（50%）；有效写 TPS 见 counter.effective_writes
// 运行: k6 run -e VUS=300 scripts/counter.js
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { api, randomPostId, HOT_POST_ID, buildOptions } from './common.js';

export const options = buildOptions();

// 有效业务写次数：like/fav 返回 changed=true（幂等去重后真正产事件的操作）
export const effectiveWrites = new Counter('counter.effective_writes');

export default function () {
  const hot = Math.random() < 0.5;
  const eid = hot ? HOT_POST_ID : randomPostId();
  const tag = hot ? 'hot' : 'rand';

  const r = Math.random();
  if (r < 0.55) {
    like(eid, tag);
  } else if (r < 0.70) {
    unlike(eid, tag);
  } else if (r < 0.80) {
    fav(eid, tag);
  } else if (r < 0.90) {
    unfav(eid, tag);
  } else {
    counts(eid, tag);
  }
}

function like(eid, tag) {
  const res = api('POST', '/api/v1/action/like', { entityType: 'knowpost', entityId: eid }, { name: `counter.like_${tag}` });
  check(res, {
    [`counter.like_${tag} 200`]: (r) => r.status === 200,
    [`counter.like_${tag} changed field`]: (r) => r.json().changed !== undefined,
  });
  if (res.status === 200 && res.json().changed === true) {
    effectiveWrites.add(1);
  }
}

function unlike(eid, tag) {
  const res = api('POST', '/api/v1/action/unlike', { entityType: 'knowpost', entityId: eid }, { name: `counter.unlike_${tag}` });
  check(res, {
    [`counter.unlike_${tag} 200`]: (r) => r.status === 200,
  });
}

function fav(eid, tag) {
  const res = api('POST', '/api/v1/action/fav', { entityType: 'knowpost', entityId: eid }, { name: `counter.fav_${tag}` });
  check(res, {
    [`counter.fav_${tag} 200`]: (r) => r.status === 200,
  });
  if (res.status === 200 && res.json().changed === true) {
    effectiveWrites.add(1);
  }
}

function unfav(eid, tag) {
  const res = api('POST', '/api/v1/action/unfav', { entityType: 'knowpost', entityId: eid }, { name: `counter.unfav_${tag}` });
  check(res, {
    [`counter.unfav_${tag} 200`]: (r) => r.status === 200,
  });
}

function counts(eid, tag) {
  const res = api('GET', `/api/v1/counter/knowpost/${eid}?metrics=like,fav`, null, { name: `counter.read_${tag}` });
  check(res, {
    [`counter.read_${tag} 200`]: (r) => r.status === 200,
    [`counter.read_${tag} counts`]: (r) => r.json().counts !== undefined,
  });
}

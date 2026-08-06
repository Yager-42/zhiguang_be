// S4 评论：提交（pending → Kafka 异步落库）/ 评论列表
// 运行: k6 run -e VUS=300 scripts/comment.js
import { check } from 'k6';
import { api, randomPostId, uuid, buildOptions } from './common.js';

export const options = buildOptions();

export default function () {
  const postId = randomPostId();
  if (Math.random() < 0.4) {
    submit(postId);
  } else {
    list(postId);
  }
}

// clientRequestId 唯一 → 幂等键；重复提交同一 id 不会重复建评论
function submit(postId) {
  const res = api('POST', '/api/v1/posts/' + postId + '/comments', {
    postId: Number(postId),
    clientRequestId: uuid(),
    body: 'loadtest comment ' + uuid(),
  }, { name: 'comment.submit' });
  check(res, {
    'comment.submit 202': (r) => r.status === 202,
    'comment.submit object': (r) => typeof r.json() === 'object',
  });
}

function list(postId) {
  const res = api('GET', `/api/v1/posts/${postId}/comments?limit=20`, null, { name: 'comment.list' });
  check(res, {
    'comment.list 200': (r) => r.status === 200,
    'comment.list items': (r) => Array.isArray(r.json().items),
  });
  // 30% 概率翻第二页（游标分页）
  const body = res.json();
  if (body.hasMore && body.nextCursorCreateTime && Math.random() < 0.3) {
    const page2 = api(
      'GET',
      `/api/v1/posts/${postId}/comments?limit=20&cursorCreateTime=${encodeURIComponent(body.nextCursorCreateTime)}&cursorCommentId=${encodeURIComponent(body.nextCursorCommentId || '')}`,
      null,
      { name: 'comment.list.page2' }
    );
    check(page2, { 'comment.list.page2 200': (r) => r.status === 200 });
  }
}

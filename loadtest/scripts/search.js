// S6 搜索：关键词检索（function_score + search_after 深翻页）/ 联想（completion suggest）
// 前置：ES 索引已回填（灌完种子帖子后重启应用，或确认 :9200/zhiguang_content_index/_count > 0）
// 运行: k6 run -e VUS=300 scripts/search.js
import { check } from 'k6';
import { api, hotTerm, buildOptions } from './common.js';

export const options = buildOptions();

export default function () {
  const term = hotTerm();
  if (Math.random() < 0.6) {
    search(term);
  } else {
    suggest(term);
  }
}

function search(term) {
  const res = api('GET', `/api/v1/search?q=${encodeURIComponent(term)}&size=20`, null, { name: 'search.query' });
  check(res, {
    'search.query 200': (r) => r.status === 200,
    'search.query object': (r) => r.status === 200 && typeof r.json() === 'object',
  });
  if (res.status !== 200) return;
  // 30% 概率用 after 游标深翻页（search_after 路径）
  const body = res.json();
  if (body.after && Math.random() < 0.3) {
    const page2 = api(
      'GET',
      `/api/v1/search?q=${encodeURIComponent(term)}&size=20&after=${encodeURIComponent(body.after)}`,
      null,
      { name: 'search.query.page2' }
    );
    check(page2, { 'search.query.page2 200': (r) => r.status === 200 });
  }
}

function suggest(term) {
  const prefix = term.slice(0, Math.max(2, Math.min(term.length, 3)));
  const res = api('GET', `/api/v1/search/suggest?prefix=${encodeURIComponent(prefix)}&size=10`, null, { name: 'search.suggest' });
  check(res, {
    'search.suggest 200': (r) => r.status === 200,
    'search.suggest object': (r) => r.status === 200 && typeof r.json() === 'object',
  });
}

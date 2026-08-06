// S1 auth：登录 / 我的信息 / 刷新令牌
// 运行: k6 run -e VUS=300 scripts/auth.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, PASSWORD, currentUser, api, refreshToken, buildOptions, forceRelogin } from './common.js';

export const options = buildOptions();

export default function () {
  const u = currentUser();
  const r = Math.random();

  if (r < 0.5) {
    // 登录（密码登录，种子用户专用）
    const res = http.post(
      `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ identifierType: 'PHONE', identifier: u.phone, password: PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'auth.login' } }
    );
    check(res, {
      'auth.login 200': (r) => r.status === 200,
      'auth.login has token': (r) => r.json().token !== undefined && r.json().token.accessToken !== undefined,
    });
  } else if (r < 0.9) {
    // /me（走 api()，内部会先登录一次并缓存 token）
    const res = api('GET', '/api/v1/auth/me');
    check(res, {
      'auth.me 200': (r) => r.status === 200,
      'auth.me has user': (r) => r.json().user !== undefined || r.json().id !== undefined,
    });
  } else {
    // 先确保本 VU 已登录并持有 refresh token；刷新成功后强制下轮重登，避免复用已轮换失效的旧 token。
    api('GET', '/api/v1/auth/me');
    const res = http.post(
      `${BASE_URL}/api/v1/auth/token/refresh`,
      JSON.stringify({ refreshToken: refreshToken() }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'auth.token.refresh' } }
    );
    check(res, { 'auth.token.refresh 200': (r) => r.status === 200 });
    forceRelogin();
  }
}

// S7 钱包：余额 / 流水 / 内容奖励配置
// 注：钱包行锁（SELECT ... FOR UPDATE）的并发压力主要来自推广保证金授权；WebSocket 出价主阶段不写钱包。
// 本脚本覆盖读路径；纯并发扣减压测可调高 VUS 观察 P99。
// 运行: k6 run -e VUS=300 scripts/wallet.js
import { check } from 'k6';
import { api, buildOptions } from './common.js';

export const options = buildOptions();

export default function () {
  const r = Math.random();
  if (r < 0.6) {
    me();
  } else if (r < 0.9) {
    ledger();
  } else {
    rewardConfig();
  }
}

function me() {
  const res = api('GET', '/api/v1/wallet/me', null, { name: 'wallet.me' });
  check(res, {
    'wallet.me 200': (r) => r.status === 200,
    'wallet.me balance fields': (r) => r.status === 200 && r.json().availableBalance !== undefined && r.json().heldBalance !== undefined && r.json().escrowedBalance !== undefined,
  });
}

function ledger() {
  const res = api('GET', '/api/v1/wallet/me/ledger?page=1&pageSize=20', null, { name: 'wallet.ledger' });
  check(res, {
    'wallet.ledger 200': (r) => r.status === 200,
    'wallet.ledger object': (r) => typeof r.json() === 'object',
  });
}

function rewardConfig() {
  const res = api('GET', '/api/v1/content-reward/config', null, { name: 'wallet.reward.config' });
  check(res, { 'wallet.reward.config 200': (r) => r.status === 200 });
}

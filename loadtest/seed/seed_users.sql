-- 压测种子用户 + 钱包账户（幂等：INSERT IGNORE，按主键去重，可重复执行）
-- 生成 __USER_N__ 个用户：ID 从 __USER_ID_BASE__ 起；手机号 = '139' + 8 位序号（11 位）；
-- 密码统一 Loadtest@123（BCrypt 哈希 __BCRYPT__，由 gen_password_hash.mjs 生成后回填）。
-- 钱包余额 1,000,000（竞价 HOLD 场景需要）。
-- 注：注册验证码只进服务端日志，自动化注册不可行，故 SQL 直插 + 密码登录。

SET @user_n := __USER_N__;
SET @id_base := __USER_ID_BASE__;
SET @pwd := '__BCRYPT__';

-- 用户
INSERT IGNORE INTO users (id, phone, email, password_hash, nickname, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS i
  UNION ALL
  SELECT i + 1 FROM seq WHERE i < @user_n
)
SELECT
  @id_base + i,
  CONCAT('139', LPAD(i, 8, '0')),
  CONCAT('lt', i, '@loadtest.local'),
  @pwd,
  CONCAT('loadtest_', i),
  NOW(3),
  NOW(3)
FROM seq;

-- 钱包账户
INSERT IGNORE INTO wallet_account (owner_user_id, available_balance, held_balance, escrowed_balance, status, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS i
  UNION ALL
  SELECT i + 1 FROM seq WHERE i < @user_n
)
SELECT
  @id_base + i,
  1000000,
  0,
  0,
  'ACTIVE',
  NOW(3),
  NOW(3)
FROM seq;

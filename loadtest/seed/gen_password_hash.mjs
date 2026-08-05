// 生成种子用户密码的 BCrypt 哈希（与 Spring Security BCryptPasswordEncoder 兼容，$2b$ 前缀可用）
// 用法: node gen_password_hash.mjs [password]    默认 Loadtest@123
// 依赖: 本目录已安装 bcryptjs（npm i bcryptjs）
import bcrypt from 'bcryptjs';

const pwd = process.argv[2] || 'Loadtest@123';
const hash = bcrypt.hashSync(pwd, 10);
console.log(hash);
console.error(`password=${pwd} verify=${bcrypt.compareSync(pwd, hash)}`);

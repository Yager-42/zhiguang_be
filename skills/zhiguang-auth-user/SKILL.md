---
name: zhiguang-auth-user
description: zhiguang 身份与用户域 Skill。用于处理登录、注册、验证码、token、当前用户、权限、用户资料和头像上传；当需求命中 `AuthController`、`ProfileController`、JWT、验证码、refresh token、`/api/v1/auth/**` 或 `/api/v1/profile` 时使用。
---

# zhiguang-auth-user

## 使用顺序

- 先看 `references/identity-and-profile.md`，确认身份对象、token 边界和 profile 归属。
- 再定位到 `AuthController` 或 `ProfileController`。
- 若变更同时影响 wallet、notification、content 发布侧归属，切 `zhiguang-change-playbook`。

## 必守约束

- 先分清“认证态”和“资料态”，不要把 profile 逻辑混进 token 逻辑。
- 当前用户提取、权限校验、验证码流程先核真相源，再动接口。
- JWT / refresh token / 验证码 / 资料补丁是不同契约，不做字段混用。

## 参考资料

- `references/identity-and-profile.md`

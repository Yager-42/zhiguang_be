# 需求描述到模块的映射

- “登录 / 注册 / 验证码 / 刷新 token / 当前用户 / 头像 / profile”
  - `zhiguang-auth-user`
- “发帖 / 草稿 / 发布 / 可见性 / feed / 详情 / 搜索 / 推荐 / 内容上传”
  - `zhiguang-content-domain`
- “关注 / 取关 / 评论 / 回复 / 点赞 / 收藏 / 未读通知 / 计数”
  - `zhiguang-social-domain`
- “竞价 / 推广位 / wallet / escrow / moderation / reconciliation / repair / rerun”
  - `zhiguang-platform-domain`
- “ErrorCode / BusinessException / 全局异常 / ID / 配置 / 线程池 / ES / Redis / Kafka 基础接线”
  - `zhiguang-common-runtime`
- “同时改 DTO + 事件 + 缓存 + 搜索 / 推荐 / 通知”
  - `zhiguang-change-playbook`
- “消息重复、状态不一致、恢复失败、补偿、重试、重建”
  - `zhiguang-debug-playbook`

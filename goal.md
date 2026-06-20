  $ponytail $subagent-driven-development $verification-before-completion $receiving-code-review

  你要实现 docs/superpowers/plans/2026-06-15-add-data-reconciliation.md。

  硬规则：
  - 只以当前 plan 和 active OpenSpec 为准：
    - openspec/changes/add-data-reconciliation/proposal.md
    - openspec/changes/add-data-reconciliation/design.md
    - openspec/changes/add-data-reconciliation/specs/data-reconciliation/spec.md
    - openspec/changes/add-data-reconciliation/tasks.md
    - openspec/11pdf-integration-matrix.md
  - 使用 ponytail：最小可工作的实现，优先现有代码模式、已有依赖、标准库/平台能力；不要引入 speculative abstractions。
  - 不要直接开始大包实现。先读 plan 和 active OpenSpec，按 plan 的 Task 1-7 拆 chunk。
  - 每个 chunk 必须走 subagent-driven-development：
    1. 开一个实现 subagent 实现当前 chunk。
    2. 实现 subagent 必须在 ponytail 指导下工作：少文件、少抽象、少新依赖，只做当前 chunk 必需内容。
    3. 实现 subagent 自己跑该 chunk 对应的最小测试。
    4. 实现完成后，开新的 review subagent 做 spec compliance review，只对照当前 plan + active OpenSpec + 当前代码。
    5. 如果 review 有 blocker/major/minor，回到实现 subagent 或新 fixer subagent 修，修完再开新 review subagent。
    6. 反复直到该 chunk review 无 findings。
    7. 再进入下一个 chunk。
  - 每个 review subagent 都必须是新 subagent，不能让实现者自审代替 review。
  - reviewer 发现的问题要按 receiving-code-review 处理：先核实是否符合 active OpenSpec 和代码现实，再修；不要盲从，也不要表演式赞同。
  - 每个 chunk 的完成声明必须有 fresh verification evidence。不要说“应该可以”。
  - 最后全部 chunk 完成后，开一个 final review subagent 审整个实现；无 findings 后再跑总验证：
    - mvn -Dtest="*Reconciliation*" test
    - mvn test
    - openspec status --change "add-data-reconciliation" --json
    - openspec validate add-data-reconciliation --strict（如果支持）
  - 如果某个命令因环境/依赖不可用失败，报告真实失败和阻塞点，不要声称通过。

  执行建议：
  1. 先输出你读到的 chunk 列表和每个 chunk 的验证命令。
  2. 从 Task 1 开始派 subagent。
  3. 严格保持每次只有一个实现 chunk 在写代码，避免 subagent 写同一批文件冲突。
  4. 不要修改 OpenSpec 文档，除非用户明确要求。
  5. 不要改 `.gitignore` 或无关文件。
  6. 实现过程中如果遇到已有未提交改动，先识别是否相关；无关则不要碰，相关则在回复中说明并谨慎合并。

  特别注意当前 plan 的关键要求：
  - `reconciliation_task` 是唯一任务事实源；不要再引入 Kafka 作为任务事实源，也不要做双写事实源。
  - `reconciliation_task`、`reconciliation_checkpoint`、`reconciliation_error_log` 都落 MySQL；状态机必须清晰可追踪。
  - 调度只处理 `status='pending'` 且 `next_execute_at <= NOW()` 的任务；执行前必须先抢 Redis 锁 `recon:lock:{taskId}`，再做 `pending -> running` CAS。
  - 失败重试节奏必须严格是 1、2、4、8、16 分钟；超过上限进入 `dead` 并写 `reconciliation_error_log`。
  - `dead -> pending` 的手动 retry 必须清理 retry 状态并把 `next_execute_at` 重置到当前可执行。
  - 必须实现卡住的 `running` 任务回收：扫描 `updated_at` 过旧的 running 任务并重置为 `pending`，而且 retry_count 不变。
  - `IdService` 必须使用 `com.tongji.common.id.IdService` 和 `com.tongji.common.id.IdNamespace.RECONCILIATION_TASK`；不要自造发号逻辑。
  - concrete reconciler 至少覆盖 ES、RAG、Cassandra text；Gorse/feed/comment count 等 repairer 只有在当前模块已经存在且依赖稳定时再注册，否则保持未注册并依赖 dead/error 行为。
  - `cassandra_text` for post 只在存在可恢复源时修；`cassandra_text` for comment 若无 source event 或 body，必须记录 dead/operator-visible error，不要伪造正文。
  - failure hooks 只给派生失败建补偿任务；关键 publish fact failure 不能走 reconciliation，必须仍由 publish attempt 状态机兜底。
  - stuck `publishing` post 的扫描必须遵守 publish attempt 语义：只标记失败或暴露重试入口，绝不能直接把帖子改成 published。
  - `createTaskIfAbsent` 去重语义只把 `pending` 和 `running` 视为 active；不要把 `succeeded` / `dead` 误判成活跃任务。
  - 可以在 docker 里部署对应中间件来完成测试，但如果环境不通，必须如实报告，不要伪称通过。

  $ponytail $subagent-driven-development $verification-before-completion $receiving-code-review                                                                     
                                                                                                                                                                    
  你要实现 docs/superpowers/plans/2026-06-15-add-recommendation-and-follow-feed.md。                                                                                
                                                                                                                                                                    
  硬规则：                                                                                                                                                          
  - 只以当前 plan 和 active OpenSpec 为准：                                                                                                                         
    - openspec/changes/add-recommendation-and-follow-feed/proposal.md                                                                                               
    - openspec/changes/add-recommendation-and-follow-feed/design.md                                                                                                 
    - openspec/changes/add-recommendation-and-follow-feed/specs/recommendation-feed/spec.md                                                                         
    - openspec/changes/add-recommendation-and-follow-feed/tasks.md                                                                                                  
  - 完全忽略 11pdf / openspec/11pdf-integration-matrix.md，不要读取、引用或按它改语义。                                                                             
  - 使用 ponytail：最小可工作的实现，优先现有代码模式、已有依赖、标准库/平台能力；不要引入 speculative abstractions。                                               
  - 不要直接开始大包实现。先读 plan 和 active OpenSpec，按 plan 的 Task 1-6 拆 chunk。                                                                              
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
    - mvn -Dtest="*Recommendation*,*FollowFeed*,*HomeFeed*" test                                                                                                    
    - mvn test                                                                                                                                                      
    - openspec status --change "add-recommendation-and-follow-feed" --json                                                                                          
    - openspec validate add-recommendation-and-follow-feed --strict（如果支持）                                                                                     
  - 如果某个命令因环境/依赖不可用失败，报告真实失败和阻塞点，不要声称通过。                                                                                         
                                                                                                                                                                    
  执行建议：                                                                                                                                                        
  1. 先输出你读到的 chunk 列表和每个 chunk 的验证命令。                                                                                                             
  2. 从 Task 1 开始派 subagent。
  3. 严格保持每次只有一个实现 chunk 在写代码，避免 subagent 写同一批文件冲突。                                                                                      
  4. 不要修改 OpenSpec 文档，除非用户明确要求。                                                                                                                     
  5. 不要改 `.gitignore` 或无关文件。                                                                                                                               
  6. 实现过程中如果遇到已有未提交改动，先识别是否相关；无关则不要碰，相关则在回复中说明并谨慎合并。                                                                 
                                                                                                                                                                    
  特别注意当前 plan 的关键要求：                                                                                                                                    
  - `feed.home.mixed-enabled` 默认 false，控制 authenticated `/api/v1/knowposts/feed` 是否切 mixed home feed；flag off/anonymous 保持 public/hot page-size          
  fallback。                                                                                                                                                        
  - mixed home feed 固定目标 20 条，不是任意 page size。                                                                                                            
  - `/api/v1/knowposts/feed/follow` 使用 cursor，`FeedPageResponse.nextCursor` 为 nullable String，格式 `<publishTsMillis>:<contentId>`；非法 cursor 返回           
  BAD_REQUEST。
  - follow source hydration 允许 `visible IN ('public','followers')`；recommendation/hot fallback 只允许 public。                                                   
  - normal author 只写 `feed_inbox`，不写 `feed_author_feed`；large author 只写 `feed_author_feed`，读路径从 followed large authors 拉取。                          
  - `feed:timeline:{userId}` 不做 single-flight；`feed:author:{authorId}:head` 是共享 large-author key，必须 single-populated/shared。                              
  - 不创建 Spring Data Cassandra entity/repository；feed 表用 direct CqlSession prepared statements + executeAsync。                                                
  - 不引入 `reconciliation_task` 或 `ReconciliationService`。                                                                                                       
  - 反馈来源：`counter-events` 点赞/收藏，`comment-feedback` 评论，`canal-outbox` 的 FollowCreated/FollowCanceled 关注事件。 
  
  可以在wsl的docker里面安装对应中间件来完成测试
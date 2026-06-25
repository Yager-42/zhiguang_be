# Trellis + Aegis 本地工作流

这个仓库把 Aegis vendored 到项目里，用来承接 Trellis 的执行阶段。

## 角色分工

- Trellis 负责：任务治理、`prd.md`、`design.md`、`implement.md`、`.trellis/spec/`、`trellis-check`、`trellis-update-spec`、提交与收尾。
- Aegis 负责：执行方法、调试方法、TDD 路由、执行层验证。

默认融合流：

```text
Trellis planning
-> trellis-before-dev
-> trellis-aegis-execution
-> Aegis execution
-> trellis-check
-> trellis-update-spec
-> commit
-> trellis-finish-work
```

## Clone 后首次接入

前提：

- 当前宿主是 Codex
- 项目已经被 Codex 设为 trusted project
- 用户级 Codex 已开启 hooks

执行：

```powershell
powershell -ExecutionPolicy Bypass -File .codex/scripts/setup-aegis.ps1
```

这个脚本会做三件事：

1. 在仓库内重写 `.codex/aegis-config.toml`
2. 让 `.codex/skills/` 指向 `.codex/aegis/skills/`
3. 不碰用户目录下的 Aegis 配置和注册表

## 完成判定

只有当脚本内部运行的 doctor JSON 同时满足以下条件，才算安装完成：

- `"ok": true`
- `"workspaceSupport": "available"`
- `"configStatus": "configured"`

而且 discovery root 必须指向当前仓库里的 `.codex/skills/`。

这个自定义路线明确不依赖：

- `~/.config/aegis/config.toml`
- `~/.config/aegis/installations.json`

## 重启要求

首次执行完 `setup-aegis.ps1` 后，需要重启一次 Codex。

后续如果你更新了仓库里 vendored 的 Aegis skills 或 `.codex/hooks.json` / `.codex/config.toml`，也需要重启 Codex 重新加载。

## 本地配置策略

这里已经不是官方默认路线。

当前项目采用的是 repo-only 自定义路线：

- 运行配置：`.codex/aegis-config.toml`

这个配置文件放在仓库内，方便一把 clone 下来直接体验当前工作流。

注意：

- 这两个文件会包含当前机器的绝对路径
- 这意味着它们适合“当前 clone 的当前机器”，不适合跨机器复用
- 新机器 clone 后，应重新运行 `.codex/scripts/setup-aegis.ps1`
- 如果想做完整 doctor 校验，可手动从 `.codex/aegis/` 运行 doctor，并显式传 `--config ../aegis-config.toml --discovery-root ../skills`

## 项目内 Aegis 产物

在这个融合模式里，只保留两类 Aegis 持久化产物：

- `docs/aegis/plans/`
- `docs/aegis/work/`

约束：

- `implement.md` 仍然是 Trellis 顶层权威计划
- `docs/aegis/plans/` 只是当前 `implement.md` 步骤的执行层细化
- `docs/aegis/work/` 按 Aegis 自己的 evidence / checkpoint / drift 规则记录
- 默认不启用 `docs/aegis/specs/`、`docs/aegis/baseline/`、`docs/aegis/adr/`

## 更新方式

这个仓库里的 Aegis 是 vendored 副本，不是独立 git checkout。

所以这里不要把 `aegis:update` 理解成“去 `.codex/aegis` 里自己 git pull”。正确更新方式是：

1. 更新项目仓库本身
2. 重新运行 `.codex/scripts/setup-aegis.ps1`
3. 重启 Codex

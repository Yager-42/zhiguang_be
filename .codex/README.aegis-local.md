# Aegis Local Notes

这个仓库走的是**项目内 vendored Aegis**路线，不是官方默认全局安装路线。

## 仓库内关键位置

- Aegis method-pack：`.codex/aegis/`
- 项目内配置：`.codex/aegis-config.toml`
- Codex 可见 skills：`.codex/skills/`
- 本地初始化脚本：`.codex/scripts/setup-aegis.ps1`

## Clone 后要改什么

新机器 clone 下来后，至少要保证下面两个路径指向你自己的本地仓库绝对路径：

- `.codex/aegis-config.toml` 里的 `method_pack_root`
- `.codex/aegis-config.toml` 里的 `workspace_helper`

最简单做法不是手改，而是直接运行：

```powershell
powershell -ExecutionPolicy Bypass -File .codex/scripts/setup-aegis.ps1
```

这个脚本只做项目内动作：

1. 重写 `.codex/aegis-config.toml`
2. 让 `.codex/skills/` 指向 `.codex/aegis/skills/`
3. 不写 `~/.config/aegis/*`
4. 不写用户级 Aegis 注册表

## 如果你非要手改

编辑 `.codex/aegis-config.toml`，把里面旧机器路径替换成你当前 clone 的绝对路径。

然后确保 `.codex/skills/` 下这些目录实际指向 `.codex/aegis/skills/` 对应目录。

## 重启要求

改完配置或 skills 暴露后，重启 Codex。

## 可选手动验证

从 vendored method-pack 根目录运行：

```powershell
cd .codex/aegis
python scripts/aegis-doctor.py --write-config --config ../aegis-config.toml --discovery-root ../skills --json
```

只在 JSON 同时包含以下字段时，才算当前仓库配置有效：

- `"ok": true`
- `"workspaceSupport": "available"`
- `"configStatus": "configured"`

## 更新约定

这里的 Aegis 更新方式不是去 `.codex/aegis` 单独 `git pull`。

正确方式是：

1. 更新整个项目仓库
2. 重新运行 `.codex/scripts/setup-aegis.ps1`
3. 重启 Codex

from __future__ import annotations

import argparse
import datetime as dt
import glob
import hashlib
import json
import re
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import yaml

ROOT = Path(__file__).resolve().parents[2]
SRC_ROOT = ROOT / "src" / "main" / "java" / "com" / "tongji"
RESOURCE_ROOT = ROOT / "src" / "main" / "resources"
REGISTRY_PATH = ROOT / "scripts" / "skills" / "skill_registry.yaml"
HTTP_METHOD_TOKENS = ["GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping"]
METHOD_RE = re.compile(r"\bpublic\b.*?\b([A-Za-z0-9_]+)\s*\(")
STRING_RE = re.compile(r'"([^"]+)"')
PROPERTIES_PREFIX_RE = re.compile(r'@ConfigurationProperties\(prefix\s*=\s*"([^"]+)"\)')
ENUM_RE = re.compile(r"\benum\s+([A-Za-z0-9_]+)\s*\{")
TOP_KEY_RE = re.compile(r"^([A-Za-z0-9_.-]+):")

sys.path.insert(0, str(ROOT / ".trellis" / "scripts"))
from common.paths import get_current_task_abs, get_repo_root  # type: ignore  # noqa: E402


@dataclass(frozen=True)
class AssetDefinition:
    asset_id: str
    skill: str
    kind: str
    script: Path
    output: Path
    source_inputs: list[str]
    refresh_trigger: list[str]
    manual_boundary: str


@dataclass(frozen=True)
class ControllerRoute:
    module: str
    skill: str
    file: str
    controller: str
    mapping: str
    method: str
    auth: str
    note: str


@dataclass(frozen=True)
class EnumFact:
    module: str
    file: str
    enum_name: str
    constants: list[str]


@dataclass(frozen=True)
class PropertyBinding:
    module: str
    file: str
    class_name: str
    prefix: str
    owner_skill: str


def utc_now() -> str:
    return dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def load_registry() -> dict[str, Any]:
    return yaml.safe_load(REGISTRY_PATH.read_text(encoding="utf-8")) or {}


def rel(path: Path) -> str:
    return path.resolve().relative_to(ROOT).as_posix()


def gather_files(patterns: Iterable[str]) -> list[Path]:
    results: set[Path] = set()
    for pattern in patterns:
        for matched in glob.glob(str(ROOT / pattern), recursive=True):
            path = Path(matched)
            if path.is_file():
                results.add(path.resolve())
    return sorted(results)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def sha256_files(paths: Iterable[Path]) -> str:
    digest = hashlib.sha256()
    for path in sorted({p.resolve() for p in paths}):
        digest.update(rel(path).encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def parse_generated_metadata(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8")
    match = re.search(r"```yaml\n(.*?)\n```", text, re.DOTALL)
    if not match:
        return {}
    data = yaml.safe_load(match.group(1)) or {}
    return data.get("asset_metadata") or {}


def render_generated_markdown(*, title: str, intro: str, asset: AssetDefinition, matched_files: list[Path], source_hash: str, body_lines: list[str]) -> str:
    metadata = {
        "asset_metadata": {
            "asset_id": asset.asset_id,
            "skill": asset.skill,
            "generated_at": utc_now(),
            "generator": f"python {rel(ROOT / 'scripts' / 'skills' / 'refresh_generated_knowledge.py')} --asset {asset.asset_id}",
            "source_inputs": asset.source_inputs,
            "matched_files_count": len(matched_files),
            "source_hash": source_hash,
            "refresh_trigger": asset.refresh_trigger,
            "manual_boundary": asset.manual_boundary,
        }
    }
    parts = [title, "", intro, "", "```yaml", yaml.safe_dump(metadata, allow_unicode=True, sort_keys=False).strip(), "```", ""]
    parts.extend(body_lines)
    return "\n".join(parts).rstrip() + "\n"


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def write_text(path: Path, content: str) -> None:
    ensure_parent(path)
    path.write_text(content, encoding="utf-8")


def skill_package_map(registry: dict[str, Any]) -> dict[str, str]:
    mapping: dict[str, str] = {}
    for skill, meta in (registry.get("skills") or {}).items():
        for prefix in meta.get("package_prefixes") or []:
            mapping[str(prefix)] = skill
    return mapping


def config_owner_for_key(key: str, registry: dict[str, Any]) -> str:
    for skill, meta in (registry.get("skills") or {}).items():
        for prefix in meta.get("config_prefixes") or []:
            if key == prefix or key.startswith(prefix + "."):
                return skill
    return "zhiguang-common-runtime"


def skill_for_java_path(path: Path, registry: dict[str, Any]) -> str:
    relative_parts = path.resolve().relative_to(SRC_ROOT).parts
    top = relative_parts[0] if relative_parts else ""
    return skill_package_map(registry).get(top, "zhiguang-common-runtime")


def parse_path(annotation: str | None) -> str:
    if not annotation:
        return ""
    values = STRING_RE.findall(annotation)
    return values[0] if values else ""


def parse_controller_file(path: Path, registry: dict[str, Any]) -> list[ControllerRoute]:
    lines = path.read_text(encoding="utf-8").splitlines()
    module = path.relative_to(SRC_ROOT).parts[0]
    controller = path.stem
    skill = skill_for_java_path(path, registry)
    base_path = ""
    rows: list[ControllerRoute] = []
    pending_annotations: list[str] = []
    for index, raw_line in enumerate(lines):
        stripped = raw_line.strip()
        if stripped.startswith("@RequestMapping") and not base_path:
            base_path = parse_path(stripped)
        if stripped.startswith("@"):
            pending_annotations.append(stripped)
            continue
        method_match = METHOD_RE.search(raw_line)
        if method_match:
            method_name = method_match.group(1)
            mapping_name = ""
            mapping_path = ""
            joined = " ".join(pending_annotations)
            for token in HTTP_METHOD_TOKENS:
                if f"@{token}" in joined:
                    mapping_name = token.replace("Mapping", "").upper()
                    for annotation in reversed(pending_annotations):
                        if annotation.startswith(f"@{token}"):
                            mapping_path = parse_path(annotation)
                            break
                    break
            if mapping_name:
                full_path = base_path or ""
                if mapping_path:
                    full_path = full_path.rstrip("/") + "/" + mapping_path.lstrip("/") if full_path else mapping_path
                full_path = full_path or "-"
                local_window = " ".join(lines[max(0, index - 4): index + 4])
                auth = "需要登录" if "@AuthenticationPrincipal" in raw_line or "Jwt" in raw_line or "@AuthenticationPrincipal" in joined else "未显式声明"
                note = "accepted" if "HttpStatus.ACCEPTED" in local_window else ""
                rows.append(ControllerRoute(module, skill, rel(path), controller, f"{mapping_name} {full_path}", method_name, auth, note))
            pending_annotations = []
        elif stripped and not stripped.startswith("//") and not stripped.startswith("*"):
            pending_annotations = []
    return rows


def generate_api_index(asset: AssetDefinition, registry: dict[str, Any]) -> str:
    matched_files = gather_files(asset.source_inputs)
    rows: list[ControllerRoute] = []
    for path in matched_files:
        rows.extend(parse_controller_file(path, registry))
    rows.sort(key=lambda item: (item.module, item.controller, item.mapping, item.method))
    lines = ["| 模块 | Skill | 文件 | Controller | 映射 | 方法 | 显式鉴权 | 备注 |", "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for row in rows:
        lines.append(f"| `{row.module}` | `{row.skill}` | `{row.file}` | `{row.controller}` | `{row.mapping}` | `{row.method}` | {row.auth} | {row.note} |")
    return render_generated_markdown(title="# API 索引（自动生成）", intro="该文档从 `src/main/java/com/tongji/**/*Controller.java` 自动提取，用于快速定位接口入口、路由前缀和显式鉴权点。改 controller 后请重新刷新生成资产。", asset=asset, matched_files=matched_files, source_hash=sha256_files(matched_files), body_lines=lines)


def generate_routing_map(asset: AssetDefinition, registry: dict[str, Any]) -> str:
    matched_files = gather_files(asset.source_inputs)
    lines = ["| Skill | package prefixes | config prefixes | manual targets | generated assets |", "| --- | --- | --- | --- | --- |"]
    for skill, meta in (registry.get("skills") or {}).items():
        manual_targets = ", ".join(f"{key}:{value}" for key, value in (meta.get("manual_targets") or {}).items()) or "-"
        generated_assets = ", ".join(meta.get("generated_assets") or []) or "-"
        package_prefixes = ", ".join(meta.get("package_prefixes") or []) or "-"
        config_prefixes = ", ".join(meta.get("config_prefixes") or []) or "-"
        lines.append(f"| `{skill}` | `{package_prefixes}` | `{config_prefixes}` | `{manual_targets}` | `{generated_assets}` |")
    return render_generated_markdown(title="# Skill 路由映射（自动生成）", intro="该文档从 `scripts/skills/skill_registry.yaml` 和各 `SKILL.md` 的边界约定自动生成，用于 child B / child C 共享同一套 ownership 输入。", asset=asset, matched_files=matched_files, source_hash=sha256_files(matched_files), body_lines=lines)


def flatten_yaml(data: Any, prefix: str = "") -> dict[str, Any]:
    result: dict[str, Any] = {}
    if isinstance(data, dict):
        for key, value in data.items():
            next_prefix = f"{prefix}.{key}" if prefix else str(key)
            result.update(flatten_yaml(value, next_prefix))
    elif isinstance(data, list):
        result[prefix] = " | ".join(yaml.safe_dump(item, allow_unicode=True, sort_keys=False).strip() if isinstance(item, (dict, list)) else str(item) for item in data)
    else:
        result[prefix] = data
    return result


def parse_property_bindings(registry: dict[str, Any]) -> list[PropertyBinding]:
    bindings: list[PropertyBinding] = []
    for path in SRC_ROOT.rglob("*Properties.java"):
        text = path.read_text(encoding="utf-8")
        match = PROPERTIES_PREFIX_RE.search(text)
        if not match:
            continue
        prefix = match.group(1)
        module = path.relative_to(SRC_ROOT).parts[0]
        bindings.append(PropertyBinding(module, rel(path), path.stem, prefix, config_owner_for_key(prefix, registry)))
    bindings.sort(key=lambda item: (item.owner_skill, item.prefix, item.class_name))
    return bindings


def generate_config_index(asset: AssetDefinition, registry: dict[str, Any]) -> str:
    matched_files = gather_files(asset.source_inputs)
    application = yaml.safe_load((RESOURCE_ROOT / "application.yml").read_text(encoding="utf-8")) or {}
    flattened = flatten_yaml(application)
    bindings = parse_property_bindings(registry)
    lines = ["## 配置绑定类", "", "| owner skill | prefix | class | file |", "| --- | --- | --- | --- |"]
    for binding in bindings:
        lines.append(f"| `{binding.owner_skill}` | `{binding.prefix}` | `{binding.class_name}` | `{binding.file}` |")
    lines.extend(["", "## application.yml 关键配置", "", "| key | value | owner skill |", "| --- | --- | --- |"])
    for key in sorted(flattened.keys()):
        owner = config_owner_for_key(key, registry)
        value = str(flattened[key]).replace("\n", "<br>")
        lines.append(f"| `{key}` | `{value}` | `{owner}` |")
    return render_generated_markdown(title="# 配置索引（自动生成）", intro="该文档从 `src/main/resources/application.yml` 和 `@ConfigurationProperties` 绑定类自动提取，用于确认真实配置键、默认值和 owner skill。改配置后请重新刷新生成资产。", asset=asset, matched_files=matched_files, source_hash=sha256_files(matched_files), body_lines=lines)


def parse_enum_file(path: Path) -> EnumFact | None:
    text = path.read_text(encoding="utf-8")
    match = ENUM_RE.search(text)
    if not match:
        return None
    tail = text[match.end():]
    constant_region = tail.split(";", 1)[0]
    constants: list[str] = []
    for raw in constant_region.splitlines():
        token = raw.strip().rstrip(",").split("(", 1)[0].split(" ", 1)[0].strip().rstrip(",")
        if re.fullmatch(r"[A-Z0-9_]+", token):
            constants.append(token)
    if not constants:
        return None
    module = path.relative_to(SRC_ROOT).parts[0]
    return EnumFact(module, rel(path), match.group(1), constants)


def generate_enum_index(asset: AssetDefinition, registry: dict[str, Any]) -> str:
    matched_files = gather_files(asset.source_inputs)
    facts: list[EnumFact] = []
    for path in matched_files:
        if not path.name.endswith(".java"):
            continue
        fact = parse_enum_file(path)
        if fact:
            facts.append(fact)
    facts.sort(key=lambda item: (item.module, item.enum_name))
    lines = ["| 模块 | 文件 | enum | 常量 |", "| --- | --- | --- | --- |"]
    for fact in facts:
        constants = ", ".join(f"`{item}`" for item in fact.constants)
        lines.append(f"| `{fact.module}` | `{fact.file}` | `{fact.enum_name}` | {constants} |")
    return render_generated_markdown(title="# 平台域枚举索引（自动生成）", intro="该文档从 `promotion` / `wallet` / `moderation` / `reconciliation` 包下的 enum 自动提取，用于标记高漂移的状态常量与流程字面量。改 enum 后请重新刷新生成资产。", asset=asset, matched_files=matched_files, source_hash=sha256_files(matched_files), body_lines=lines)


def get_asset_definition(asset_id: str, registry: dict[str, Any] | None = None) -> AssetDefinition:
    registry = registry or load_registry()
    meta = (registry.get("generated_assets") or {}).get(asset_id)
    if not meta:
        raise KeyError(f"Unknown asset id: {asset_id}")
    return AssetDefinition(asset_id, meta["skill"], meta["kind"], ROOT / meta["script"], ROOT / meta["output"], list(meta.get("source_inputs") or []), list(meta.get("refresh_trigger") or []), meta.get("manual_boundary", ""))


def all_asset_definitions(registry: dict[str, Any] | None = None) -> list[AssetDefinition]:
    registry = registry or load_registry()
    return [get_asset_definition(asset_id, registry) for asset_id in (registry.get("generated_assets") or {}).keys()]


def generate_asset(asset_id: str, registry: dict[str, Any] | None = None) -> Path:
    registry = registry or load_registry()
    asset = get_asset_definition(asset_id, registry)
    if asset.kind == "api_index":
        content = generate_api_index(asset, registry)
    elif asset.kind == "routing_map":
        content = generate_routing_map(asset, registry)
    elif asset.kind == "config_index":
        content = generate_config_index(asset, registry)
    elif asset.kind == "enum_index":
        content = generate_enum_index(asset, registry)
    else:
        raise ValueError(f"Unsupported asset kind: {asset.kind}")
    write_text(asset.output, content)
    return asset.output


def asset_source_hash(asset_id: str, registry: dict[str, Any] | None = None) -> str:
    registry = registry or load_registry()
    asset = get_asset_definition(asset_id, registry)
    return sha256_files(gather_files(asset.source_inputs))


def asset_is_fresh(asset_id: str, registry: dict[str, Any] | None = None) -> bool:
    registry = registry or load_registry()
    asset = get_asset_definition(asset_id, registry)
    metadata = parse_generated_metadata(asset.output)
    return bool(metadata and asset.output.exists() and metadata.get("source_hash") == asset_source_hash(asset_id, registry))


def validate_skill_references(skill: str) -> list[str]:
    skill_dir = ROOT / "skills" / skill
    issues: list[str] = []
    if not (skill_dir / "SKILL.md").exists():
        return [f"{skill}: missing SKILL.md"]
    text = (skill_dir / "SKILL.md").read_text(encoding="utf-8")
    for match in re.findall(r"`((?:references|scripts)/[^`]+)`", text):
        candidate = skill_dir / Path(match)
        if not candidate.exists():
            issues.append(f"{skill}: missing referenced file {rel(candidate)}")
    return issues


def git_changed_files(base_ref: str = "HEAD") -> list[str]:
    result = subprocess.run(["git", "diff", "--name-only", base_ref, "--"], cwd=ROOT, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git diff failed")
    return sorted(dict.fromkeys(line.strip().replace("\\", "/") for line in result.stdout.splitlines() if line.strip()))


def git_diff_for_file(path: str, base_ref: str = "HEAD") -> str:
    result = subprocess.run(["git", "diff", "-U0", base_ref, "--", path], cwd=ROOT, capture_output=True, text=True, check=False)
    return result.stdout if result.returncode == 0 else ""


def application_diff_top_keys(base_ref: str = "HEAD") -> list[str]:
    diff = git_diff_for_file("src/main/resources/application.yml", base_ref)
    keys: list[str] = []
    for line in diff.splitlines():
        if not line or line.startswith(("+++", "---", "@@")) or line[0] not in "+-":
            continue
        candidate = line[1:].strip()
        match = TOP_KEY_RE.match(candidate)
        if match:
            keys.append(match.group(1))
    return sorted(dict.fromkeys(keys))


def classify_changed_path(path: str, registry: dict[str, Any], base_ref: str = "HEAD") -> list[dict[str, Any]]:
    signals: list[dict[str, Any]] = []
    normalized = path.replace("\\", "/")
    if normalized.startswith("src/main/java/com/tongji/"):
        relative = Path(normalized).relative_to("src/main/java/com/tongji")
        package = relative.parts[0]
        primary_skill = skill_package_map(registry).get(package, "zhiguang-common-runtime")
        suffix = relative.name
        local_path = ROOT / normalized
        if suffix.endswith("Controller.java"):
            signals.append({"kind": "controller", "skill": primary_skill, "path": normalized, "symbol": Path(normalized).stem})
            signals.append({"kind": "controller", "skill": "zhiguang-repo-map", "path": normalized, "symbol": Path(normalized).stem})
        elif suffix.endswith("Properties.java") or "/config/" in normalized:
            signals.append({"kind": "config", "skill": primary_skill, "path": normalized, "symbol": Path(normalized).stem})
            if primary_skill != "zhiguang-common-runtime":
                signals.append({"kind": "config", "skill": "zhiguang-common-runtime", "path": normalized, "symbol": Path(normalized).stem})
        else:
            text = local_path.read_text(encoding="utf-8", errors="ignore") if local_path.exists() else ""
            if " enum " in text or text.strip().startswith("public enum") or text.strip().startswith("enum "):
                signals.append({"kind": "enum", "skill": primary_skill, "path": normalized, "symbol": Path(normalized).stem})
                signals.append({"kind": "enum", "skill": "zhiguang-business-dictionary", "path": normalized, "symbol": Path(normalized).stem})
    elif normalized == "src/main/resources/application.yml":
        top_keys = application_diff_top_keys(base_ref) or ["application.yml"]
        impacted_skills = {"zhiguang-common-runtime"}
        for key in top_keys:
            impacted_skills.add(config_owner_for_key(key, registry))
        for skill in sorted(impacted_skills):
            signals.append({"kind": "config", "skill": skill, "path": normalized, "symbol": ", ".join(top_keys)})
    elif normalized.startswith("skills/"):
        parts = Path(normalized).parts
        if len(parts) >= 2 and parts[1].startswith("zhiguang-"):
            signals.append({"kind": "skill-doc", "skill": parts[1], "path": normalized, "symbol": Path(normalized).name})
    return signals


def group_signals(changed_files: list[str], registry: dict[str, Any], base_ref: str = "HEAD") -> list[dict[str, Any]]:
    grouped: list[dict[str, Any]] = []
    for path in changed_files:
        grouped.extend(classify_changed_path(path, registry, base_ref))
    primary_business_skills = {item["skill"] for item in grouped if item["skill"] in {"zhiguang-auth-user", "zhiguang-content-domain", "zhiguang-social-domain", "zhiguang-platform-domain", "zhiguang-common-runtime"}}
    if len(primary_business_skills) > 1:
        grouped.append({"kind": "cross-domain", "skill": "zhiguang-change-playbook", "path": ", ".join(sorted(primary_business_skills)), "symbol": ", ".join(sorted(primary_business_skills))})
    return grouped


def task_dir_from_arg(task: str | None) -> Path:
    if not task or task == "current":
        current = get_current_task_abs(get_repo_root())
        if current is None:
            raise RuntimeError("No current Trellis task")
        return current
    task_path = Path(task)
    return task_path if task_path.is_absolute() else (ROOT / task_path)


def review_dir(task: str | None) -> Path:
    return task_dir_from_arg(task) / "skills-review"


def ensure_drift_section(path: Path, content: str) -> None:
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    header = "## Drift Notes"
    entry = content.rstrip() + "\n"
    if header not in existing:
        new_text = existing.rstrip() + "\n\n" + header + "\n\n" + entry
    else:
        head, tail = existing.split(header, 1)
        new_text = head.rstrip() + "\n\n" + header + tail.rstrip() + "\n\n" + entry
    path.write_text(new_text.rstrip() + "\n", encoding="utf-8")


def load_state(review_path: Path) -> dict[str, Any]:
    state_path = review_path / "state.json"
    return json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}


def save_state(review_path: Path, state: dict[str, Any]) -> None:
    write_text(review_path / "state.json", json.dumps(state, ensure_ascii=False, indent=2) + "\n")


def make_suggestion_id(skill: str, kind: str, evidence: list[str]) -> str:
    return f"SUG-{skill}-{kind}-{sha256_text('|'.join(sorted(evidence)))[:10]}"


def priority_for_kind(kind: str, evidence_count: int) -> str:
    if kind == "cross-domain":
        return "high"
    if kind == "config" and evidence_count > 1:
        return "high"
    if kind in {"controller", "enum", "config"}:
        return "medium"
    return "low"


def target_for_signal(skill: str, kind: str, registry: dict[str, Any]) -> str | None:
    manual_targets = ((registry.get("skills") or {}).get(skill) or {}).get("manual_targets") or {}
    if kind in manual_targets:
        return manual_targets[kind]
    if kind == "cross-domain":
        return manual_targets.get("review")
    return manual_targets.get("controller") or manual_targets.get("config") or manual_targets.get("enum")


def build_suggestions(signals: list[dict[str, Any]], registry: dict[str, Any]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, str, str | None], list[dict[str, Any]]] = defaultdict(list)
    for signal in signals:
        target = target_for_signal(signal["skill"], signal["kind"], registry)
        grouped[(signal["skill"], signal["kind"], target)].append(signal)
    suggestions: list[dict[str, Any]] = []
    for (skill, kind, target), items in grouped.items():
        if kind == "skill-doc" or not target:
            continue
        evidence = [item["path"] for item in items]
        suggestion_id = make_suggestion_id(skill, kind, evidence)
        symbols = sorted({item["symbol"] for item in items})
        title = {"controller": "入口/路由语义核对", "config": "配置契约核对", "enum": "状态/枚举语义核对", "cross-domain": "跨域联动检查"}.get(kind, "知识核对")
        content_lines = [f"### {dt.date.today().isoformat()} Drift Note / {suggestion_id}", f"- 触发类型：`{kind}`"]
        for item in items:
            content_lines.append(f"- 代码事实：`{item['symbol']}` 来自 `{item['path']}`")
        content_lines.append(f"- 建议动作：人工确认 `{Path(target).name}` 是否需要补入这批变更的最新语义。")
        suggestions.append({"id": suggestion_id, "skill": skill, "kind": kind, "priority": priority_for_kind(kind, len(items)), "status": "pending", "target_file": target, "mode": "drift-note", "title": title, "evidence": evidence, "symbols": symbols, "content": "\n".join(content_lines) + "\n"})
    suggestions.sort(key=lambda item: (item["priority"], item["skill"], item["kind"]))
    return suggestions


def recovery_markdown(changed_files: list[str], impacted_skills: list[str], asset_paths: list[str], hard_errors: list[str], suggestions: list[dict[str, Any]]) -> str:
    lines = ["# Skills Recovery Checkpoint", "", f"- generated_at: {utc_now()}", f"- changed_files: {len(changed_files)}", f"- impacted_skills: {', '.join(impacted_skills) if impacted_skills else '-'}", f"- generated_assets: {', '.join(asset_paths) if asset_paths else '-'}", "", "## Risks", "", f"- hard_errors: {len(hard_errors)}", f"- pending_suggestions: {len(suggestions)}", ""]
    if hard_errors:
        lines.extend(["## Hard Errors", ""])
        lines.extend(f"- {item}" for item in hard_errors)
        lines.append("")
    if suggestions:
        lines.extend(["## Suggestions", ""])
        lines.extend(f"- `{item['id']}` / `{item['priority']}` / `{item['skill']}` / {item['title']}" for item in suggestions)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def suggestions_markdown(*, base_ref: str, changed_files: list[str], impacted_skills: list[str], asset_paths: list[str], hard_errors: list[str], suggestions: list[dict[str, Any]]) -> str:
    lines = ["# Skills Diff Check", "", f"- generated_at: {utc_now()}", f"- base_ref: `{base_ref}`", f"- changed_files: {len(changed_files)}", f"- impacted_skills: {', '.join(impacted_skills) if impacted_skills else '-'}", f"- refreshed_assets: {', '.join(asset_paths) if asset_paths else '-'}", "", "## Changed Files", ""]
    lines.extend(f"- `{path}`" for path in changed_files)
    lines.extend(["", "## Hard Errors", ""])
    lines.extend((f"- [hard] {item}" for item in hard_errors) if hard_errors else ["- none"])
    lines.extend(["", "## Pending Suggestions", ""])
    if not suggestions:
        lines.append("- none")
    for item in suggestions:
        lines.extend([f"### `{item['id']}`", "", f"- skill: `{item['skill']}`", f"- priority: `{item['priority']}`", f"- title: {item['title']}", f"- target_file: `{item['target_file']}`", "", "```yaml", yaml.safe_dump(item, allow_unicode=True, sort_keys=False).strip(), "```", ""])
    lines.extend(["## Apply", "", "人工确认后再执行写回。示例：", "", "```bash", "python scripts/skills/skills_guard.py apply --task current --ids SUG-xxxx,SUG-yyyy", "```", ""])
    return "\n".join(lines).rstrip() + "\n"


def refresh_assets_for_skills(skills: set[str], registry: dict[str, Any], changed_only: bool = False) -> list[str]:
    refreshed: list[str] = []
    for asset in all_asset_definitions(registry):
        if asset.skill not in skills:
            continue
        if changed_only and asset_is_fresh(asset.asset_id, registry):
            continue
        refreshed.append(rel(generate_asset(asset.asset_id, registry)))
    return refreshed


def parse_suggestions_file(path: Path) -> list[dict[str, Any]]:
    text = path.read_text(encoding="utf-8")
    blocks = re.findall(r"```yaml\n(.*?)\n```", text, re.DOTALL)
    suggestions: list[dict[str, Any]] = []
    for block in blocks:
        loaded = yaml.safe_load(block) or {}
        if isinstance(loaded, dict) and loaded.get("id"):
            suggestions.append(loaded)
    return suggestions


def command_generate(asset_id: str) -> int:
    print(rel(generate_asset(asset_id)))
    return 0


def command_route(base_ref: str, explicit_paths: list[str] | None) -> int:
    registry = load_registry()
    changed_files = explicit_paths or git_changed_files(base_ref)
    signals = group_signals(changed_files, registry, base_ref)
    impacted = sorted({item["skill"] for item in signals})
    print(json.dumps({"changed_files": changed_files, "impacted_skills": impacted, "signals": signals}, ensure_ascii=False, indent=2))
    return 0


def command_diff_check(base_ref: str, task: str | None, explicit_paths: list[str] | None) -> int:
    registry = load_registry()
    changed_files = explicit_paths or git_changed_files(base_ref)
    review_path = review_dir(task)
    review_path.mkdir(parents=True, exist_ok=True)
    signals = group_signals(changed_files, registry, base_ref)
    impacted_skills = sorted({item["skill"] for item in signals})
    refreshed_assets = refresh_assets_for_skills(set(impacted_skills), registry, changed_only=True)
    hard_errors: list[str] = []
    if not changed_files:
        hard_errors.append("no changed files detected")
    for skill in impacted_skills:
        hard_errors.extend(validate_skill_references(skill))
        for asset_id in ((registry.get("skills") or {}).get(skill) or {}).get("generated_assets") or []:
            if not get_asset_definition(asset_id, registry).output.exists():
                hard_errors.append(f"{skill}: missing generated asset {asset_id}")
    suggestions = build_suggestions(signals, registry)
    all_relevant_paths = [ROOT / path for path in changed_files if (ROOT / path).exists()]
    for skill in impacted_skills:
        skill_dir = ROOT / "skills" / skill
        if skill_dir.exists():
            all_relevant_paths.extend(path for path in skill_dir.rglob("*.md"))
    for asset in all_asset_definitions(registry):
        if asset.skill in impacted_skills and asset.output.exists():
            all_relevant_paths.append(asset.output)
    current_hash = sha256_files(all_relevant_paths) if all_relevant_paths else sha256_text("empty")
    state = load_state(review_path)
    suggestion_path = review_path / "skills-suggestions.md"
    recovery_path = review_path / "context-recovery.md"
    if state.get("scan_hash") == current_hash and suggestion_path.exists() and recovery_path.exists():
        print(rel(suggestion_path))
        return 0
    write_text(suggestion_path, suggestions_markdown(base_ref=base_ref, changed_files=changed_files, impacted_skills=impacted_skills, asset_paths=refreshed_assets, hard_errors=hard_errors, suggestions=suggestions))
    write_text(recovery_path, recovery_markdown(changed_files, impacted_skills, refreshed_assets, hard_errors, suggestions))
    state.update({"generated_at": utc_now(), "base_ref": base_ref, "scan_hash": current_hash, "impacted_skills": impacted_skills, "refreshed_assets": refreshed_assets, "hard_errors": hard_errors, "pending_suggestion_ids": [item["id"] for item in suggestions], "high_priority_pending": [item["id"] for item in suggestions if item["priority"] == "high"], "applied_suggestion_ids": state.get("applied_suggestion_ids", [])})
    save_state(review_path, state)
    print(rel(suggestion_path))
    return 0 if not hard_errors else 2


def command_apply(task: str | None, ids: list[str]) -> int:
    review_path = review_dir(task)
    suggestion_path = review_path / "skills-suggestions.md"
    if not suggestion_path.exists():
        raise RuntimeError("suggestion file not found")
    state = load_state(review_path)
    suggestions = {item["id"]: item for item in parse_suggestions_file(suggestion_path)}
    applied: list[str] = state.get("applied_suggestion_ids", [])
    for suggestion_id in ids:
        item = suggestions.get(suggestion_id)
        if not item:
            raise RuntimeError(f"unknown suggestion id: {suggestion_id}")
        target = ROOT / item["target_file"]
        ensure_parent(target)
        ensure_drift_section(target, item["content"])
        if suggestion_id not in applied:
            applied.append(suggestion_id)
    state["applied_suggestion_ids"] = applied
    state["pending_suggestion_ids"] = [item for item in state.get("pending_suggestion_ids", []) if item not in ids]
    state["high_priority_pending"] = [item for item in state.get("high_priority_pending", []) if item not in ids]
    state["last_apply_at"] = utc_now()
    save_state(review_path, state)
    print(json.dumps({"applied": ids}, ensure_ascii=False))
    return 0


def command_status(task: str | None, fail_on_hard: bool, fail_on_high: bool) -> int:
    review_path = review_dir(task)
    state = load_state(review_path)
    hard_errors = state.get("hard_errors", [])
    high_pending = state.get("high_priority_pending", [])
    print(json.dumps({"hard_errors": hard_errors, "pending_suggestion_ids": state.get("pending_suggestion_ids", []), "high_priority_pending": high_pending, "applied_suggestion_ids": state.get("applied_suggestion_ids", [])}, ensure_ascii=False, indent=2))
    if fail_on_hard and hard_errors:
        return 2
    if fail_on_high and high_pending:
        return 3
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="skills knowledge automation")
    subparsers = parser.add_subparsers(dest="command", required=True)
    p_gen = subparsers.add_parser("generate")
    p_gen.add_argument("asset")
    p_route = subparsers.add_parser("route")
    p_route.add_argument("--base-ref", default="HEAD")
    p_route.add_argument("--paths", nargs="*")
    p_diff = subparsers.add_parser("diff-check")
    p_diff.add_argument("--base-ref", default="HEAD")
    p_diff.add_argument("--task", default="current")
    p_diff.add_argument("--paths", nargs="*")
    p_apply = subparsers.add_parser("apply")
    p_apply.add_argument("--task", default="current")
    p_apply.add_argument("--ids", required=True)
    p_status = subparsers.add_parser("status")
    p_status.add_argument("--task", default="current")
    p_status.add_argument("--fail-on-hard", action="store_true")
    p_status.add_argument("--fail-on-high", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "generate":
        return command_generate(args.asset)
    if args.command == "route":
        return command_route(args.base_ref, args.paths)
    if args.command == "diff-check":
        return command_diff_check(args.base_ref, args.task, args.paths)
    if args.command == "apply":
        return command_apply(args.task, [item.strip() for item in args.ids.split(",") if item.strip()])
    if args.command == "status":
        return command_status(args.task, args.fail_on_hard, args.fail_on_high)
    return 1

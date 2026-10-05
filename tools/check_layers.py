#!/usr/bin/env python3
"""架构分层校验 —— 违规即退出码 1（可挂进 CI / 提交前检查）。

**它读的是 `gen_code_map.py` 里的 `LAYERS` 表 —— 不复制第二份。**
改分层标准只需改那一处（`docs/guides/code-map.md` §3.1 的铁律）。

规则
----
R1  跨模块依赖只能「上层 → 下层」。**下层依赖上层 = 违规**。
R2  `@Mixin` 只能声明在 L4 / L5 —— 挂原版钩子必须关在接线层。
R3  领域（`living/domain/*`）之间应尽量不互相依赖。

⚠️ **棘轮机制（为什么现在就能用）**：项目当前已有违规，一次性修完不现实。
所以基线文件 `tools/layer_baseline.txt` 记录**已存在的违规**：
**只允许减少、不允许增加** —— 新增违规 ⇒ 退出码 1。
修掉一批之后跑 `--update-baseline` 收紧基线。

**为什么不用 jQAssistant**：它最值钱的是「规则可强制」，而这条不需要 Neo4j。
详见 `docs/guides/code-map.md`。

用法
----
    python tools/check_layers.py                     # 校验（有新增违规 ⇒ 退出 1）
    python tools/check_layers.py -v                  # 连类级明细一起列
    python tools/check_layers.py --show              # 只看当前违规，不做基线比对
    python tools/check_layers.py --update-baseline   # 修完后收紧基线
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_code_map as G  # noqa: E402  —— 复用它的 LAYERS 表与解析器（单一事实源）

ROOT = G.ROOT
BASELINE = os.path.join(ROOT, "tools", "layer_baseline.txt")

R1, R2, R3 = "module", "mixin", "domain"
TITLES = {
    R1: "R1 跨模块方向违规（下层依赖上层）",
    R2: "R2 Mixin 位置违规（只能声明在 L4 / L5）",
    R3: "R3 领域互依赖（living/domain/* 之间）",
}
# 规则顺序固定，便于基线文件 diff
ORDER = (R1, R2, R3)


def collect() -> dict[str, dict[tuple[str, str], int]]:
    """返回 {规则: {(源, 目标): 条数}}。"""
    files = [G.parse_file(p) for p in G.collect_java()]
    graph = G.build_graph(files)

    lay = {m["id"]: G.layer_of(m["id"]) for m in graph["modules"]}
    out: dict[str, dict[tuple[str, str], int]] = {k: {} for k in ORDER}

    for e in graph["moduleEdges"]:
        a, b, w = e["s"], e["d"], e["w"]
        if a not in lay or b not in lay:
            continue
        if lay[a] < lay[b]:
            out[R1][(a, b)] = out[R1].get((a, b), 0) + w
        elif a.startswith("living/domain/") and b.startswith("living/domain/"):
            out[R3][(a, b)] = out[R3].get((a, b), 0) + w

    # R2：声明了 @Mixin 的类，其所在层必须是 L4 / L5
    for f in files:
        if f["kind"] == "file" or not f["mixins"]:
            continue
        if G.layer_of(f["module"]) not in (4, 5):
            out[R2][(f["pkg"] + "." + f["name"], f["module"])] = 1

    return out


def read_baseline() -> dict[str, dict[tuple[str, str], int]]:
    base: dict[str, dict[tuple[str, str], int]] = {k: {} for k in ORDER}
    if not os.path.exists(BASELINE):
        return base
    with open(BASELINE, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 4:
                continue
            kind, a, b, n = parts
            if kind in base:
                base[kind][(a, b)] = int(n)
    return base


def write_baseline(cur: dict[str, dict[tuple[str, str], int]]) -> None:
    lines = [
        "# 架构分层基线 —— 已存在的违规。**只允许减少，不允许增加。**",
        "# 由 tools/check_layers.py 读写；除删行外请勿手改。",
        "# 修掉一批后跑：python tools/check_layers.py --update-baseline",
        "# 格式：规则 <TAB> 源 <TAB> 目标 <TAB> 条数",
        "",
    ]
    for kind in ORDER:
        rows = cur[kind]
        lines.append("# %s" % TITLES[kind])
        if not rows:
            lines.append("#   （无）")
        for (a, b), n in sorted(rows.items(), key=lambda x: (-x[1], x[0])):
            lines.append("%s\t%s\t%s\t%d" % (kind, a, b, n))
        lines.append("")
    with open(BASELINE, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


def main() -> int:
    verbose = "-v" in sys.argv
    show_only = "--show" in sys.argv
    update = "--update-baseline" in sys.argv

    cur = collect()
    totals = {k: (sum(cur[k].values()), len(cur[k])) for k in ORDER}

    print("=== 架构分层校验（tools/check_layers.py）===\n")
    for k in ORDER:
        n, pairs = totals[k]
        flag = "OK " if n == 0 else "!! "
        print("%s%s   %3d 条 / %d 对" % (flag, TITLES[k], n, pairs))
    print()

    if show_only or update:
        for k in ORDER:
            if not cur[k]:
                continue
            print("\n%s" % TITLES[k])
            for (a, b), n in sorted(cur[k].items(), key=lambda x: (-x[1], x[0])):
                extra = ""
                if k == R1:
                    extra = "  （%s → %s）" % (G.layer_name_of(G.layer_of(a)),
                                              G.layer_name_of(G.layer_of(b)))
                print("  %-28s -> %-28s %3d%s" % (a, b, n, extra))
        if update:
            write_baseline(cur)
            print("\n✅ 基线已更新：%s" % os.path.relpath(BASELINE, ROOT).replace("\\", "/"))
        return 0

    base = read_baseline()
    if not base[R1] and not base[R2] and not base[R3] and not os.path.exists(BASELINE):
        print("⚠ 还没有基线文件。先跑一次 `--update-baseline` 记录现状，之后才开始「只减不增」。")
        print("  （当前 %d 条违规会被记入基线，不算失败）"
              % sum(totals[k][0] for k in ORDER))
        return 0

    added: list[tuple[str, str, str, int, int]] = []
    shrunk: list[tuple[str, str, str, int, int]] = []
    for k in ORDER:
        for key, n in cur[k].items():
            old = base[k].get(key, 0)
            if n > old:
                added.append((k, key[0], key[1], old, n))
        for key, n in base[k].items():
            now = cur[k].get(key, 0)
            if now < n:
                shrunk.append((k, key[0], key[1], n, now))

    if added:
        print("🔴 新增违规 %d 条（基线不允许增加）：" % len(added))
        for k, a, b, old, n in sorted(added, key=lambda x: -x[4]):
            where = ""
            if k == R1:
                where = "  （%s → %s）" % (G.layer_name_of(G.layer_of(a)),
                                          G.layer_name_of(G.layer_of(b)))
            elif k == R2:
                where = "  （%s）" % G.layer_name_of(G.layer_of(b))
            print("   %-8s %-30s -> %-30s  %d → %d%s" % (k, a, b, old, n, where))
            if verbose and k == R1:
                print("           修法：让 %s 不再认识 %s —— 用注册表 / 上移契约 / 下沉入口"
                      % (a, b))
    if shrunk:
        print("\n⬇ 已消除 %d 条 —— 可以跑 `--update-baseline` 收紧基线：" % len(shrunk))
        for k, a, b, old, now in sorted(shrunk, key=lambda x: -(x[3] - x[4])):
            print("   %-8s %-30s -> %-30s  %d → %d" % (k, a, b, old, now))

    if added:
        print("\n✗ 失败：基线只允许减少，不允许增加。")
        print("  如果这是**有意的**架构决策，跑 `--update-baseline` 显式接受它。")
        return 1
    if shrunk:
        print("\n✓ 通过（有已消除项，记得收紧基线）")
    else:
        print("✓ 通过（无新增违规）")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError, OSError):
        pass
    sys.exit(main())

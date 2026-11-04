#!/usr/bin/env python3
"""槽位裸写门禁 —— `setItem(` 在 domain/function 的出现次数棘轮（违规即退出码 1）。

背景（TODO「物品传输」落地顺序 ③，2026-11-05）
------------------------------------------------
槽位数量增减的正确写法是 `ContainerContext` 的四个原语：
`move`（槽→槽搬运）/ `produce`（产物放置）/ `consume`（输入消费）/ `transform`（原地替换）
—— 守恒（I-C12）的「写后回读 + 只扣实际量」都在原语里。

domain/function 里直接调 `setItem` 是「先写再扣源」回潮的形状：
setItem 是覆盖语义（void、写失败无法表达），绕过原语 = 绕过守恒。

规则
----
**基线文件 `tools/stack_ops_baseline.txt` 记录每文件的 `setItem(` 现存次数：
只允许减少、不允许增加** —— 新增调用或新增文件 ⇒ 退出码 1，必须：
要么改用原语，要么在明确知道自己在干什么（状态写入 / 残留物替换等非数量搬运）
时跑 `--update-baseline` 有意识收紧……不对，是有意识**登记**进基线。

⚠️ 这不是禁令是**减速带**：setItem 仍有合法用途（写状态、组件、非搬运场景），
门禁只保证每次新增都被看见。存量清零是长期方向，不设期限。

统计口径：`src/main/java/com/qiqi/li/living/{domain,function}` 下 `.java` 文件里的
`setItem(` 出现次数；剔除整行注释（`//`、`*` 开头）与块注释标记行。
`transfer/` 与 `container/` 不在扫描范围 —— 前者是传输引擎（Accessor 内部写入是
正道），后者是原语实现本体。

用法
----
    python tools/check_stack_ops.py                     # 校验（新增 ⇒ 退出 1）
    python tools/check_stack_ops.py --show              # 只看当前计数，不做基线比对
    python tools/check_stack_ops.py --update-baseline   # 登记存量 / 收紧基线
"""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCAN_ROOTS = [
    os.path.join(ROOT, "src", "main", "java", "com", "qiqi", "li", "living", "domain"),
    os.path.join(ROOT, "src", "main", "java", "com", "qiqi", "li", "living", "function"),
]
BASELINE = os.path.join(ROOT, "tools", "stack_ops_baseline.txt")
NEEDLE = "setItem("


def count_in_file(path: str) -> int:
    """`setItem(` 出现次数；剔除整行注释（// 或 * 开头的行）。"""
    n = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue
            if NEEDLE in line:
                n += line.count(NEEDLE)
    return n


def current_counts() -> dict[str, int]:
    """{相对路径: 次数}，路径相对 living/（如 `domain/water/ContainerFluidData.java`）。"""
    counts: dict[str, int] = {}
    for root in SCAN_ROOTS:
        for dirpath, _dirs, files in os.walk(root):
            for name in files:
                if not name.endswith(".java"):
                    continue
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, os.path.dirname(SCAN_ROOTS[0]))
                rel = rel.replace("\\", "/")
                c = count_in_file(full)
                if c > 0:
                    counts[rel] = c
    return counts


def load_baseline() -> dict[str, int]:
    baseline: dict[str, int] = {}
    if not os.path.exists(BASELINE):
        return baseline
    with open(BASELINE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            rel, _, n = line.rpartition("\t")
            baseline[rel.strip()] = int(n)
    return baseline


def write_baseline(counts: dict[str, int]) -> None:
    lines = [
        "# 槽位裸写基线 —— domain/function 各文件 setItem( 的现存次数。**只允许减少，不允许增加。**",
        "# 由 tools/check_stack_ops.py 读写；除删行外请勿手改。",
        "# 新增调用想登记：确认不是「先写再扣源」后跑 python tools/check_stack_ops.py --update-baseline",
        "# 格式：文件路径 <TAB> 次数（路径相对 living/）",
        "",
    ]
    for rel in sorted(counts):
        lines.append(f"{rel}\t{counts[rel]}")
    with open(BASELINE, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main() -> int:
    update = "--update-baseline" in sys.argv
    show_only = "--show" in sys.argv

    counts = current_counts()

    if update:
        write_baseline(counts)
        print(f"[check_stack_ops] 基线已更新：{len(counts)} 个文件，共 {sum(counts.values())} 处")
        return 0

    if show_only:
        for rel in sorted(counts):
            print(f"{counts[rel]:>3}  {rel}")
        print(f"—— 共 {len(counts)} 个文件，{sum(counts.values())} 处（--show 不做基线比对）")
        return 0

    baseline = load_baseline()
    if not baseline:
        print("[check_stack_ops] 基线缺失 —— 首次运行先生成：python tools/check_stack_ops.py --update-baseline")
        return 1

    violations = []
    for rel in sorted(set(baseline) | set(counts)):
        now = counts.get(rel, 0)
        old = baseline.get(rel, 0)
        if now > old:
            violations.append(f"  {rel}: 基线 {old} → 现在 {now}（+{now - old}）")

    if violations:
        print("[check_stack_ops] 新增槽位裸写 —— 数量增减请走 ContainerContext 原语")
        print("  move / produce / consume / transform：写后回读 + 只扣实际量（I-C12）都在原语里。")
        print("  确认确属合法用途（状态写入等非数量搬运）后 --update-baseline 登记：")
        for v in violations:
            print(v)
        return 1

    shrink = sum(1 for rel in counts if counts[rel] < baseline.get(rel, 0))
    print(f"[check_stack_ops] 通过：{len(counts)} 个文件 {sum(counts.values())} 处，无新增"
          + (f"（较基线减少 {shrink} 处 —— 可跑 --update-baseline 收紧）" if shrink else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())

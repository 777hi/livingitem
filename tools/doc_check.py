"""
Documentation system consistency checker.

Verifies the invariants declared in docs/README.md §7 ("改完必查"):
  1. Path reality       - every path mentioned in a doc (relative markdown links,
                          and repo-relative docs/... / src/... mentions) exists
  2. Test count         - AGENTS.md's declared total matches build/test-results/test/*.xml
  3. Test tree          - the test file list in AGENTS.md matches src/test reality
  4. Archive continuity - changelog newest date -> AGENTS oldest date, gap <= 1 day
  5. Entry size         - AGENTS.md stays under the entry budget (soft warning)

Checks 1-4 fail the run (exit 1). Check 5 only warns: the entry being slightly over
budget is a maintenance signal, not a correctness error.

Usage: python tools/doc_check.py [-v]
"""
import os
import re
import sys
import glob
import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERBOSE = "-v" in sys.argv

ENTRY = os.path.join(ROOT, "AGENTS.md")
CHANGELOG = os.path.join(ROOT, "docs", "archive", "changelog.md")
RESULTS = os.path.join(ROOT, "build", "test-results", "test", "TEST-*.xml")

ENTRY_BUDGET = 20000          # chars, see docs/README.md §1
CONTINUITY_MAX_GAP_DAYS = 1   # see docs/README.md §4

failures = []
warnings = []


def rel(p):
    return os.path.relpath(p, ROOT).replace("\\", "/")


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def doc_files():
    """入口 + docs/ 下所有 md。**归档层排除**：冻结历史引用早已删除的文件是正常的，
    不该要求它链接有效（归档层不进加载路径、也不再编辑）。"""
    out = [ENTRY]
    for f in glob.glob(os.path.join(ROOT, "docs", "**", "*.md"), recursive=True):
        if os.sep + "archive" + os.sep in f:
            continue
        out.append(f)
    return out


def resolves(base, t):
    """链接目标可能相对「文件所在目录」写，也可能相对「仓库根」写（两种都存在）。"""
    return (os.path.exists(os.path.normpath(os.path.join(base, t)))
            or os.path.exists(os.path.normpath(os.path.join(ROOT, t))))


# ---------------------------------------------------------------- 1. path reality

PLANNED_MARKERS = ("未建", "待建", "规划中", "已删除", "待补")


def check_paths():
    bad = []
    for f in doc_files():
        text = read(f)
        base = os.path.dirname(f)

        for line in text.split("\n"):
            # 讨论「待建 / 已删除」文件的行不算断链
            if any(k in line for k in PLANNED_MARKERS):
                continue

            # markdown links: [label](target) — relative, or absolute file:// URLs
            # (earlier AI tools wrote file:///g:/... links; verify those too, don't skip them)
            for m in re.finditer(r"\]\(([^)\s]+)\)", line):
                t = m.group(1).strip()
                if not t or t.startswith(("http://", "https://", "mailto:")):
                    continue
                t = t.split("#")[0]
                if not t or t.startswith("/"):
                    continue
                if t.startswith("file:///"):
                    if not os.path.exists(t[len("file:///"):]):
                        bad.append((rel(f), m.group(1), "file:// 链接"))
                elif not resolves(base, t):
                    bad.append((rel(f), t, "相对链接"))

            # repo-relative mentions of docs/... or src/...
            for m in re.finditer(r"(?<![\w/.-])((?:docs|src)/[\w\-/.]*[\w\-]\.(?:md|java))", line):
                t = m.group(1)
                if not os.path.exists(os.path.join(ROOT, t)):
                    bad.append((rel(f), t, "仓库路径"))

    if bad:
        seen = set()
        for f, t, kind in bad:
            if (f, t) in seen:
                continue
            seen.add((f, t))
            failures.append(f"[路径] {f} 提到的 {kind} 不存在: {t}")
    print(f"1. 路径真实性      : {'FAIL' if bad else 'OK'}"
          + (f"（{len(set((a, b) for a, b, _ in bad))} 处）" if bad else ""))


# ---------------------------------------------------------------- 2/3. AGENTS.md facts

def check_test_count():
    declared = re.search(r"合计测试用例\s*\**\s*(\d+)\s*\**\s*个", read(ENTRY))
    if not declared:
        failures.append("[条数] AGENTS.md 找不到「合计测试用例 N 个」声明")
        print("2. 测试条数一致性  : FAIL（无声明）")
        return
    declared = int(declared.group(1))

    total = 0
    for f in glob.glob(RESULTS):
        h = re.search(r"<testsuite[^>]*>", read(f)).group(0)
        total += int(re.search(r'tests="(\d+)"', h).group(1))

    if total == 0:
        warnings.append("[条数] 没有测试结果 XML（先跑 ./gradlew test --rerun）—— 跳过条数核对")
        print("2. 测试条数一致性  : SKIP（无 XML）")
        return
    if declared != total:
        failures.append(f"[条数] AGENTS.md 声明 {declared}，实测 {total}")
        print(f"2. 测试条数一致性  : FAIL（声明 {declared} / 实测 {total}）")
    else:
        print(f"2. 测试条数一致性  : OK（{total}）")


def check_test_tree():
    """测试文件树住在 docs/reference/file-map.md（2026-09-16 从 AGENTS.md 迁入）。"""
    fm = os.path.join(ROOT, "docs", "reference", "file-map.md")
    if not os.path.exists(fm):
        failures.append("[测试树] 找不到 docs/reference/file-map.md")
        print("3. 测试树一致性    : FAIL（无 file-map.md）")
        return
    text = read(fm)
    anchor = "src/test/java/com/qiqi/li/"
    if anchor not in text:
        failures.append("[测试树] file-map.md 里找不到测试文件树")
        print("3. 测试树一致性    : FAIL（无测试树）")
        return
    i = text.index(anchor)
    block = text[i:text.index("```", i)]
    doc = set(re.findall(r"([A-Za-z0-9_]+Test)\.java", block))

    real = set()
    for f in glob.glob(os.path.join(ROOT, "src", "test", "**", "*.java"), recursive=True):
        n = os.path.basename(f)[:-5]
        if n.endswith("Test"):
            real.add(n)

    extra, missing = sorted(doc - real), sorted(real - doc)
    if extra or missing:
        if extra:
            failures.append(f"[测试树] file-map.md 列了不存在的测试类: {extra}")
        if missing:
            failures.append(f"[测试树] file-map.md 漏列测试类: {missing}")
        print(f"3. 测试树一致性    : FAIL（多 {len(extra)} / 缺 {len(missing)}）")
    else:
        print(f"3. 测试树一致性    : OK（{len(real)} 个测试类）")


# ---------------------------------------------------------------- 4. archive continuity

def check_continuity():
    cl = read(CHANGELOG)
    ag = read(ENTRY)
    cl_dates = sorted(set(re.findall(r"^## (\d{4}-\d{2}-\d{2})$", cl, re.M)))
    ag_dates = sorted(set(re.findall(r"\*\*最近更新\*\* \((\d{4}-\d{2}-\d{2})\)", ag)))
    if not cl_dates or not ag_dates:
        failures.append("[连续性] 找不到 changelog 日期段或 AGENTS 更新批次")
        print("4. 归档连续性      : FAIL（无日期）")
        return
    newest, oldest = cl_dates[-1], ag_dates[0]
    gap = (datetime.date.fromisoformat(oldest) - datetime.date.fromisoformat(newest)).days
    if gap > CONTINUITY_MAX_GAP_DAYS:
        failures.append(
            f"[连续性] changelog 最新 {newest} → AGENTS 最早 {oldest}，空档 {gap - 1} 天（历史可能丢失）")
        print(f"4. 归档连续性      : FAIL（空档 {gap - 1} 天）")
    else:
        print(f"4. 归档连续性      : OK（{newest} → {oldest}）")


# ---------------------------------------------------------------- 5. entry size

def check_entry_size():
    n = len(read(ENTRY))
    if n > ENTRY_BUDGET:
        warnings.append(f"[体量] AGENTS.md {n} 字符 > 预算 {ENTRY_BUDGET} —— 该归档一轮了（docs/README.md §4）")
        print(f"5. 入口体量        : WARN（{n} > {ENTRY_BUDGET}）")
    else:
        print(f"5. 入口体量        : OK（{n} / {ENTRY_BUDGET}）")


def check_decisions():
    """决策索引：取代关系必须双向一致（A 说取代 B ⇒ B 必须说被 A 取代）。
    这是让「翻转留痕」不依赖人记性的唯一办法。"""
    p = os.path.join(ROOT, "docs", "decisions.md")
    if not os.path.exists(p):
        print("6. 决策取代关系    : SKIP（无 docs/decisions.md）")
        return

    rows = {}
    for line in read(p).split("\n"):
        if not line.startswith("| D-"):
            continue
        f = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(f) < 7:
            continue
        rows[f[0]] = (f[5], f[6])          # id -> (状态, 取代关系)

    def has_id(text, did):
        return re.search(r"(?<![\w-])" + re.escape(did) + r"(?![\w-])", text) is not None

    bad = []
    for did, (status, rel) in rows.items():
        for m in re.finditer(r"→\s*(D-[\w-]+)", rel):
            other = m.group(1)
            if other not in rows:
                bad.append(f"{did} 指向不存在的 {other}")
            elif not has_id(rows[other][1], did):
                bad.append(f"{did} → {other}，但 {other} 未反向标注 ← {did}")
        if status == "已被取代" and "→" not in rel:
            bad.append(f"{did} 状态「已被取代」但没写 → 接替者")
        if status == "生效" and "→" in rel:
            bad.append(f"{did} 状态「生效」却写了 → 接替者（应改状态）")

    if bad:
        for b in bad:
            failures.append("[决策] " + b)
        print(f"6. 决策取代关系    : FAIL（{len(bad)} 处）")
    else:
        print(f"6. 决策取代关系    : OK（{len(rows)} 条决策）")


def main():
    print("=== 文档系统一致性检查（docs/README.md §7）===\n")
    check_paths()
    check_test_count()
    check_test_tree()
    check_continuity()
    check_entry_size()
    check_decisions()

    print()
    for w in warnings:
        print("  WARN  " + w)
    for f in failures:
        print("  FAIL  " + f)
    if failures:
        print(f"\n✗ {len(failures)} 项失败（{len(warnings)} 项警告）")
        return 1
    print(f"\n✓ 全部通过（{len(warnings)} 项警告）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

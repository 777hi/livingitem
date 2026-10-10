"""
Documentation system consistency checker.

Verifies the invariants declared in docs/README.md §7 ("改完必查"):
  1. Path reality      - every path mentioned in a doc (relative markdown links,
                         and repo-relative docs/... / src/... mentions) exists
  2. Test count        - AGENTS.md's declared total matches build/test-results/test/*.xml
  3. Test tree         - the test file list in test-map.md matches src/test reality
  4. Progress rolling  - AGENTS「开发进展」一行式条数 / 日期对齐 / 指针含 .md
  5. Entry size        - AGENTS.md stays under the entry budget (soft warning)
  6. Decisions         - decisions.md 的 supersedes 链双向一致
  7. Java symbols      - docs 提到的 Living*.java / *Mixin.java 必须存在
  8. Commands          - docs 引用的 /livingitem 子命令必须已注册
  9. Method refs       - docs 里 ClassName.method(...) 的方法必须存在于源码
 10. §0 玩法定义        - 每份 docs/tech/living-*-tech.md 必含「§0 玩法定义」
 11. buffer 滞留       - 标着「已完成」却留在 buffer（不稳定层）的文档（soft warning）

Checks 1-4 and 6-10 fail the run (exit 1). Checks 5 and 11 only warn: both are
maintenance signals that need a human decision, not correctness errors.

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
BUFFER = os.path.join(ROOT, "docs", "buffer")

# chars（不是磁盘字节）：这是 AI 的**加载预算**，而 AI 按 token 消耗上下文，
# 与文件在磁盘上占多少字节无关（中文 UTF-8 一个字符 3 字节，差 1.5 倍）。
# 曾写「≤20KB」⇒ 22,377 字节的 AGENTS.md 被判 OK，红线形同虚设（2026-10-10 修正）。
ENTRY_BUDGET = 20000            # chars, see docs/README.md §1
MAX_PROGRESS_ROWS = 10          # 「开发进展」一行式滚动清单上限；see docs/README.md §4

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
        # buffer 层（设计稿 / 计划 / 路线图）整体豁免：它天然会描述**尚未创建**的文件路径，
        # 与第 7 项（Java 符号）、第 9 项（方法名）同一口径 —— 「设计稿可以引用未实现的东西」。
        if "buffer" in os.path.relpath(f, ROOT).replace("\\", "/").split("/"):
            continue
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
    """测试文件树住在 docs/reference/test-map.md（2026-10-10 从 file-map.md 拆出：
    主文件树是清单型内容已删，测试树是叙事型意图说明保留）。"""
    fm = os.path.join(ROOT, "docs", "reference", "test-map.md")
    if not os.path.exists(fm):
        failures.append("[测试树] 找不到 docs/reference/test-map.md")
        print("3. 测试树一致性    : FAIL（无 test-map.md）")
        return
    text = read(fm)
    anchor = "src/test/java/com/qiqi/li/"
    if anchor not in text:
        failures.append("[测试树] test-map.md 里找不到测试文件树")
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
            failures.append(f"[测试树] test-map.md 列了不存在的测试类: {extra}")
        if missing:
            failures.append(f"[测试树] test-map.md 漏列测试类: {missing}")
        print(f"3. 测试树一致性    : FAIL（多 {len(extra)} / 缺 {len(missing)}）")
    else:
        print(f"3. 测试树一致性    : OK（{len(real)} 个测试类）")


# ---------------------------------------------------------------- 4. progress rolling

def check_progress_rolling():
    """「开发进展」一行式滚动校验（2026-09-22 修订，取代原归档边界校验）。

    规约（docs/README.md §4）：AGENTS「开发进展」只放**最近 MAX_PROGRESS_ROWS 条**
    「一行结论 + 指针」，**完整正文直接写 changelog**；超出上限时删掉最底部一行
    （正文早已在 changelog ⇒ 无迁移、无守恒校验、无边界判据）。

    旧「批次制 + 定期归档」的两个毛病（实测）：
      ① 归档是**有风险的手工操作**（连续迁出 + 守恒校验 + 同日批次合并坑），
         漏做即**永久丢历史**（`2026-09-01~04` 断档）；
      ② 实测条目平均 **705 字符**（规约要求「一行结论 + 指针」）⇒ 进展节占入口 **55%**。

    三条判据（全部可复算）：
      ① 条数 ≤ MAX_PROGRESS_ROWS（防入口膨胀）
      ② 最新条目日期 == changelog 顶部日期（防「只加了 AGENTS、忘了写 changelog」）
      ③ 每条都有指针（含 `.md`，防退化成「只有结论」的流水账）
    """
    cl = read(CHANGELOG)
    ag = read(ENTRY)
    rows = re.findall(r"^\| (\d{4}-\d{2}-\d{2}) \| (.+?) \| (.+?) \|$", ag, re.M)
    if not rows:
        failures.append("[进展] AGENTS 里找不到一行式清单（`| 日期 | 变更 | 指针 |`）")
        print("4. 进展滚动        : FAIL（无条目）")
        return

    problems = []
    if len(rows) > MAX_PROGRESS_ROWS:
        problems.append("条数 %d > 上限 %d" % (len(rows), MAX_PROGRESS_ROWS))

    no_ptr = [r[1][:20] for r in rows if ".md" not in r[2]]
    if no_ptr:
        problems.append("缺指针 %d 条（如：%s）" % (len(no_ptr), "；".join(no_ptr[:2])))

    cl_dates = re.findall(r"^## (\d{4}-\d{2}-\d{2})$", cl, re.M)
    if not cl_dates:
        problems.append("changelog 里找不到日期段")
    else:
        ag_top = max(r[0] for r in rows)
        if ag_top != cl_dates[0]:
            problems.append(
                "最新条目 %s ≠ changelog 顶部 %s —— 写完 changelog 正文了吗？" % (ag_top, cl_dates[0]))

    if problems:
        for x in problems:
            failures.append("[进展] " + x)
        print("4. 进展滚动        : FAIL（%s）" % "；".join(problems))
    else:
        print("4. 进展滚动        : OK（%d/%d 条，最新 %s）" % (len(rows), MAX_PROGRESS_ROWS, cl_dates[0]))


# ---------------------------------------------------------------- 5. entry size

def check_entry_size():
    n = len(read(ENTRY))
    if n > ENTRY_BUDGET:
        warnings.append(f"[体量] AGENTS.md {n} 字符 > 预算 {ENTRY_BUDGET} —— 先查冗余（章节重复），再考虑降级搬 docs/reference/（docs/README.md §1/§5）")
        print(f"5. 入口体量        : WARN（{n} > {ENTRY_BUDGET}）")
    else:
        print(f"5. 入口体量        : OK（{n} / {ENTRY_BUDGET}）")


# ---------------------------------------------------------------- 6/7. java symbols

# 只检查「本项目专有命名」的类引用。原判据注释曾写「原版 / 第三方没有 Living* / *Mixin 命名的类」
# —— **这句是错的**（见下方 2026-10-03 实测）。当前口径：**带 `.java` 后缀**才检查。
# 2026-09-22 探针：命中 5 处，全是「| X.java | 删除 |」表格写法 ⇒ 已加豁免。
#
# ⚠️ 2026-10-03 试过放宽（去掉 `.java` 要求，直接匹配类名）：**放弃**。
# 实测命中 73 处，其中约 43 处是**误报**，且**无法机械排除**：
#   - 原版 / NeoForge 的 Living* 类：`LivingEntity`(×12)、`LivingDamageEvent`(×5)、
#     `LivingIncomingDamageEvent`(×3)、`LivingKnockBackEvent`、`LivingEquipmentChangeEvent`
#   - 第三方（Sable）的 Mixin：`ServerboundMovePlayerPacketMixin`(×5)、`ChunkMapMixin`(×3) …
#   - 占位符写法：`LivingXxxData` / `LivingXxxItemOverrides`
# ⇒ **「Living 前缀」不足以判定是本项目类。** 带 `.java` 后缀虽窄，但误报率≈0 ——
#   **窄而准 > 宽而吵**（一个 59% 误报的检查会被无视，等于没有）。
#   代价：不带后缀的引用（实例 `gui-interaction-system.md` 的 `LivingFunctionConfig`）**仍会漏**，
#   属**已知盲区**，只能靠人工复核。要补的话需要"原版类清单"之类的机制，成本另算。
JAVA_SYMBOL_RE = re.compile(r"\b(Living[A-Za-z0-9_]*\.java|[A-Z][A-Za-z0-9_]*Mixin\.java)\b")
# 行级豁免：讲历史/计划的行不算误导（「删除」覆盖「| X.java | 删除 |」这种表格写法）
JAVA_SKIP_WORDS = ("已删除", "已移除", "未实现", "设计稿", "待建", "不存在", "已废弃", "作废", "删除")
# 路径级豁免：引用原版 / 第三方源码的文件名，不是本项目文件
JAVA_SKIP_PATHS = ("libs/src/", "net/minecraft", "net/neoforged", "minecraft/")


def check_java_symbols():
    """文档提到的本项目 Java 文件必须存在（2026-09-22 新增）。

    **为什么需要**：第 1 项只校验 `.md` 链接；而文档大量以**文件名**形式引用 Java 类
    （`LivingXxx.java`、`FooMixin.java`），这类引用**不受任何机械检查**，只能靠人记性 ——
    实测漂移过多次（`Config.java`、`LivingButton.java`、`chest_living.png` 都是这么发现的）。
    用户原则：「文档是帮助我们了解项目的，**不是误导我们的**」。

    判据：文档（**排除 archive / buffer**）里出现的 `Living*.java` / `*Mixin.java`
    必须能在 `src/**` 找到；讲历史或计划的行（含「删除 / 未实现 / 设计稿…」）豁免。
    ⚠️ **不带 `.java` 后缀的引用不在检查范围** —— 放宽试过，因误报率 59% 而放弃，
    理由见上方 `JAVA_SYMBOL_RE` 的注释。
    """
    have = set()
    for root, _dirs, files in os.walk(os.path.join(ROOT, "src")):
        for f in files:
            if f.endswith(".java"):
                have.add(f)

    docs = [ENTRY]
    for f in glob.glob(os.path.join(ROOT, "docs", "**", "*.md"), recursive=True):
        parts = f.replace("\\", "/").split("/")
        if "archive" in parts or "buffer" in parts:
            continue
        docs.append(f)

    bad = []
    for p in docs:
        for i, line in enumerate(read(p).split("\n"), 1):
            if any(w in line for w in JAVA_SKIP_WORDS):
                continue
            if any(w in line for w in JAVA_SKIP_PATHS):
                continue
            for m in JAVA_SYMBOL_RE.finditer(line):
                if m.group(1) not in have:
                    bad.append("%s:%d 提到不存在的 %s" % (rel(p), i, m.group(1)))

    if bad:
        for b in bad:
            failures.append("[符号] " + b)
        print("7. Java 符号真实性 : FAIL（%d 处）" % len(bad))
        if VERBOSE:
            for b in bad:
                print("      " + b)
    else:
        print("7. Java 符号真实性 : OK")


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


def check_commands():
    """命令真实性（2026-09-27 新增，第 8 项）：文档里写的 /livingitem ... 必须真实存在。

    **为什么需要**：第 7 项只校验 Java 文件名，第 1 项只校验 md 链接 ——
    两者都覆盖不到「文档里写的命令字符串」。2026-09-27 `/living_monitor` 迁至
    `/livingitem debug` 时，代码改了、4 份活跃文档共 10 处旧命令名残留，
    全部校验静默通过 ⇒ 读者照着敲会得到「未知指令」。

    判据：文档里 `/livingitem` 之后的每一级 token，必须是源码中
    `Commands.literal("...")` 注册过的字面量（参数占位符 `<x>` / `[x]` / `a|b` 跳过）。
    """
    literals = set()
    for f in glob.glob(os.path.join(ROOT, "src", "main", "java", "**", "*.java"), recursive=True):
        with open(f, encoding="utf-8") as fh:
            literals |= set(re.findall(r'literal\("([\w_]+)"\)', fh.read()))
    if not literals:
        print("8. 命令真实性      : SKIP（未找到 literal 注册）")
        return

    bad = []
    for p in doc_files():
        text = read(p)
        lines = text.split("\n")
        for i, line in enumerate(lines):
            # 行级豁免：含「待建」等标记的行不是断言 —— 设计稿描述未来命令是正当场景
            # （与第 1 项 PLANNED_MARKERS、第 7 项 JAVA_SKIP_WORDS 同一语义：
            #   「讲计划的行」不等于「声称它已存在」）。
            # 2026-09-28：interaction-rule-design.md §3 提到尚未实现的
            # /livingitem interaction ... 时首次触发本豁免需求。
            if any(k in line for k in PLANNED_MARKERS):
                continue
            m = re.search(r"/livingitem(?![A-Za-z0-9_])", line)
            if not m:
                continue
            # 单行命令形态：逐「纯 ASCII 词」校验 /livingitem 之后的 token，
            # 遇到参数占位符 <x>、含 / 的路径、或中文说明即停止 —— 那些不是命令级别。
            rest = line[m.end():]
            for tok in rest.split():
                if not re.fullmatch(r"[A-Za-z0-9_]+", tok):
                    break                      # 参数 / 路径 / 自然语言 ⇒ 命令到此结束
                if tok.isdigit():
                    continue                   # 示例命令里的参数值（如 register 9 54）
                if tok not in literals:
                    bad.append((rel(p), tok))

            # 树形图形态：/livingitem 单独一行，子命令画在下面（├── container / └── debug）
            # —— 这是 commands.md 的主要写法，必须一并校验，否则导航核心反而是盲区。
            if not rest.strip():
                for ln in lines[i + 1: i + 11]:
                    tm = re.match(r"^\s*[│\s]*[├└]──\s+([A-Za-z0-9_]+)", ln)
                    if tm:
                        if tm.group(1) not in literals:
                            bad.append((rel(p), tm.group(1)))
                    elif ln.strip() and not ln.lstrip().startswith("│"):
                        break                  # 离开树形图

    if bad:
        seen = set()
        for f, t in bad:
            if (f, t) in seen:
                continue
            seen.add((f, t))
            failures.append(f"[命令] {f} 引用了未注册的子命令: /livingitem ... {t}")
        print(f"8. 命令真实性      : FAIL（{len(seen)} 处）")
    else:
        print("8. 命令真实性      : OK")


# ---------------------------------------------------------------- 9. method refs

# 只检查「本项目类 + 方法」的组合引用（`ClassName.method(`）。
# ⚠️ **已知盲区**：**不带类名的裸方法名**（如 `getSlotSignal()`）不在检查范围 ——
# 方法名太泛（`getSignal` / `tick` / `calculate` 都是原版或通用名），放宽会误报爆炸。
# 口径与第 7 项一致：**窄而准 > 宽而吵**。
METHOD_REF_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)\s*\(")


def check_method_refs():
    """文档里 `ClassName.method(...)` 引用的方法必须在本项目源码中存在（2026-10-08 新增）。

    **为什么需要**：第 7 项只断言「文档提到的 `Living*.java` 文件必须存在」，
    **不校验方法名** ⇒ 删掉的方法会永远留在文档里。
    实测（2026-10-08）：`ContainerRedstoneData.getSlotSignal` 已从代码删除，
    但 `living-redstone-tech.md` / `living-tnt-tech.md` 仍有 4 处引用，无任何机械检查能发现。
    用户原则同第 7 项：「**文档不是误导我们的**」。

    判据：文档（**排除 archive / buffer** —— 冻结历史与设计稿可以引用未实现的东西）
    里出现 `ClassName.method(` 形式，若 `ClassName` 是**本项目已有类**，
    则 `method` 必须能在 `src/**` 任意位置找到（跨文件 / 继承 / 接口都算存在）。
    豁免：讲历史 / 计划的行（复用 `JAVA_SKIP_WORDS`）。

    ⚠️ **不带类名的裸方法名不在检查范围**（已知盲区，理由见上方 `METHOD_REF_RE` 注释）。
    """
    class_names = set()
    for root, _dirs, files in os.walk(os.path.join(ROOT, "src")):
        for f in files:
            if f.endswith(".java"):
                class_names.add(f[:-5])

    src_files = []
    for root, _dirs, files in os.walk(os.path.join(ROOT, "src")):
        for f in files:
            if f.endswith(".java"):
                src_files.append(os.path.join(root, f))
    all_src = "\n".join(read(p) for p in src_files)
    # 粗粒度即可：任何位置出现过 `name(` 就认为该方法名存在（含调用点，宽松 ⇒ 少误报）
    known_methods = set(re.findall(r"\b([a-zA-Z_][A-Za-z0-9_]*)\s*\(", all_src))

    docs = [ENTRY]
    for f in glob.glob(os.path.join(ROOT, "docs", "**", "*.md"), recursive=True):
        parts = f.replace("\\", "/").split("/")
        if "archive" in parts or "buffer" in parts:
            continue
        docs.append(f)

    bad = []
    for p in docs:
        for i, line in enumerate(read(p).split("\n"), 1):
            if any(w in line for w in JAVA_SKIP_WORDS):
                continue
            if any(w in line for w in JAVA_SKIP_PATHS):
                continue
            for m in METHOD_REF_RE.finditer(line):
                cls, meth = m.group(1), m.group(2)
                if cls not in class_names:
                    continue          # 原版 / 第三方类，不管
                if meth not in known_methods:
                    bad.append("%s:%d 提到不存在的 %s.%s" % (rel(p), i, cls, meth))

    # 历史：2026-10-08 上线首日曾以 **warning 级**试运行（当时报出 13 处陈旧引用、
    # 跨 8 个文档，超出当日任务范围）⇒ 同日清完后**已恢复 fail 级**。
    if bad:
        for b in bad:
            failures.append("[方法] " + b)
        print("9. 方法名真实性    : FAIL（%d 处）" % len(bad))
        if VERBOSE:
            for b in bad:
                print("      " + b)
    else:
        print("9. 方法名真实性    : OK")


# ---------------------------------------------------------------- 10. §0 play section

def check_play_sections():
    """每份 docs/tech/living-*-tech.md 必须含「## §0 玩法定义」（规约见 docs/README.md §2）。

    为什么值得自动检查：§0 是**功能需求的来源**与**行为对错的判据**，也是 AI 读子系统的
    **第一站**（AGENTS 子系统索引已注明「读子系统先读 §0」）。新加活物品时**漏写 §0
    没有别的地方会报** —— 文档系统里其它检查都只看链接 / 条数 / 符号，看不到"这一节缺失"。
    """
    files = sorted(glob.glob(os.path.join(ROOT, "docs", "tech", "living-*-tech.md")))
    missing = []
    for f in files:
        # 接受「§0 玩法定义」及带后缀写法；亦兼容活武器原有的「§0 定位与铁律」标题
        if not re.search(r"^## §0 (玩法定义|定位与铁律)", read(f), re.M):
            missing.append(os.path.basename(f))
    if missing:
        failures.append(f"[§0 玩法定义] 以下 tech 文档缺 §0: {missing}")
        print(f"10. §0 玩法定义    : FAIL（{len(missing)}/{len(files)} 份缺失）")
    else:
        print(f"10. §0 玩法定义    : OK（{len(files)} 份全覆盖）")


# ---------------------------------------------------------------- 11. buffer 滞留

# 完成态词 / 未完成态词。**未完成态词优先**：一份「部分落地」「进行中」的计划
# 内部会用 ✅ 标记已完成步骤，只匹配完成词会误报（实测 open-plan.md、
# power-refactoring-plan.md 都会命中）⇒ 双向判据。
DONE_WORDS = ("已实现", "已验收", "全部完成", "已完成", "已落地", "已定案")
OPEN_WORDS = ("进行中", "部分", "未定案", "待办", "待做", "待拍板", "探讨",
              "设计中", "未实现", "规划中", "未完成", "待决")
STATUS_WINDOW = 40              # 只取「状态:」后这几十字符 —— 再往后就是正文里的局部完成标记


def check_buffer_staleness():
    """buffer 层滞留检测（2026-10-10 新增，警告级）。

    防的是 docs/README.md 铁律 0 点名的**头号误导**：buffer 的定义是
    「不稳定 / 未定案，别当现状读」，一份标着「✅ 已实现并验收」的文档待在那里，
    新 AI 会把它当成未定案的设计稿（或反过来，把未定案当现状）。

    **只报警，不自动搬运**：AGENTS.md 的进展指针可能正指向它，
    搬走必然断链（第 1 项随后会报）⇒ 搬 / 改状态标注由人决定，脚本只负责提醒。
    """
    stale = []
    for p in sorted(glob.glob(os.path.join(BUFFER, "*.md"))):
        head = read(p).split("\n")[:20]          # 状态横幅只出现在文件头
        for line in head:
            if not re.search(r"状态[:：]", line):
                continue
            val = line.split("状态", 1)[-1].lstrip("：: ")[:STATUS_WINDOW]
            if any(w in val for w in DONE_WORDS) and not any(w in val for w in OPEN_WORDS):
                stale.append(os.path.basename(p))
            break                                 # 只看第一条状态行
    if stale:
        warnings.append(
            "[buffer 滞留] 已完成文档仍在 buffer（会被当未定案读）: " + ", ".join(stale)
            + " —— 搬 docs/archive/（**须同步改入口指针**，第 1 项会兜底）或就地改状态标注")
        print(f"11. buffer 滞留      : WARN（{len(stale)} 份）")
    else:
        print("11. buffer 滞留      : OK")


def main():
    print("=== 文档系统一致性检查（docs/README.md §7）===\n")
    check_paths()
    check_test_count()
    check_test_tree()
    check_progress_rolling()
    check_entry_size()
    check_decisions()
    check_java_symbols()
    check_commands()
    check_method_refs()
    check_play_sections()
    check_buffer_staleness()

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
    # Windows 控制台默认 GBK —— 结论行里的 "✓" / "✗"（U+2713 / U+2717）
    # 不在 GBK 字符集内，print 会抛 UnicodeEncodeError。**即使 8 项全通过**，
    # 那句「全部通过」也打印不出来，只剩一个 traceback（失败分支的 ✗ 同样崩，
    # ⇒ 真 FAIL 时看到的是 traceback 而不是 FAIL 清单）。这里统一强制 UTF-8。
    # IDE / CI 把 stdout 换成非 TextIOWrapper 时静默跳过（这些环境通常已是 UTF-8）。
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError, OSError):
        pass
    sys.exit(main())

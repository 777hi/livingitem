#!/usr/bin/env python3
"""测试台账（只读）—— 从 build/test-results + src/test 生成“在测什么”的盘点。

用法：
    python tools/test_inventory.py
    python tools/test_inventory.py --unreferenced
    python tools/test_inventory.py --out build/test-inventory.md

输出：
    build/test-inventory.md   （默认，git 忽略；不写源码）

判据：
    - 用例数取 JUnit XML（含参数化展开与 @Nested；按 testcase.classname 归并）
    - 声明方法数取源码注解（@Test / @ParameterizedTest / @RepeatedTest）
    - “类型”是按包名 / 类名的**启发式**归类，只用于看形状，不作契约
    - “KnownGap”只做文本标记识别，不代表已登记；登记规范见
      docs/buffer/2026-10-29-测试域梳理.md §5
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_SRC = os.path.join(ROOT, "src", "test", "java")
MAIN_SRC = os.path.join(ROOT, "src", "main", "java")
XML_DIR = os.path.join(ROOT, "build", "test-results", "test")

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

KNOWN_GAP_MARKERS = ("known-gap", "GAP-", "已知缺口", "已知残留", "文档明示")


def read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def read_xml() -> tuple[dict, dict]:
    """返回 (按类聚合, 按 suite 聚合)。按 testcase.classname 归并 @Nested。"""
    by_class: dict[str, dict] = defaultdict(
        lambda: {"tests": 0, "skipped": 0, "failures": 0, "errors": 0, "time": 0.0}
    )
    by_suite: dict[str, dict] = defaultdict(
        lambda: {"tests": 0, "skipped": 0, "failures": 0, "errors": 0, "time": 0.0}
    )
    for path in glob.glob(os.path.join(XML_DIR, "TEST-*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suite_name = root.get("name") or os.path.basename(path)
        for tc in root.iter("testcase"):
            cls = (tc.get("classname") or suite_name).split("$")[0]
            t = float(tc.get("time") or 0)
            for bucket, key in ((by_class, cls), (by_suite, suite_name)):
                rec = bucket[key]
                rec["tests"] += 1
                rec["time"] += t
                if tc.find("skipped") is not None:
                    rec["skipped"] += 1
                if tc.find("failure") is not None:
                    rec["failures"] += 1
                if tc.find("error") is not None:
                    rec["errors"] += 1
    return dict(by_class), dict(by_suite)


def classify(pkg: str, name: str) -> str:
    p = pkg.replace(".", "/")
    if "client/" in p:
        return "客户端 UI/渲染/输入"
    if "/network/" in p:
        return "网络/序列化"
    if "compat/" in p:
        return "第三方兼容"
    if re.search(r"(Packet|Sync|Codec|Serialize|HostPacket|Segment)", name):
        return "网络/序列化"
    if re.search(r"(Contract|Ownership|Activation|TickOrder|DroppedItem|Boundary|Config|Registry)", name):
        return "API/契约/注册/配置"
    if re.search(r"(IntegrationTest|IT$|FeedChain|E2E|RealMenu)", name):
        return "集成/端到端"
    if re.search(r"(Perf)", name):
        return "性能"
    if re.search(r"(Math|Geometry|Column|Coord|Beam|Layout|Progress|Color|RenderState)", name):
        return "纯函数/数学/几何"
    # 领域关键词先于通用 Container：ContainerFluidDataTest 属于流体，不是容器基础设施
    if re.search(r"(Fluid|Redstone|Power|Phase|Channel|Copper|Beacon|Farmland|Brewing|Furnace|Hopper|Ender|Chest|Backpack|Tool|Tnt|Explosion|Map|Bucket|Interaction|Filter)", name):
        return "领域状态机/规则"
    if re.search(r"(Container|Slot|Menu|Context|Entity|Handoff|Uuids|Keys|Cache|Persist|Viewer|Chunk|Anchor|Neighbor)", name):
        return "容器基础设施/布局/身份"
    return "其他/回归"


def scan_sources() -> tuple[list[dict], list[str]]:
    rows: list[dict] = []
    support: list[str] = []
    for path in sorted(glob.glob(os.path.join(TEST_SRC, "**", "*.java"), recursive=True)):
        src = read(path)
        pkg_m = re.search(r"^package\s+([\w.]+);", src, re.M)
        pkg = pkg_m.group(1) if pkg_m else "?"
        name = os.path.basename(path)[:-5]
        raw_tests = len(re.findall(r"@(?:Test|ParameterizedTest|RepeatedTest)\b", src))
        nested = len(re.findall(r"@Nested\b", src))
        disabled = len(re.findall(r"@Disabled\b", src))
        assertions = len(re.findall(r"\b(?:assert\w*|fail|verify)\s*\(", src))
        tags = sorted(set(re.findall(r'@Tag\("([^"]+)"\)', src)))
        lines = src.count("\n") + 1
        pipeline = bool(re.search(r"\bprocessContext\b|\bprocessContainerAt\b", src))
        known_gap = any(m in src for m in KNOWN_GAP_MARKERS)
        reason = ""
        dm = re.search(r"@Disabled\s*\(([^)]*)\)", src)
        if dm:
            reason = dm.group(1).strip().strip('"')
        if raw_tests == 0:
            support.append(os.path.relpath(path, TEST_SRC))
            continue
        rows.append(
            dict(
                pkg=pkg,
                name=name,
                fqcn=f"{pkg}.{name}",
                type=classify(pkg, name),
                raw=raw_tests,
                nested=nested,
                disabled=disabled,
                reason=reason,
                assertions=assertions,
                lines=lines,
                tags=tags,
                pipeline=pipeline,
                known_gap=known_gap,
            )
        )
    return rows, support


def unreferenced_main_classes() -> list[str]:
    tests = "\n".join(read(p) for p in glob.glob(os.path.join(TEST_SRC, "**", "*.java"), recursive=True))
    out = []
    for path in sorted(glob.glob(os.path.join(MAIN_SRC, "**", "*.java"), recursive=True)):
        cls = os.path.basename(path)[:-5]
        if cls not in tests:
            out.append(os.path.relpath(path, MAIN_SRC))
    return out


def fmt_table(headers: list[str], rows: list[list[str]]) -> str:
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join("" if c is None else str(c) for c in r) + " |")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description="测试台账（只读）")
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "test-inventory.md"))
    ap.add_argument("--unreferenced", action="store_true", help="附带主类未被测试源码引用的清单")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    xml_by_class, xml_by_suite = read_xml()
    rows, support = scan_sources()

    total_tests = sum(v["tests"] for v in xml_by_class.values())
    total_skipped = sum(v["skipped"] for v in xml_by_class.values())
    total_failed = sum(v["failures"] + v["errors"] for v in xml_by_class.values())
    total_time = sum(v["time"] for v in xml_by_class.values())

    # 按包汇总
    by_pkg: dict[str, dict] = defaultdict(lambda: {"classes": 0, "tests": 0, "asserts": 0, "lines": 0})
    for r in rows:
        rec = by_pkg[r["pkg"]]
        rec["classes"] += 1
        rec["tests"] += xml_by_class.get(r["fqcn"], {}).get("tests", 0)
        rec["asserts"] += r["assertions"]
        rec["lines"] += r["lines"]

    # 按类型汇总
    by_type: dict[str, dict] = defaultdict(lambda: {"classes": 0, "tests": 0})
    for r in rows:
        rec = by_type[r["type"]]
        rec["classes"] += 1
        rec["tests"] += xml_by_class.get(r["fqcn"], {}).get("tests", 0)

    gaps = [r for r in rows if r["known_gap"]]
    disabled = [r for r in rows if r["disabled"]]
    pipeline = [r for r in rows if r["pipeline"]]

    lines: list[str] = []
    lines.append("# 测试台账（自动生成）\n")
    lines.append("> 只读盘点，**不要手改**。生成命令：`python tools/test_inventory.py`")
    lines.append("> 用例数取 `build/test-results/test/*.xml`；类型是按包名/类名的启发式归类。\n")
    lines.append("## 1. 总量\n")
    lines.append(
        fmt_table(
            ["指标", "值"],
            [
                ["测试类（有 @Test）", len(rows)],
                ["辅助类 / 替身", len(support)],
                ["用例（XML 展开）", total_tests],
                ["跳过", total_skipped],
                ["失败 / 错误", total_failed],
                ["总耗时（秒）", f"{total_time:.2f}"],
                ["已知缺口（文本标记）", len(gaps)],
                ["@Disabled 类", len(disabled)],
                ["调用 processContext 的类", len(pipeline)],
            ],
        )
    )
    lines.append("\n## 2. 按包\n")
    pkg_rows = [
        [k, v["classes"], v["tests"], v["asserts"], v["lines"]]
        for k, v in sorted(by_pkg.items(), key=lambda x: -x[1]["tests"])
    ]
    lines.append(fmt_table(["包", "测试类", "用例", "断言", "行数"], pkg_rows))
    lines.append("\n## 3. 按类型（启发式）\n")
    lines.append(
        fmt_table(
            ["类型", "类数", "用例"],
            [[k, v["classes"], v["tests"]] for k, v in sorted(by_type.items(), key=lambda x: -x[1]["tests"])],
        )
    )
    lines.append("\n## 4. 最慢的 15 个类\n")
    slow = sorted(
        ((cls, rec) for cls, rec in xml_by_class.items() if rec["tests"]),
        key=lambda x: -x[1]["time"],
    )[:15]
    lines.append(
        fmt_table(
            ["类", "用例", "耗时（秒）"],
            [[c, r["tests"], f"{r['time']:.2f}"] for c, r in slow],
        )
    )
    lines.append("\n## 5. 已知缺口 / @Disabled\n")
    gap_rows = []
    for r in gaps:
        gap_rows.append([r["fqcn"], "known-gap 标记", r["reason"]])
    for r in disabled:
        gap_rows.append([r["fqcn"], "@Disabled", r["reason"]])
    lines.append(fmt_table(["类", "标记", "原因"], gap_rows) if gap_rows else "（无）")
    lines.append("\n## 6. 调用真实流水线的测试\n")
    lines.append(
        fmt_table(
            ["类", "用例", "类型"],
            [[r["fqcn"], xml_by_class.get(r["fqcn"], {}).get("tests", 0), r["type"]] for r in pipeline],
        )
        if pipeline
        else "（无）"
    )
    lines.append("\n## 7. 全量类表\n")
    table_rows = []
    for r in sorted(rows, key=lambda x: (x["pkg"], x["name"])):
        x = xml_by_class.get(r["fqcn"], {})
        table_rows.append(
            [
                r["fqcn"],
                r["type"],
                x.get("tests", 0),
                r["raw"],
                r["assertions"],
                r["lines"],
                ",".join(r["tags"]) or "-",
                r["disabled"] or "-",
                "Y" if r["known_gap"] else "-",
                "Y" if r["pipeline"] else "-",
            ]
        )
    lines.append(
        fmt_table(
            ["类", "类型", "用例(XML)", "声明方法", "断言", "行数", "Tag", "Disabled", "KnownGap", "Pipeline"],
            table_rows,
        )
    )
    if args.unreferenced:
        unref = unreferenced_main_classes()
        lines.append("\n## 8. 主类未被测试源码引用（静态近似）\n")
        lines.append(f"共 **{len(unref)} / {len(glob.glob(os.path.join(MAIN_SRC, '**', '*.java'), recursive=True))}** 个。")
        lines.append("\n> 这是字符串引用近似，不等于覆盖率；客户端/注册/替身类天然会命中。\n")
        lines.append("\n".join(f"- `{p}`" for p in unref))

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    if not args.quiet:
        print(f"测试台账已写入 {os.path.relpath(args.out, ROOT)}")
        print(
            f"测试类={len(rows)} 用例={total_tests} 失败={total_failed} "
            f"known-gap={len(gaps)} disabled={len(disabled)} "
            f"pipeline={len(pipeline)} 耗时={total_time:.2f}s"
        )
        print("\n按类型：")
        for k, v in sorted(by_type.items(), key=lambda x: -x[1]["tests"]):
            print(f"  {k:24s} 类={v['classes']:3d} 用例={v['tests']:4d}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

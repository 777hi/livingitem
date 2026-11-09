"""
记录「最近一次全量测试」快照（2026-11-09 起，D-doc-08）。

为什么需要：`build/test-results/test/*.xml` 会被 `./gradlew test --tests` 局部运行覆盖。
原先 `doc_check` 第 2 项拿它和 AGENTS.md 声明的条数比对 ⇒ 改一次文档也得先跑全量测试。
本脚本只认**全量结果**，把它写到独立的 `build/verification/full-test-summary.json`；
局部运行不覆盖快照，`doc_check` 第 2 项只读快照 ⇒ 那条税消失。

全量判据：XML 结果文件数 >= `src/test` 下的 `*Test` / `*IT` 类数，且 tests > 0。
（`@Nested` 会让结果文件多于源文件，所以是 >= 而非 ==；实测 221 结果 / 208 类。）

用法：`python tools/gen_test_summary.py`
      pre-commit 与 CI 在 `doc_check` 之前自动调用，无需手工执行。
"""
import glob
import json
import os
import re
import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "build", "test-results", "test", "TEST-*.xml")
SRC_TEST = os.path.join(ROOT, "src", "test")
SNAPSHOT = os.path.join(ROOT, "build", "verification", "full-test-summary.json")


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def test_class_count():
    n = 0
    for f in glob.glob(os.path.join(SRC_TEST, "**", "*.java"), recursive=True):
        name = os.path.splitext(os.path.basename(f))[0]
        if name.endswith("Test") or name.endswith("IT"):
            n += 1
    return n


def sum_xml(files):
    sums = {"tests": 0, "skipped": 0, "failures": 0, "errors": 0}
    for f in files:
        m = re.search(r"<testsuite[^>]*>", read(f))
        if not m:
            continue
        for k in sums:
            mm = re.search(r'%s="(\d+)"' % k, m.group(0))
            if mm:
                sums[k] += int(mm.group(1))
    return sums


def main():
    files = sorted(glob.glob(RESULTS))
    if not files:
        print("[test-summary] 无测试 XML：保留既有快照（若存在）")
        return 0

    classes = test_class_count()
    sums = sum_xml(files)
    if len(files) < classes or sums["tests"] == 0:
        print(f"[test-summary] 局部运行（{len(files)} 个结果 / {classes} 个测试类）：保留既有快照")
        return 0

    # 全量跑失败也要写快照（记录 failures/errors），否则旧绿快照会掩盖失败。
    # 但结果文件没更新时（FROM-CACHE / UP-TO-DATE，没真跑）不刷新，避免制造「新快照」假象。
    xml_mtime = max(os.path.getmtime(f) for f in files)
    old = None
    if os.path.exists(SNAPSHOT):
        try:
            old = json.loads(read(SNAPSHOT))
        except (ValueError, OSError):
            old = None
    if old and old.get("xmlMTimeMax", 0) >= xml_mtime:
        print("[test-summary] 结果文件未更新（FROM-CACHE / UP-TO-DATE / 未重跑）：保留既有快照")
        return 0

    snap = {
        "schema": 1,
        "source": "full ./gradlew test",
        **sums,
        "xmlFiles": len(files),
        "testClasses": classes,
        "xmlMTimeMax": xml_mtime,
        "recordedAt": datetime.datetime.now().isoformat(timespec="seconds"),
    }
    os.makedirs(os.path.dirname(SNAPSHOT), exist_ok=True)
    with open(SNAPSHOT, "w", encoding="utf-8") as f:
        json.dump(snap, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"[test-summary] 已记录全量快照：{sums['tests']} 条 / "
          f"failures={sums['failures']} errors={sums['errors']} "
          f"（{len(files)} 结果 / {classes} 类）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

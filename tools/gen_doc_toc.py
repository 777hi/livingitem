"""生成/刷新 docs 活跃文档的 H2+H3 可点击目录。

用法：
    python tools/gen_doc_toc.py --dry     # 只报告，不写
    python tools/gen_doc_toc.py           # 写入

口径：
- 锚点按 GitHub slug 规则：小写 → 删除标点（保留空格与 `-`）→ 每个空格各转一个 `-`；
  **不合并连续 `-`**（`文本层 + 图形层` ⇒ `文本层--图形层`）。
- 只收 H2/H3；忽略 H1、H4+、以及 `## 目录` 自身。
- 已有目录：整块替换（保留块尾的 `---` 分隔线）。
- 无目录：插在 `## §0` 之前；没有 §0 则插在 H1 之后。
- 生成物不在范围内（见 SKIP）。
"""

import os, re, sys, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SKIP = {
    "docs/reference/test-map.md",              # gen_test_map.py 生成
    "docs/reference/shared-state.md",          # gen_rosters.py 生成
    "docs/reference/event-threading.md",       # gen_rosters.py 生成
    "docs/reference/container-data-lifecycle.md",  # gen_rosters.py 生成
    "docs/reference/commands.md",               # 快照，仅 2 个 H2
    "docs/reference/subsystem-index.md",        # 无 H2/H3
}

HEAD = re.compile(r"^(#{2,3})\s+(.*\S)\s*$")


def slug(title: str) -> str:
    """GitHub 规则：小写 → 删除标点（保留空格与 `-`）→ 每个空格各转一个 `-`。
    不合并连续 `-`：`文本层 + 图形层` 中间的两个空格 ⇒ `文本层--图形层`。"""
    s = title.strip().lower()
    s = re.sub(r"[^\w\u4e00-\u9fff\s\-]", "", s)
    return s.replace(" ", "-")


def collect(lines):
    """返回 [(level, title)]，跳过 `## 目录` 自身。"""
    out = []
    for line in lines:
        m = HEAD.match(line.strip())
        if not m:
            continue
        title = m.group(2)
        if re.match(r"^目录$", title):
            continue
        out.append((len(m.group(1)), title))
    return out


def build(heads) -> list:
    block = ["## 目录", ""]
    for lvl, txt in heads:
        indent = "" if lvl == 2 else "  "
        block.append(f"{indent}- [{txt}](#{slug(txt)})")
    block.append("")
    return block


def rewrite(text: str):
    lines = text.split("\n")
    heads = collect(lines)
    if not heads:
        return None, 0

    toc_idx = next((i for i, l in enumerate(lines) if re.match(r"^##\s*目录\s*$", l)), None)

    if toc_idx is not None:
        end = next((k for k in range(toc_idx + 1, len(lines)) if re.match(r"^##\s", lines[k])), len(lines))
        had_rule = any(l.strip() in ("---", "***") for l in lines[toc_idx + 1:end])
        # build() 已以空行结尾，分隔线不再补前导空行 —— 否则每跑一次就多一行（非幂等）
        tail = ["---", ""] if had_rule else []
        new = lines[:toc_idx] + build(heads) + tail + lines[end:]
        start = toc_idx
    else:
        anchor = next((i for i, l in enumerate(lines) if re.match(r"^##\s*§0", l)), None)
        if anchor is None:
            anchor = next((i for i, l in enumerate(lines) if re.match(r"^##\s", l)), None)
        if anchor is None:
            return None, 0
        new = lines[:anchor] + build(heads) + ["---", ""] + lines[anchor:]
        start = anchor

    before = len(text)
    out = "\n".join(new)
    return out, len(out) - before


def main():
    dry = "--dry" in sys.argv
    files = []
    for d in ("tech", "system-design", "reference", "guides"):
        files += sorted(glob.glob(os.path.join(ROOT, "docs", d, "*.md")))
    files += sorted(glob.glob(os.path.join(ROOT, "docs", "*.md")))

    total_delta = 0
    changed = 0
    print(f"{'文档':<44}{'标题数':>6}{'行数':>5}{'字符增量':>9}")
    print("-" * 66)
    for p in files:
        rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
        if rel in SKIP:
            continue
        text = open(p, encoding="utf-8").read()
        new, delta = rewrite(text)
        if new is None:
            print(f"{rel.replace('docs/',''):<44}   跳过（无 H2/H3）")
            continue
        heads = collect(text.split("\n"))
        if len(heads) < 3:
            print(f"{rel.replace('docs/',''):<44}   跳过（标题数 < 3，目录无意义）")
            continue
        print(f"{rel.replace('docs/',''):<44}{len(heads):>6}{new.count(chr(10))+1:>5}{delta:>+9,}")
        total_delta += delta
        changed += 1
        if not dry:
            open(p, "w", encoding="utf-8", newline="\n").write(new)

    print("-" * 66)
    print(f"{'合计':<44}{'':>6}{'':>5}{total_delta:>+9,}   （{changed} 份{'' if not dry else '，dry 未写入'}）")


if __name__ == "__main__":
    main()

"""
buffer 状态索引生成器 —— docs/buffer/README.md 的「未定案」表由各文档头部的
「状态：」行自动生成（2026-10-31 起，取代手工维护的导航快照；手工快照实测必漂：
当天审查即发现缺 3 份文件、4 行状态过期）。

状态单一来源 = 各 buffer 文档头部的「状态：」行（前 40 字，与 doc_check 第 11 项同口径）。
改状态 = 改文档头部 + 重跑本脚本；本表没有手工「备注」列，细节写在各文档头部。

⚠️ 本表不含「最后动过」日期列（2026-11-08 删）：该值取自 git 提交日期，而提交动作
本身会改写它 ⇒ 提交完成瞬间生成区即过期，第 12 项必判红，又要重生成 + 再提交。
想知道某份文档何时动过，直接 git log 该文件即可。

用法：
  python tools/gen_buffer_index.py           # 重写 README 的生成区
  python tools/gen_buffer_index.py --check   # 只校验；不一致即退出码 1（doc_check 第 12 项调用）
"""
import os
import re
import sys
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUFFER = os.path.join(ROOT, "docs", "buffer")
README = os.path.join(BUFFER, "README.md")

BEGIN = ("<!-- buffer-index:begin（本区由 tools/gen_buffer_index.py 生成；"
         "改状态请改各文档头部「状态：」行后重跑本脚本，勿手改本区） -->")
END = "<!-- buffer-index:end -->"
HEADING = "## 未定案（保留）"
TABLE_HEAD = ("| 文件 | 状态（头部「状态：」行摘录） |\n"
              "|---|---|")


def status_of(path):
    with open(path, encoding="utf-8") as f:
        head = f.read().split("\n")[:20]
    for line in head:
        # 「状态：」与「**状态**:」两种写法都认（粗体星号可有可无）
        if re.search(r"状态\*{0,2}[:：]", line):
            val = line.split("状态", 1)[-1]
            val = val.lstrip("：: *＊").strip()
            val = val[:40].strip().strip("*＊").strip()
            return val or "（空状态行）"
    return "⚠️ 头部无「状态：」行"


def buffer_docs():
    return [p for p in sorted(glob.glob(os.path.join(BUFFER, "*.md")))
            if os.path.basename(p) != "README.md"]


def build_block(nl):
    rows = ["| [%s](%s) | %s |" % (os.path.basename(p), os.path.basename(p), status_of(p))
            for p in buffer_docs()]
    block = "\n".join([BEGIN, "", HEADING, "", TABLE_HEAD] + rows + ["", END])
    return block.replace("\n", nl)


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError, OSError):
        pass
    with open(README, encoding="utf-8", newline="") as f:
        text = f.read()
    nl = "\r\n" if "\r\n" in text else "\n"
    start = text.find(BEGIN)
    end = text.find(END)
    if start == -1 or end == -1 or end < start:
        if "--check" in sys.argv:
            print("12. buffer 索引      : FAIL（README 缺生成区标记）")
            return 1
        print("README 缺 begin/end 标记对——先手工加入再跑", file=sys.stderr)
        return 2
    want = build_block(nl)
    current = text[start:end + len(END)]
    if "--check" in sys.argv:
        if current == want:
            print("12. buffer 索引      : OK（%d 份）" % len(buffer_docs()))
            return 0
        print("12. buffer 索引      : FAIL（与各文档头部状态行不一致 —— 跑 python tools/gen_buffer_index.py）")
        return 1
    with open(README, "w", encoding="utf-8", newline="") as f:
        f.write(text[:start] + want + text[end + len(END):])
    print("buffer 索引已重生成（%d 份）" % len(buffer_docs()))
    return 0


if __name__ == "__main__":
    sys.exit(main())

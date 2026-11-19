"""
buffer 状态索引生成器 —— docs/buffer/README.md 的「未定案」表由各文档头部的
「状态：」行自动生成（2026-10-31 起，取代手工维护的导航快照；手工快照实测必漂：
当天审查即发现缺 3 份文件、4 行状态过期）。

状态单一来源 = 各 buffer 文档头部的「状态：」行，取其中的**状态句**
（到第一个「——」/「；」为止；口径见 buffer/README「状态行口径」）。
**status_sentence 是显示与 doc_check 第 11 项检测的唯一实现** —— 2026-11-18 收口：
此前两处各写一份（显示 40 字硬截断、检测 40 字窗口），导致截断无省略号、
粗体「**状态**：」对第 11 项失明、后置完成词漏检。
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

# 状态行识别：`> 状态：` 与 `> **状态**：` 两种写法都认（粗体星号可有可无）。
STATUS_HEAD_RE = re.compile(r"状态\*{0,2}\s*[:：]")
# 状态句分界：体例是「<性质> —— <指针 / 边界说明>」，「——」/「；」之后不属于状态本身。
STATUS_SEPARATORS = ("——", "；")
# 表格显示上限：正常状态句远小于此；超长才截断，且必须带省略号（读者要能分辨截断）。
STATUS_DISPLAY_MAX = 80


def status_sentence(path):
    """状态句：从「状态：」到第一个「——」/「；」为止。

    **显示与第 11 项滞留检测的唯一口径** —— 检测看完整状态句，不用固定字符窗口
    （窗口会漏掉后置完成词；粗体状态行曾因另一份实现的正则不认 `**` 而整行失明）。
    """
    with open(path, encoding="utf-8") as f:
        head = f.read().split("\n")[:20]
    for line in head:
        if STATUS_HEAD_RE.search(line):
            val = STATUS_HEAD_RE.split(line, 1)[1]
            for sep in STATUS_SEPARATORS:
                if sep in val:
                    val = val.split(sep, 1)[0]
                    break
            return val.strip().strip("*＊").strip()
    return ""


def status_display(path):
    """README 表格摘要：完整状态句；仅当超过上限才截断，且带省略号。"""
    s = status_sentence(path)
    if not s:
        return "⚠️ 头部无「状态：」行"
    if len(s) > STATUS_DISPLAY_MAX:
        s = s[:STATUS_DISPLAY_MAX].rstrip() + "…"
    return s


def buffer_docs():
    return [p for p in sorted(glob.glob(os.path.join(BUFFER, "*.md")))
            if os.path.basename(p) != "README.md"]


def build_block(nl):
    rows = ["| [%s](%s) | %s |" % (os.path.basename(p), os.path.basename(p), status_display(p))
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

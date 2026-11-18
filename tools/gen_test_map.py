"""
测试文件树生成器 —— docs/reference/test-map.md 的树形名录由 src/test 扫描生成
（2026-11-18 起，取代手维护名录；实测手工名录漏列过两个新增测试类，而校验只在跑 doc_check 时事后报错）。

分工（遵循「机器只印它真能推导的」，规约见 docs/README.md「该不该写：三条判据」）：
  - 名录（目录 + `*Test`/`*IT` 文件名）：100% 机器可推 ⇒ 生成。
  - 每类的「守什么」：只搬运**源码里已有的类 javadoc 首句**（作者本人写的事实），
    机器**不判断契约对错、不浓缩 @DisplayName**。无类 javadoc 的类如实标注「源码无类注释」。
  - 用例数：不进本表（由 tools/test_inventory.py 生成），与本生成器无关。

为什么描述不手写：手写描述是源码 @DisplayName 的转述，会随测试改动而腐烂，且腐烂时无症状
（doc_check 旧第 3 项只比对文件名集合，不管每行写了什么 —— 同 gen_rosters.py 的教训）。

用法：
  python tools/gen_test_map.py            # 重写 test-map.md 的生成区
  python tools/gen_test_map.py --check    # 只校验；不一致即退出码 1（doc_check 第 3 项调用）
"""
import os
import re
import sys
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "reference", "test-map.md")
TESTS = os.path.join(ROOT, "src", "test")
ANCHOR = "src/test/java/com/qiqi/li/"

BEGIN = ("<!-- test-map:begin（本区由 tools/gen_test_map.py 从 src/test 扫描生成；"
         "测试类增删 = 改代码后重跑本脚本，勿手改本区） -->")
END = "<!-- test-map:end -->"

NO_DOC = "⚠️ 源码无类注释"

# 类 javadoc：紧邻 class 声明前的 /** ... */（容忍中间注解与修饰符）
JAVADOC = re.compile(
    r"/\*\*(.*?)\*/\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|final\s+|abstract\s+)*class\s+\w+\b", re.S)


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def first_sentence(javadoc):
    """取 javadoc 第一句：去行首星号、块标签、内联标签，只留纯文本首句。机器搬运，不改写语义。"""
    s = "\n".join(re.sub(r"^\s*\*\s?", "", line) for line in javadoc.splitlines())
    s = re.split(r"<p\s*>", s, maxsplit=1, flags=re.I)[0]  # <p> 之后是补充，不属首句
    s = re.sub(r"\{@code\s+([^}]*)\}", r"\1", s)
    s = re.sub(r"\{@link\s+([^}]*)\}", r"\1", s)
    s = re.sub(r"<[^>]+>", " ", s)
    # 剥离链接与仓库路径：javadoc 的相对链接以**源码文件**为基准，搬进 docs/reference/ 后
    # 基准变化必然断链（doc_check 第 1 项会报）。机器只搬运文本事实，链接解析不是它的职责
    # ——原链接仍留在源码 javadoc 里可点，这里只留可读文本。
    s = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", s)  # [text](url) → text
    s = re.sub(r"(?:[\w.\-]+/)*(?:docs|src)/(?:[\w./\-一-龥]+/)*([\w\-一-龥]+)\.(?:md|java)",
               r"\1", s)  # docs/x/y.md → y
    s = s.replace("&nbsp;", " ").replace("**", "")
    s = re.sub(r"\s+", " ", s).strip()
    m = re.search(r"[。！？]|(?<!\d)\.(?:\s|$)", s)
    if m:
        s = s[:m.end()].strip()
    if len(s) > 100:
        s = s[:100].rstrip() + "…"
    return s


def desc_of(path, name):
    """机器可推的描述：源码已有类 javadoc 的首句；无则如实标注。"""
    try:
        src = _read(path)
    except OSError:
        return NO_DOC
    # 锁定目标类的 javadoc：先找 class 声明位置，再看紧邻其前的注释块
    decl = re.search(r"(?:public\s+|final\s+|abstract\s+)*class\s+" + re.escape(name) + r"\b", src)
    if not decl:
        return NO_DOC
    before = src[:decl.start()]
    blocks = list(re.finditer(r"/\*\*(.*?)\*/\s*$", before, re.S))
    if not blocks:
        return NO_DOC
    sentence = first_sentence(blocks[-1].group(1))
    return sentence or NO_DOC


def _tests():
    """(相对目录, 文件名, 描述)；收 src/test 下全部 .java（测试类 + 辅助 / 替身类）。

    为何连辅助类也收：原文档列了 `FakeContainerContext`（测试替身，有导航价值），
    doc_check 旧口径的正则匹配不到它 ⇒ 它一直是文档里的自由文本、不受校验。
    生成制下按源码全集生成，一并纳入并标注类型，比"只收 Test/IT"更完整且同样机器可推。
    """
    out = []
    base = os.path.join(ROOT, "src", "test", "java", "com", "qiqi", "li")
    for path in glob.glob(os.path.join(base, "**", "*.java"), recursive=True):
        name = os.path.basename(path)[:-5]
        rel_dir = os.path.relpath(os.path.dirname(path), base).replace(os.sep, "/")
        if rel_dir == ".":
            rel_dir = ""
        desc = desc_of(path, name)
        if not (name.endswith("Test") or name.endswith("IT")):
            desc = "【辅助类·非测试】" + (desc if desc != NO_DOC else "测试替身/夹具，非 Test/IT")
        out.append((rel_dir, os.path.basename(path), desc))
    return out


def _tree(tests):
    root = {"files": [], "dirs": {}}
    for rel_dir, filename, desc in tests:
        node = root
        if rel_dir:
            for part in rel_dir.split("/"):
                node = node["dirs"].setdefault(part, {"files": [], "dirs": {}})
        node["files"].append((filename, desc))

    def render(node, prefix):
        lines = []
        files = sorted(node["files"])
        dirs = sorted(node["dirs"])
        total = len(files) + len(dirs)
        i = 0
        for filename, desc in files:
            i += 1
            branch = "└── " if i == total else "├── "
            lines.append(prefix + branch + filename + "  # " + desc)
        for d in dirs:
            i += 1
            branch = "└── " if i == total else "├── "
            lines.append(prefix + branch + d + "/")
            lines.extend(render(node["dirs"][d], prefix + ("    " if i == total else "│   ")))
        return lines

    return ["```", ANCHOR] + render(root, "") + ["```"]


def build_block(nl="\n"):
    body = "\n".join(_tree(_tests()))
    return (BEGIN + "\n" + body + "\n" + END).replace("\n", nl)


def check_region(doc_path, builder):
    """返回 (状态, 说明)，状态 ∈ ok / stale / missing。与 gen_rosters.py 同构。"""
    if not os.path.exists(doc_path):
        return "missing", "文档不存在"
    with open(doc_path, encoding="utf-8", newline="") as f:
        text = f.read()
    start, finish = text.find(BEGIN), text.find(END)
    if start == -1 or finish == -1 or finish < start:
        return "missing", "缺 begin/end 生成区标记"
    nl = "\r\n" if "\r\n" in text else "\n"
    want = builder().replace("\n", nl)
    return ("ok", "") if text[start:finish + len(END)] == want else ("stale", "生成区与源码不一致")


def count():
    return len(_tests())


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError, OSError):
        pass
    with open(DOC, encoding="utf-8", newline="") as f:
        text = f.read()
    nl = "\r\n" if "\r\n" in text else "\n"
    start, finish = text.find(BEGIN), text.find(END)
    if start == -1 or finish == -1 or finish < start:
        print("3. 测试树一致性    : FAIL（test-map.md 缺 begin/end 生成区标记）")
        return 1
    want = build_block(nl)
    if "--check" in sys.argv:
        if text[start:finish + len(END)] == want:
            print("3. 测试树一致性    : OK（%d 个测试类 · 名录与描述均由源码生成）" % count())
            return 0
        print("3. 测试树一致性    : FAIL（生成区与源码不一致 —— 跑 python tools/gen_test_map.py）")
        return 1
    with open(DOC, "w", encoding="utf-8", newline="") as f:
        f.write(text[:start] + want + text[finish + len(END):])
    print("测试树已重生成（%d 个测试类）" % count())
    return 0


if __name__ == "__main__":
    sys.exit(main())

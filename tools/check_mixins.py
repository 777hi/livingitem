#!/usr/bin/env python3
"""Mixin 铁律校验 —— 违规即退出码 1（可挂 CI / 提交前检查）。

把 AGENTS.md「Mixin 铁律」落成可复算的判据（先例：check_layers.py 校验分层铁律）。
纯文本扫描，不需要编译整个项目（编译只查语法，查不出「运行到创造标签页才崩」这类问题）。

规则
----
R1  `@Accessor` / `@Invoker` 方法名必须带 `living_item$` 前缀。
    为什么：Mixin 以「在目标类上生成的合成方法名」为冲突判定键，通用名
    （原版字段派生 / 短名）会被其它模组撞名 ⇒ Incompatible @Accessor ⇒ MixinApplyError
    （2026-10-11 SlotWrapperAccessor.getTarget 实测崩溃，与 inventoryessentials 撞）。

R2  `@Accessor` / `@Invoker` 必须显式指定目标成员：`@Accessor("字段名")` / `@Invoker("方法名")`。
    为什么：Mixin 靠方法名 get/is/set（Invoker：call）前缀**推断**目标成员名，
    方法名带 living_item$ 前缀后推断失效 ⇒ InvalidAccessorException 启动即 FATAL
    （2026-10-11 AbstractContainerScreenAccessor 实测踩坑）。
    R1 与 R2 必须成对出现，缺一即崩。

R3  每个 `src/main/resources/*mixins*.json` 的条目必须能解析到真实源文件
    （package + 条目名 → .java 存在；plugin 类同理）。防「改了类名忘了改配置」。

R4  每个 `@Mixin` 源文件必须登记在某个 *mixins*.json 里。防「写了 mixin 忘了注册」
    ⇒ 加载不到、注入静默失效（required=true 会崩，但 loose 配置只会静默）。

W1  （仅警告，不判失败）`@Shadow` 成员名**不得**带 `living_item$` 前缀 ——
    shadow 是「别名目标已有成员」，必须与目标成员同名，加前缀 = 解析不到目标。
    与 R1 方向相反，别混淆：注入器 handler / @Accessor/@Invoker 方法名可加前缀，
    @Shadow 不行（它不生成新成员，也无碰撞风险）。

已知局限
----
- 注释先剥离后再扫描；字符串字面量里的 "@Accessor" 字样不剥（现库无此场景）。
- 方法名取「注解后到下一个 `;` 之间的第一个 标识符(」——注解与方法声明之间
  再插带参注解会误取（现库无此写法；真误报时看 file:line 即可人工复核）。

用法
----
    python tools/check_mixins.py        # 校验（有 R 级违例 ⇒ 退出 1）
"""
from __future__ import annotations

import glob
import io
import json
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_JAVA = os.path.join(ROOT, "src", "main", "java")
SRC_RES = os.path.join(ROOT, "src", "main", "resources")

PREFIX = "living_item$"

# ---------------------------------------------------------------- 基础工具

COMMENT_RE = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)


def strip_comments(text: str) -> str:
    """剥离 // 与 /* */（保留换行数，行号不失真）。"""
    return COMMENT_RE.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)


def iter_java_files():
    for dirpath, _, filenames in os.walk(SRC_JAVA):
        for name in filenames:
            if name.endswith(".java"):
                yield os.path.join(dirpath, name)


def read(path: str) -> str:
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def rel(path: str) -> str:
    return os.path.relpath(path, ROOT).replace("\\", "/")


# ---------------------------------------------------------------- 成员声明扫描
#
# 匹配 `@Accessor` / `@Invoker`（含可选参数），再取「注解后到下一个 ; 之间
# 第一个 标识符(」作为方法名。@Accessor("x") 与裸 @Accessor 都能吃到。
ANNOTATION_RE = re.compile(r"@(Accessor|Invoker)\b\s*(\(([^)]*)\))?")


def scan_members(text: str):
    """产出 (kind, method_name, explicit_name_or_None, line)。"""
    for m in ANNOTATION_RE.finditer(text):
        kind = m.group(1)
        args = (m.group(3) or "").strip()
        explicit = None
        if args:
            vm = re.search(r"""["']([^"']*)["']""", args)
            explicit = vm.group(1) if vm else args  # 非字符串参数（异常写法）当作已显式
        tail = text[m.end():]
        semi = tail.find(";")
        seg = tail[:semi + 1] if semi >= 0 else tail
        nm = re.search(r"([A-Za-z_$][\w$]*)\s*\(", seg)
        name = nm.group(1) if nm else ""
        yield kind, name, explicit, line_of(text, m.start())


# ---------------------------------------------------------------- R3/R4：配置 ↔ 源码

LIST_KEYS = ("mixins", "client", "server")


def load_configs():
    """返回 [(config_path, package, {entry})] 与坏 JSON 列表。"""
    configs, broken = [], []
    for path in sorted(glob.glob(os.path.join(SRC_RES, "*mixins*.json"))):
        try:
            data = json.loads(read(path))
        except Exception as e:  # noqa: BLE001
            broken.append((rel(path), str(e)))
            continue
        pkg = data.get("package", "")
        entries = {e for k in LIST_KEYS for e in data.get(k, [])}
        configs.append((path, pkg, entries, data))
    return configs, broken


def collect_mixin_files():
    """返回 [(path, package, simple_name)]：所有含 @Mixin 的源文件。"""
    out = []
    for path in iter_java_files():
        text = strip_comments(read(path))
        if "@Mixin" not in text:
            continue
        pm = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        out.append((path, pm.group(1) if pm else "",
                    os.path.splitext(os.path.basename(path))[0]))
    return out


# ---------------------------------------------------------------- 主流程

def main() -> int:
    r1, r2, r3, r4, w1 = [], [], [], [], []

    member_files = 0
    for path in iter_java_files():
        text = strip_comments(read(path))
        if "@Accessor" not in text and "@Invoker" not in text:
            continue
        file_has_member = False
        for kind, name, explicit, line in scan_members(text):
            file_has_member = True
            loc = "%s:%d" % (rel(path), line)
            if not name.startswith(PREFIX):
                r1.append("%s  @%s 方法名 `%s` 缺 `%s` 前缀" % (loc, kind, name, PREFIX))
            if explicit is None:
                r2.append("%s  裸 @%s（无显式成员名）：带前缀后推断必失效 ⇒ InvalidAccessorException"
                          % (loc, kind))
        if file_has_member:
            member_files += 1

    # W1：@Shadow 带前缀（反向规则）
    for path in iter_java_files():
        text = strip_comments(read(path))
        if "@Shadow" not in text or PREFIX not in text:
            continue
        for m in re.finditer(r"@Shadow\b", text):
            semi = text.find(";", m.start())
            seg = text[m.start():semi + 1] if semi >= 0 else text[m.start():m.start() + 200]
            nm = re.search(r"([A-Za-z_$][\w$]*)\s*(?:=|;|\()", seg)
            if nm and nm.group(1).startswith(PREFIX):
                w1.append("%s:%d  @Shadow 成员 `%s` 不应带 %s 前缀（必须与目标成员同名）"
                          % (rel(path), line_of(text, m.start()), nm.group(1), PREFIX))

    configs, broken = load_configs()
    for loc, err in broken:
        r3.append("%s  JSON 解析失败：%s" % (loc, err))

    registered = set()
    config_count = 0
    for path, pkg, entries, data in configs:
        config_count += 1
        base = os.path.join(SRC_JAVA, pkg.replace(".", os.sep)) if pkg else SRC_JAVA
        for entry in sorted(entries):
            if not os.path.isfile(os.path.join(base, entry + ".java")):
                r3.append("%s  条目 `%s` 在包 %s 下找不到源文件" % (rel(path), entry, pkg))
        registered |= {(pkg, e) for e in entries}
        plugin = data.get("plugin")
        if plugin and not os.path.isfile(os.path.join(SRC_JAVA, plugin.replace(".", os.sep) + ".java")):
            r3.append("%s  plugin `%s` 找不到源文件" % (rel(path), plugin))

    mixin_files = collect_mixin_files()
    for path, pkg, simple in mixin_files:
        if (pkg, simple) not in registered:
            r4.append("%s  @Mixin 类未登记进任何 *mixins*.json（package=%s）" % (rel(path), pkg))

    # ---------------------------------------------------------------- 输出
    print("Mixin 铁律校验（tools/check_mixins.py）")
    print("-" * 60)
    ok = True
    for rid, title, items in (
        ("R1", "访问器/调用器方法名带 %s 前缀" % PREFIX, r1),
        ("R2", "访问器/调用器显式指定目标成员", r2),
        ("R3", "mixins.json 条目/插件 ↔ 源文件一致", r3),
        ("R4", "每个 @Mixin 源文件都已登记", r4),
    ):
        if items:
            ok = False
            print("[FAIL] %s %s —— %d 项违例" % (rid, title, len(items)))
            for it in items:
                print("       " + it)
        else:
            print("[ OK ] %s %s" % (rid, title))

    if w1:
        print("[WARN] W1 @Shadow 前缀（建议级，不影响退出码）—— %d 项" % len(w1))
        for it in w1:
            print("       " + it)

    print("-" * 60)
    print("扫描：@Accessor/@Invoker 成员文件 %d 个；mixin 源文件 %d 个；配置 %d 份"
          % (member_files, len(mixin_files), config_count))
    if ok:
        print("全部通过。")
        return 0
    print("存在违例 ⇒ 退出码 1（详见 AGENTS.md「Mixin 铁律」）。")
    return 1


if __name__ == "__main__":
    sys.exit(main())

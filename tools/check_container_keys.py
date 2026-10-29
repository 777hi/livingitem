#!/usr/bin/env python3
"""容器键解析收敛校验 —— 违规即退出码 1（可挂进 CI / 提交前检查）。

**它守的是什么**
容器键是字符串协议（`player_<uuid>` / `player_<uuid>_ender_chest` / `chest_x_y_z` / …），
解析必须集中在 `living/container/ContainerKeys.java`（唯一入口，2026-10-13 立）。

2026-10-13 一次性暴露 3 处同源 bug —— 全部因为消费方各自 `startsWith` / `substring` 反推：
  ① 背包守卫把「开着背包界面」误判为「没开界面」⇒ 背包 tooltip 不刷新；
  ② 末影箱 UUID 解析漏剥 `_ender_chest` 后缀 ⇒ 抛异常被吞 ⇒ **从不发包**；
  ③ 客户端按「是不是 InventoryScreen」推断种类 ⇒ 与服务端键前缀判断**相反** ⇒ 读写错位。

**为什么只查这两类（窄而准 > 宽而吵）**
`startsWith("chest_")` 之类在领域内部可能语义合法，全量禁止会大量误报；
下面两类是**键形解析的确凿信号**，收敛后合法出现点极少 ⇒ 零误报：
  C1 `UUID.fromString(`      —— 只许 ContainerKeys（+ 常量 UUID 白名单）
  C2 键前缀字面量 startsWith —— 只许 ContainerKeys
注释行（`*` / `//` 开头）跳过 —— 文档里会引用这些写法。

用法
----
    python tools/check_container_keys.py        # 校验（违规 ⇒ 退出 1）
    python tools/check_container_keys.py -v     # 连出现点一起列
"""
from __future__ import annotations

import os
import re
import sys

# Windows 默认控制台是 GBK，结论行里的 ✓ / ✗ 会抛 UnicodeEncodeError
# （8 项全绿也打不出「通过」，只剩 traceback）。与 check_mixins.py 同款处理。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java")

# 白名单：允许出现「键形解析」的文件
UUID_ALLOW = {
    "ContainerKeys.java",         # 唯一 UUID 解析点
    "LivingToolFakePlayer.java",  # 常量 UUID（假玩家 fallback，与键形无关）
}
PREFIX_ALLOW = {
    "ContainerKeys.java",         # 唯一键前缀判定点
}

UUID_RE = re.compile(r"UUID\.fromString\s*\(")
PREFIX_RE = re.compile(r'startsWith\s*\(\s*"(?:player_|chest_|container_|item_entity_|entity_)')


def is_comment(line: str) -> bool:
    s = line.strip()
    return s.startswith("*") or s.startswith("//")


def main() -> int:
    verbose = "-v" in sys.argv
    violations = []
    for dirpath, _dirs, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, encoding="utf-8") as f:
                for n, line in enumerate(f, 1):
                    if is_comment(line):
                        continue
                    if UUID_RE.search(line) and fn not in UUID_ALLOW:
                        violations.append((path, n, "C1", line.strip()))
                    if PREFIX_RE.search(line) and fn not in PREFIX_ALLOW:
                        violations.append((path, n, "C2", line.strip()))

    def rel(p: str) -> str:
        return os.path.relpath(p, ROOT).replace("\\", "/")

    if violations:
        print(f"!! 容器键解析散落 {len(violations)} 处（应收敛到 ContainerKeys）")
        for path, n, rule, text in violations:
            print(f"   [{rule}] {rel(path)}:{n}  {text[:100]}")
        print("\n修法：改用 ContainerKeys#kindOf / #ownerOf / #positionsOf / #isOwnedByPlayer")
        return 1
    if verbose:
        print("OK 容器键解析：无散落（唯一入口 ContainerKeys）")
    print("✓ 通过（容器键解析收敛）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

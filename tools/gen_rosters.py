"""
参考层「名单」生成器 —— docs/reference/shared-state.md 与 event-threading.md 的
**成员名单**由源码扫描生成（2026-11-01 起，与 tools/gen_buffer_index.py 同构：
生成区 + begin/end 标记 + `--check`，由 doc_check 第 13 / 14 项调用）。

为什么把名单交给机器、把判断收归人手：
这两张表原先是**多列手写表格**（内容 / 线程 / 建立 / 清理 / 持久化 / 备注）。
其中只有「有没有这个成员」是机器可推的；其余列全是**对源码的复述** ——
复述会腐烂，而腐烂时没有任何症状：doc_check 13~14 只校验成员名是否出现，
**不校验那一行写了什么**。2026-11-01 的 A/B 代理实验实测两处错行绿灯通过
（`ChunkEvent.Unload`「异步线程」实为主线程；`ItemFormLiveViews`「每 tick 重建」实为惰性过期）。

⇒ 分工重划：**名单（机器）/ 判断（人，且必须带判据）**。
本脚本只印它真能推导的三件事：成员名、该类是否登记停服 / 登出清理、处理器所在的包。
**线程 / 清理时机 / 持久化一律不生成** —— 那些是手写判断，写在各文档的「判断清单」里；
没有判据的判断不允许存在（规约见 docs/README.md「该不该写：一条判据」）。

用法：
  python tools/gen_rosters.py            # 重写两份文档的生成区
  python tools/gen_rosters.py --check    # 只校验；不一致即退出码 1（doc_check 用）
"""
import os
import re
import sys
import glob

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import doc_check as dc  # noqa: E402  复用同一份扫描定义与排除集，避免出现第二个真相

ROOT = dc.ROOT
SHARED_DOC = os.path.join(ROOT, "docs", "reference", "shared-state.md")
EVENT_DOC = os.path.join(ROOT, "docs", "reference", "event-threading.md")

BEGIN = ("<!-- rosters:begin（本区由 tools/gen_rosters.py 从源码扫描生成；"
         "名单变化 = 改代码后重跑本脚本，勿手改本区） -->")
END = "<!-- rosters:end -->"

# 处理器名 + 事件类型（事件类型 = 第一个参数的简单名）
SUBSCRIBE = re.compile(
    r'@SubscribeEvent\s+(?:(?:public|private|protected|static|final)\s+)*void\s+'
    r'([A-Za-z_]\w*)\s*\(\s*(?:[\w.]+\.)?([A-Za-z_]\w*)\s+\w+')

# 与 doc_check 第 13 项同口径的两个源码根目录
SHARED_ROOTS = ("living", "network")


def _all_java():
    return glob.glob(os.path.join(ROOT, "src", "main", "java", "**", "*.java"), recursive=True)


def _by_owner():
    """owner -> (path, src)；同名类取第一个（本库无重名类）。"""
    out = {}
    for path in _all_java():
        owner = os.path.splitext(os.path.basename(path))[0]
        if owner in out:
            continue
        try:
            out[owner] = (path, dc.read(path))
        except OSError:
            continue
    return out


def _stop_hook(src):
    """机器可推：该类自己文件里的清理入口。三态，且**不等于**「有没有清理」——
    只描述「走没走 `StaticCacheRegistry` 统一分发」。判据都取本类文件内可见的事实。"""
    hooks = []
    if "StaticCacheRegistry.onServerStop" in src:
        hooks.append("登记")
    elif "ServerStoppedEvent" in src:
        # 自己 @SubscribeEvent 处理停服事件（不走统一分发，但确实清了）
        hooks.append("自带停服事件")
    if "StaticCacheRegistry.onClientLogout" in src:
        hooks.append("登出")
    elif "ClientPlayerNetworkEvent" in src:
        hooks.append("自带登出事件")
    return " / ".join(hooks) if hooks else "⚠️ 本类无清理入口"


def _is_client(path):
    return os.sep + "li" + os.sep + "client" + os.sep in os.sep + path


def build_shared_block():
    owners = _by_owner()
    rows, count = [], 0
    for key in sorted(dc._scan_shared_state_keys()):
        owner, field = key.split("#", 1)
        if owner in dc._SHARED_EXCLUDED_CLASSES or key in dc._SHARED_EXCLUDED_FIELDS:
            continue
        count += 1
        path, src = owners.get(owner, ("", ""))
        rows.append((owner, field, _stop_hook(src)))
    # 同类字段并成一行，避免「一字段一行」把名单拉成 29 行
    grouped = {}
    for owner, field, hook in rows:
        grouped.setdefault(owner, [hook, []])[1].append(field)
    table = ["| 类 | 清理入口（本类文件内可见的事实） | 跨 tick 可变 static 集合 |", "|---|---|---|"]
    for owner in sorted(grouped):
        hook, fields = grouped[owner]
        table.append("| `%s` | %s | %s |"
                     % (owner, hook, " ".join("`%s`" % f for f in sorted(fields))))

    excluded = "、".join("`%s`" % c for c in sorted(dc._SHARED_EXCLUDED_CLASSES))
    fields_x = "、".join("`%s`" % c for c in sorted(dc._SHARED_EXCLUDED_FIELDS))
    universe = [
        "",
        "**论域**（本表覆盖范围的完整定义，超出论域的问题本表**不回答**）：",
        "",
        "- 扫描根目录：`src/main/java/com/qiqi/li/{living,network}/`。",
        "- 扫描形态：`static final` 的 `Map` / `Set` / `List` / `Queue` / `Deque` / "
        "`ConcurrentHashMap` / 名字以 `Cache` 结尾的类型；初始化式含 `Map.of` / `List.of` / "
        "`Set.of` / `Collections.unmodifiable*` / `Codec` / `StreamCodec` / `TypeToken` 的直接跳过。",
        "- 按**类**排除（配置 / 启动期注册表 / 反射缓存 / 纯客户端 / 诊断，共 %d 个，"
        "**这些类里的集合一律不收录**）：%s。" % (len(dc._SHARED_EXCLUDED_CLASSES), excluded),
        "- 按**字段**排除（共 %d 个）：%s。" % (len(dc._SHARED_EXCLUDED_FIELDS), fields_x or "（无）"),
        "- **「清理入口」列只有三种取值，且只描述本类文件内看得见的事实**："
        "`登记` = 走了 `StaticCacheRegistry` 统一分发；`自带停服事件` = 自己 `@SubscribeEvent` "
        "处理 `ServerStoppedEvent`（效果相同，只是没走分发）；`⚠️ 本类无清理入口` = 本类文件里"
        "两种都看不见 —— **这一列不是「有没有泄漏」的结论**，泄漏判断见下方判断清单（那里带判据）。",
        "- ⚠️ 因此「哪些表没有停服清理」这类全称问句，答案只在**上表 + 上列排除集**内成立；"
        "排除集里的类不受本表约束（它们的清理判据见下方判断清单）。",
    ]
    return "\n".join(table + universe)


def build_event_block():
    server, client = {}, {}
    for path in _all_java():
        owner = os.path.splitext(os.path.basename(path))[0]
        try:
            src = dc.read(path)
        except OSError:
            continue
        bucket = client if _is_client(path) else server
        for m in SUBSCRIBE.finditer(src):
            bucket.setdefault(owner, set()).add("`%s`(`%s`)" % (m.group(1), m.group(2)))
    table = ["| 类（按所在包分桶，**不代表线程**） | `@SubscribeEvent` 处理器（事件类型） |",
             "|---|---|"]
    for title, bucket in (("服务端 / 领域包", server),
                          ("客户端包 `com/qiqi/li/client/`（客户端侧，不在服务器 tick 线程）", client)):
        table.append("| ▸ **%s** | |" % title)
        for owner in sorted(bucket):
            table.append("| `%s` | %s |" % (owner, " ".join(sorted(bucket[owner]))))
    total = sum(len(v) for v in server.values()) + sum(len(v) for v in client.values())
    note = ["", "**共 %d 个处理器**。本名单不含线程判断 —— 想知道某个处理器在哪个线程、"
            "能不能碰共享状态，看上方三条硬规则与 §2 的例外清单；名单里查不到的约束就是**没写过的约束**，"
            "不等于「无约束」。" % total]
    return "\n".join(table + note)


def _splice(doc_path, body, check):
    with open(doc_path, encoding="utf-8", newline="") as f:
        text = f.read()
    nl = "\r\n" if "\r\n" in text else "\n"
    start, finish = text.find(BEGIN), text.find(END)
    if start == -1 or finish == -1 or finish < start:
        return None, "%s 缺 begin/end 标记对" % os.path.basename(doc_path)
    want = BEGIN + "\n" + body.replace("\n", nl) + "\n" + END
    current = text[start:finish + len(END)]
    if check:
        return current == want, None
    with open(doc_path, "w", encoding="utf-8", newline="") as f:
        f.write(text[:start] + want + text[finish + len(END):])
    return True, None


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError, OSError):
        pass
    check = "--check" in sys.argv
    jobs = ((SHARED_DOC, build_shared_block()), (EVENT_DOC, build_event_block()))
    results = []
    for path, body in jobs:
        ok, err = _splice(path, body, check)
        if err:
            print("13/14. 参考层名单    : FAIL（%s）" % err)
            return 1
        results.append(ok)
    n = len(dc._scan_shared_state_keys())
    if check:
        if all(results):
            print("13/14. 参考层名单    : OK（扫描 %d 个候选成员 · 两份生成区与源码一致）" % n)
            return 0
        print("13/14. 参考层名单    : FAIL（生成区与源码不一致 —— 跑 python tools/gen_rosters.py）")
        return 1
    print("参考层名单已重生成（扫描 %d 个候选成员）" % n)
    return 0


# ---------------------------------------------------------------- 供 doc_check 调用

def shared_roster_count():
    return sum(1 for k in dc._scan_shared_state_keys()
               if k.split("#", 1)[0] not in dc._SHARED_EXCLUDED_CLASSES
               and k not in dc._SHARED_EXCLUDED_FIELDS)


def event_roster_count():
    n = 0
    for path in _all_java():
        try:
            src = dc.read(path)
        except OSError:
            continue
        n += len(set(m.group(1) for m in SUBSCRIBE.finditer(src)))
    return n


def check_region(doc_path, builder):
    """单个文档的生成区状态。返回 (状态, 说明)：状态 ∈ ok / stale / missing。

    这是比旧版第 13/14 项**更强**的判据：旧版只要求成员名在文档里出现过（手写表格也能满足，
    而且行内容错了照样绿灯）。改成「生成区必须与源码扫描结果逐字一致」后，
    名单这一层**不可能出自人手**，也就不可能携带人手的过期复述。
    """
    if not os.path.exists(doc_path):
        return "missing", "文档不存在"
    with open(doc_path, encoding="utf-8", newline="") as f:
        text = f.read()
    start, finish = text.find(BEGIN), text.find(END)
    if start == -1 or finish == -1 or finish < start:
        return "missing", "缺 begin/end 标记对"
    nl = "\r\n" if "\r\n" in text else "\n"
    want = BEGIN + "\n" + builder().replace("\n", nl) + "\n" + END
    return ("ok", "") if text[start:finish + len(END)] == want else ("stale", "生成区与源码不一致")


if __name__ == "__main__":
    sys.exit(main())

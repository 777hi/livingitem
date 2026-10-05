#!/usr/bin/env python3
"""从 Java 源码生成「代码关系图」（单页离线 HTML）。

为什么需要它
------------
开发中逻辑散落各处、上下游纠缠，靠 grep 只能看到「这个类被谁提到」，
看不到「改动会沿哪条链传导」。文档系统回答的是「该读哪篇」，
本工具回答的是「改这个类会牵动谁」—— 两者互补，不是一回事。

它做什么
--------
扫描 src/main/java，按文件抽取：

  * 包 / 类名 / 类型（class|interface|enum|record）
  * 类级 javadoc 摘要（鼠标悬停可见）
  * extends / implements / permits / record 组件类型  → 边类型 ``inherit``
  * import 与正文里提到的本项目类                       → 边类型 ``use``
  * ``@Mixin(X.class)`` 的目标类（原版 / 第三方）        → 边类型 ``mixin``
                                                         + 生成 ``external`` 节点

输出 ``build/code-map.html``：自包含、离线可开、可点击。
点任一节点 → 高亮它的「上游（谁依赖我）」与「下游（我依赖谁）」，
其余淡出 —— 这是把毛线团拆开的关键。

用法
----
    python tools/gen_code_map.py                # 默认输出 build/code-map.html
    python tools/gen_code_map.py --out x.html
    python tools/gen_code_map.py --json         # 同时落一份 build/code-map.json

    # 给 **AI** 用的文本查询（不产出 HTML，直接解析源码 ⇒ 不存在读到过期图的问题）
    python tools/gen_code_map.py --query ContainerFluidData
    python tools/gen_code_map.py --query living/transfer

设计约束
--------
* **纯派生**：图完全由源码算出，不存任何手工维护的数据 ⇒ 不可能漂移。
* **只读**：不修改任何源码；输出落在 build/（已 gitignore）。
* **无第三方依赖**：只用标准库；前端不依赖 CDN，保证离线可用。
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ROOT = os.path.join(ROOT, "src", "main", "java")
BASE_PKG = "com.qiqi.li"

# --------------------------------------------------------------------------- 目标架构分层
#
# ⚠️ **这是「目标」，不是「现状」**。层次视图按它排列，**违规边标红**。
# 改这张表 = 改你对架构的期望；别处若也要校验分层，应读同一张表。
#
# 约定：**数字越大越靠上层**；上层可以依赖下层，**下层不得依赖上层**。
LAYERS: list[tuple[int, str, str]] = [
    (-1, "外部", "原版 / 第三方 —— @Mixin 注入目标，mod 挂在它们身上"),
    (0, "L0 契约", "纯接口 / 数据，不依赖任何实现"),
    (1, "L1 基础", "容器抽象 · 工具 · 性能 · 日志"),
    (2, "L2 通用机制", "transfer · interaction · components …（不应认识具体领域）"),
    (3, "L3 领域", "12 个子系统 —— 彼此应几乎不依赖"),
    (4, "L4 接线 · 入口", "主类 · 网络包 · Mixin · 指令（只做注册与接线）"),
    (5, "L5 客户端", "渲染 · 输入 · 图标 · 屏幕（依赖一切，谁都不依赖它）"),
]

MODULE_LAYER: dict[str, int] = {"(external)": -1}
for _m in ("living/api", "living/model"):
    MODULE_LAYER[_m] = 0
for _m in ("living/container", "living/perf", "living/util", "logging"):
    MODULE_LAYER[_m] = 1
for _m in ("living/compat", "living/components", "living/debug", "living/function",
           "living/interaction", "living/transfer"):
    MODULE_LAYER[_m] = 2
for _m in ("com/qiqi", "living/command", "living/mixin", "network"):
    MODULE_LAYER[_m] = 4
for _m in ("client/gui", "client/icon", "client/input", "client/mixin",
           "client/mixinsupport", "client/render", "client/util"):
    MODULE_LAYER[_m] = 5

UNMAPPED: set[str] = set()


def layer_of(module: str) -> int:
    """模块 → 目标层号。未登记的模块落到 L3 并记入 UNMAPPED（main 里告警）。"""
    if module.startswith("living/domain/"):
        return 3
    lay = MODULE_LAYER.get(module)
    if lay is None:
        UNMAPPED.add(module)
        return 3
    return lay


# --------------------------------------------------------------------------- 解析


def strip_comments_strings(src: str) -> str:
    """把注释与字符串字面量替换成空白（保留换行与长度）。

    结构解析必须在「去噪」后的文本上做 —— 否则 javadoc 里的
    ``{@link ContainerFluidData}`` 会被当成真实依赖。
    """
    out: list[str] = []
    i, n = 0, len(src)
    state = "code"
    while i < n:
        c = src[i]
        if state == "code":
            if src.startswith("//", i):
                state, i = "line", i + 2
                out.append("  ")
            elif src.startswith("/*", i):
                state, i = "block", i + 2
                out.append("  ")
            elif src.startswith('"""', i):
                state, i = "textblock", i + 3
                out.append("   ")
            elif c == '"':
                state, i = "str", i + 1
                out.append(" ")
            elif c == "'":
                state, i = "char", i + 1
                out.append(" ")
            else:
                out.append(c)
                i += 1
        elif state == "line":
            out.append("\n" if c == "\n" else " ")
            if c == "\n":
                state = "code"
            i += 1
        elif state == "block":
            if src.startswith("*/", i):
                state, i = "code", i + 2
                out.append("  ")
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
        elif state == "textblock":
            if src.startswith('"""', i):
                state, i = "code", i + 3
                out.append("   ")
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
        else:  # str / char
            if c == "\\":
                out.append("  ")
                i += 2
            elif (state == "str" and c == '"') or (state == "char" and c == "'"):
                state, i = "code", i + 1
                out.append(" ")
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
    return "".join(out)


PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;", re.M)
DECL_RE = re.compile(r"\b(class|interface|enum|record)\s+(\w+)\s*(?:<[^{]*?>)?\s*([^{]*)\{", re.S)
# @Mixin 在项目里有三种写法，都要认：
#   @Mixin(SpriteIconButton.class)                    —— 简称
#   @Mixin(net.minecraft.client.gui.MapRenderer.class) —— 全限定名
#   @Mixin(targets = "net.minecraft...Screen$SlotWrapper") —— 字符串（含内部类 $）
# 只认第一种会漏掉 3 个注入点。
ANNOT_MIXIN_RE = re.compile(r"@Mixin\s*\(([^)]*)\)", re.S)


def short_name(fqn: str) -> str:
    """net.minecraft.client.gui.MapRenderer -> MapRenderer；
    A.B$Inner -> Inner。"""
    return fqn.rsplit(".", 1)[-1].rsplit("$", 1)[-1]


def mixin_targets(src: str) -> list[str]:
    out: set[str] = set()
    for m in ANNOT_MIXIN_RE.finditer(src):
        body = m.group(1)
        for cm in re.finditer(r"([\w.$]+)\.class", body):
            out.add(cm.group(1))
        for tm in re.finditer(r'targets\s*=\s*"([^"]+)"', body):
            out.add(tm.group(1))
    return sorted(out)

TAG_RE = re.compile(r"<[^>]+>")
LINK_RE = re.compile(r"\{@\w+\s+([^}]*)\}")


def javadoc_summary(src: str, upto: int) -> str:
    """取类声明之前**最后一个** javadoc 块的首句。"""
    last = None
    for m in re.finditer(r"/\*\*(.*?)\*/", src[:upto], re.S):
        last = m
    if not last:
        return ""
    body = last.group(1)
    lines = []
    for ln in body.split("\n"):
        ln = re.sub(r"^\s*\*\s?", "", ln).strip()
        if ln:
            lines.append(ln)
    text = " ".join(lines)
    text = LINK_RE.sub(r"\1", text)
    text = TAG_RE.sub("", text)
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return ""
    # 首句：中文句号或英文句号
    m = re.search(r"^(.{4,120}?)[。.]", text)
    return (m.group(1) if m else text[:110]).strip()


def simple_types(fragment: str, known: set[str]) -> set[str]:
    """从类型片段里挑出属于本项目的简单类名（去泛型、取末段）。"""
    found = set()
    fragment = re.sub(r"<[^<>]*>", " ", fragment)
    for tok in re.findall(r"[A-Za-z_$][\w$.]*", fragment):
        name = tok.rsplit(".", 1)[-1]
        if name in known:
            found.add(name)
    return found


def module_of(rel_pkg: str) -> str:
    """把包路径收敛成「模块」标签 —— 用于配色与图例分组。"""
    if not rel_pkg:
        return "(root)"
    parts = rel_pkg.split("/")
    if len(parts) >= 3 and parts[0] == "living" and parts[1] == "domain":
        return "living/domain/" + parts[2]
    if len(parts) >= 2:
        return parts[0] + "/" + parts[1]
    return parts[0]


def collect_java() -> list[str]:
    out = []
    for dirpath, _dirs, files in os.walk(SRC_ROOT):
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def parse_file(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    code = strip_comments_strings(src)

    rel = os.path.relpath(path, SRC_ROOT).replace("\\", "/")
    # rel_pkg 是**完整包路径**（com/qiqi/li/...）；pkg_rel 去掉 com.qiqi.li 前缀，
    # 供 module_of 分组用。
    rel_pkg = os.path.dirname(rel).replace("\\", "/")
    pkg = rel_pkg.replace("/", ".")
    prefix = BASE_PKG.replace(".", "/") + "/"
    pkg_rel = rel_pkg[len(prefix):] if rel_pkg.startswith(prefix) else rel_pkg

    decl = DECL_RE.search(code)
    if decl:
        kind, name, header = decl.group(1), decl.group(2), decl.group(3)
        decl_at = decl.start()
    else:  # package-info.java 之类
        kind, name, header, decl_at = "file", os.path.basename(rel)[:-5], "", 0

    imports = set()
    for m in IMPORT_RE.finditer(code):
        imports.add(m.group(1))

    # 继承 / 实现 / 许可 / record 组件
    inherit_frag = []
    for m in re.finditer(r"\b(?:extends|implements|permits)\b([^{;]*?)(?=\b(?:extends|implements|permits|record)\b|$)", header):
        inherit_frag.append(m.group(1))
    if kind == "record":
        pm = re.search(r"\(([^)]*)\)", header)
        if pm:
            inherit_frag.append(pm.group(1))

    mixins = mixin_targets(src)

    return {
        "path": rel,
        "pkg": pkg,
        "pkgRel": pkg_rel,
        "module": module_of(pkg_rel),
        "kind": kind,
        "name": name,
        "summary": javadoc_summary(src, decl_at) if decl else "",
        "imports": sorted(imports),
        "inheritFrag": " , ".join(inherit_frag),
        # 精确标识符集合 —— 不能用子串匹配，否则 LivingItem 会把
        # LivingItemFunction / LivingItemManager 全算成自己的引用（实测 154 条假上游）。
        "tokens": set(re.findall(r"[A-Za-z_$][\w$]*", code)),
        "mixins": sorted(mixins),
    }


def build_graph(files: list[dict]) -> dict:
    known = {f["name"] for f in files if f["kind"] != "file"}
    by_name = {f["name"]: f for f in files if f["kind"] != "file"}

    nodes = []
    for f in files:
        if f["kind"] == "file":
            continue
        nodes.append({
            "id": f["name"],
            "pkg": f["pkg"],
            "module": f["module"],
            "kind": f["kind"],
            "path": f["path"],
            "summary": f["summary"],
            "layer": layer_of(f["module"]),
        })
    index = {n["id"]: n for n in nodes}

    # ---- external 节点（@Mixin 目标：原版 / 第三方）
    # 统一用**简称**做 id，全限定名存进 path 供面板展示 ——
    # 否则图上会同时出现 `MapRenderer` 和 `net.minecraft.client.gui.MapRenderer`。
    externals: dict[str, dict] = {}
    for f in files:
        for target in f["mixins"]:
            short = short_name(target)
            if short in known:
                continue
            externals.setdefault(short, {
                "id": short,
                "pkg": target.rsplit(".", 1)[0] if "." in target else "",
                "module": "(external)",
                "kind": "external",
                "path": target,
                "summary": "Mixin 注入目标（原版 / 第三方）",
                "layer": -1,
            })
    for e in externals.values():
        index[e["id"]] = e
    nodes.extend(externals.values())

    # ---- 边
    edges: dict[tuple[str, str], set[str]] = defaultdict(set)
    for f in files:
        if f["kind"] == "file":
            continue
        src_name = f["name"]

        # inherit（最强）
        for t in simple_types(f["inheritFrag"], known):
            if t != src_name:
                edges[(src_name, t)].add("inherit")

        # use：import + 正文提及
        used = set()
        for imp in f["imports"]:
            if imp.endswith(".*"):
                continue
            name = imp.rsplit(".", 1)[-1]
            if name in known:
                used.add(name)
        for name in known:
            if name != src_name and name in f["tokens"]:
                used.add(name)
        for t in used:
            edges[(src_name, t)].add("use")

        # mixin
        for target in f["mixins"]:
            short = short_name(target)
            if short not in known and short in externals:
                edges[(src_name, short)].add("mixin")

    PRIORITY = {"inherit": 3, "mixin": 2, "use": 1}
    edge_list = []
    for (s, d), kinds in edges.items():
        if s not in index or d not in index:
            continue
        best = max(kinds, key=lambda k: PRIORITY[k])
        edge_list.append({"s": s, "d": d, "k": best})

    # 度数
    outdeg = defaultdict(int)
    indeg = defaultdict(int)
    for e in edge_list:
        outdeg[e["s"]] += 1
        indeg[e["d"]] += 1
    for n in nodes:
        n["out"] = outdeg[n["id"]]
        n["in"] = indeg[n["id"]]

    # 模块级聚合图（给「包视图」用）
    mod_out = defaultdict(int)
    mod_in = defaultdict(int)
    mod_nodes = defaultdict(int)
    for n in nodes:
        mod_nodes[n["module"]] += 1
    for e in edge_list:
        a = index[e["s"]]["module"]
        b = index[e["d"]]["module"]
        if a == b:
            continue
        mod_out[a] += 1
        mod_in[b] += 1
    modules = sorted(
        ({"id": m, "count": c, "out": mod_out[m], "in": mod_in[m]} for m, c in mod_nodes.items()),
        key=lambda x: (-x["count"], x["id"]),
    )
    mod_edges = defaultdict(int)
    for e in edge_list:
        a = index[e["s"]]["module"]
        b = index[e["d"]]["module"]
        if a != b:
            mod_edges[(a, b)] += 1
    module_edges = [{"s": a, "d": b, "w": w} for (a, b), w in mod_edges.items()]

    return {
        "base": BASE_PKG,
        "nodes": nodes,
        "edges": edge_list,
        "modules": modules,
        "moduleEdges": module_edges,
        "layers": [{"id": l, "name": n, "desc": d} for l, n, d in LAYERS],
    }


# --------------------------------------------------------------------------- 渲染

HTML = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>活物品 · 代码关系图</title>
<style>
  :root{--bg:#12100e;--panel:#1b1917;--line:#3a3733;--fg:#e8e4dc;--dim:#8f8a80;--accent:#e0a24a;--up:#e0524a;--down:#3f9ad6;}
  *{box-sizing:border-box}
  html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);
    font-family:"Segoe UI","Microsoft YaHei",system-ui,sans-serif;font-size:13px;overflow:hidden}
  #head{height:46px;display:flex;align-items:center;gap:14px;padding:0 14px;
    border-bottom:1px solid var(--line);background:var(--panel);position:relative;z-index:5}
  #head b{font-weight:500;font-size:14px}
  #head .sub{color:var(--dim);font-size:12px}
  #head input[type=search]{background:#12100e;border:1px solid var(--line);color:var(--fg);
    padding:5px 9px;border-radius:6px;width:190px;font-size:12px;outline:none}
  #head input[type=search]:focus{border-color:var(--accent)}
  #head label{display:flex;align-items:center;gap:5px;color:var(--dim);font-size:12px;cursor:pointer;user-select:none}
  #head label:hover{color:var(--fg)}
  #head input[type=range]{width:90px;accent-color:var(--accent)}
  .seg{display:flex;border:1px solid var(--line);border-radius:6px;overflow:hidden}
  .seg button{background:transparent;border:0;color:var(--dim);padding:5px 11px;cursor:pointer;font-size:12px}
  .seg button.on{background:var(--accent);color:#1a1613;font-weight:500}
  #wrap{position:absolute;inset:46px 0 0 0}
  canvas{display:block;width:100%;height:100%;cursor:grab}
  canvas.drag{cursor:grabbing}
  #legend{position:absolute;left:10px;top:10px;width:210px;max-height:calc(100% - 20px);overflow:auto;
    background:rgba(27,25,23,.92);border:1px solid var(--line);border-radius:8px;padding:9px 11px;font-size:12px}
  #legend h4{margin:0 0 7px;font-weight:500;font-size:12px;color:var(--dim)}
  .lg{display:flex;align-items:center;gap:7px;padding:3px 0;cursor:pointer;border-radius:4px}
  .lg:hover{background:#242220}
  .lg.off{opacity:.32}
  .dot{width:9px;height:9px;border-radius:50%;flex:none}
  .lg .n{margin-left:auto;color:var(--dim);font-size:11px}
  #panel{position:absolute;right:10px;top:10px;width:308px;max-height:calc(100% - 20px);overflow:auto;
    background:rgba(27,25,23,.95);border:1px solid var(--line);border-radius:8px;padding:12px 14px;display:none}
  #panel h3{margin:0 0 3px;font-weight:500;font-size:14px}
  #panel .meta{color:var(--dim);font-size:11.5px;margin-bottom:8px;word-break:break-all;line-height:1.5}
  #panel .sum{font-size:12px;line-height:1.6;color:#c9c3b8;margin-bottom:10px}
  #panel h5{margin:11px 0 5px;font-weight:500;font-size:12px;display:flex;align-items:center;gap:6px}
  #panel h5 .bar{flex:1;height:1px;background:var(--line)}
  #panel ul{margin:0;padding:0;list-style:none}
  #panel li{padding:2.5px 0;cursor:pointer;font-size:12px;color:#bdb7ac;display:flex;gap:7px;align-items:baseline}
  #panel li:hover{color:var(--fg)}
  #panel li .k{font-size:10px;color:var(--dim);border:1px solid var(--line);border-radius:3px;padding:0 4px;flex:none}
  #panel .close{position:absolute;right:10px;top:9px;cursor:pointer;color:var(--dim);font-size:15px;line-height:1}
  #panel .close:hover{color:var(--fg)}
  #tip{position:absolute;pointer-events:none;background:rgba(12,11,10,.96);border:1px solid var(--line);
    border-radius:6px;padding:7px 10px;font-size:12px;max-width:330px;display:none;z-index:9;line-height:1.55}
  #tip .t{font-weight:500}
  #tip .m{color:var(--dim);font-size:11px}
  #tip .s{color:#bdb7ac;margin-top:4px;font-size:11.5px}
  #hint{position:absolute;left:50%;bottom:12px;transform:translateX(-50%);color:var(--dim);font-size:11.5px;
    background:rgba(27,25,23,.9);border:1px solid var(--line);border-radius:6px;padding:5px 12px}
</style>
</head>
<body>
<div id="head">
  <b>活物品 · 代码关系图</b>
  <span class="sub" id="stat"></span>
  <div class="seg" id="mode">
    <button data-m="class" class="on">类视图</button>
    <button data-m="module">包视图</button>
    <button data-m="layer">层次视图</button>
  </div>
  <input type="search" id="q" placeholder="搜索类名…">
  <label>上游/下游深度 <input type="range" id="depth" min="1" max="4" value="1"><span id="depthv">1</span></label>
  <label><input type="checkbox" id="eInherit" checked>继承</label>
  <label><input type="checkbox" id="eUse" checked>引用</label>
  <label><input type="checkbox" id="eMixin" checked>Mixin</label>
  <label><input type="checkbox" id="eLabel" checked>标签</label>
</div>
<div id="wrap">
  <canvas id="c"></canvas>
  <div id="legend"></div>
  <div id="panel"></div>
  <div id="tip"></div>
  <div id="hint">滚轮缩放 · 拖拽平移 · 点节点看上下游 · 点空白取消</div>
</div>
<script id="data" type="application/json">__DATA__</script>
<script>
(function(){
"use strict";
var D = JSON.parse(document.getElementById('data').textContent);
var cv = document.getElementById('c'), ctx = cv.getContext('2d');
var W=0,H=0,DPR=Math.min(window.devicePixelRatio||1,2);

/* ---------- 配色：模块 -> 稳定色相 ---------- */
var mods = D.modules.map(function(m){return m.id;});
var modColor = {};
mods.forEach(function(m,i){
  if(m==='(external)'){modColor[m]='#6f6a62';return;}
  var h = (i*137.508)%360;
  modColor[m] = 'hsl('+h.toFixed(0)+',58%,62%)';
});
function colorOf(id){var n=idx[id];return modColor[n?n.module:'(root)']||'#888';}

/* ---------- 建图 ---------- */
var idx={}, nodes=[], edges=[], viewMode='class';
var layerName={}, layerDesc={};
(D.layers||[]).forEach(function(l){ layerName[l.id]=l.name; layerDesc[l.id]=l.desc; });

function loadMode(m){
  viewMode = m;
  if(m==='module'){
    nodes = D.modules.map(function(mm){
      return {id:mm.id,module:mm.id,pkg:mm.id,kind:'module',path:'',summary:'',
              count:mm.count,out:mm.out,in:mm.in};
    });
    edges = D.moduleEdges.map(function(e){return {s:e.s,d:e.d,k:'use',w:e.w};});
  } else {
    // 类视图与层次视图共用同一份数据，只是布局不同
    nodes = D.nodes.map(function(n){return Object.assign({},n);});
    edges = D.edges.map(function(e){return {s:e.s,d:e.d,k:e.k};});
  }
  idx={}; nodes.forEach(function(n){idx[n.id]=n;});
  adj={}; radj={};
  nodes.forEach(function(n){adj[n.id]=[];radj[n.id]=[];});
  edges.forEach(function(e){
    if(!adj[e.s]||!radj[e.d])return;
    adj[e.s].push(e); radj[e.d].push(e);
  });
  hidden={};
  var deg={}; nodes.forEach(function(n){deg[n.id]=0;});
  edges.forEach(function(e){deg[e.s]++;deg[e.d]++;});
  nodes.forEach(function(n){
    var d = (n.kind==='module'? (n.count||1)*3 : deg[n.id]||0);
    n.deg=deg[n.id]||0;
    n.r = n.kind==='module' ? 6+Math.sqrt(n.count)*2.6 : 3.2+Math.sqrt(d)*1.15;
    n.x = W/2 + (Math.random()-.5)*Math.min(W,900);
    n.y = H/2 + (Math.random()-.5)*Math.min(H,700);
  });
  ei={}; nodes.forEach(function(n,i){ei[n.id]=i;});
  // 标签预算：默认只标注度数最高的 ~12%，否则 300 个标签会糊成一片
  var ds=nodes.map(function(n){return n.deg;}).sort(function(a,b){return b-a;});
  labelMin = ds.length ? ds[Math.min(ds.length-1, Math.floor(ds.length*0.10))] : 0;
}
var adj={},radj={},hidden={};

/* ---------- 力导向布局（Fruchterman-Reingold） ----------
   自制的 1/d² 斥力 + 线性弹簧在 300 节点 / 1100 边上会塌成一团球
   （实测第一版就是这样）。改用经典 FR：
     斥力 k²/d（全对，无 cutoff 塌缩）、引力 d²/k（仅边）、
     温度上限控制每步位移并线性退火 ⇒ 布局自然铺开。 */
var ei={}, labelMin=0;
function layout(iters){
  var n=nodes.length; if(!n) return;
  var k=Math.sqrt((W*H)/n)*0.85;
  var t=Math.min(W,H)/7;
  var cool=t/iters;
  var disp=new Float64Array(n*2);
  for(var it=0;it<iters;it++){
    for(var z=0;z<n*2;z++) disp[z]=0;
    // 斥力
    for(var i=0;i<n;i++){
      var a=nodes[i];
      for(var j=i+1;j<n;j++){
        var b=nodes[j];
        var dx=a.x-b.x, dy=a.y-b.y;
        var d2=dx*dx+dy*dy;
        if(d2<0.01){ dx=(Math.random()-.5)*0.5; dy=(Math.random()-.5)*0.5; d2=dx*dx+dy*dy||0.01; }
        var d=Math.sqrt(d2);
        if(d>k*7) continue;
        var f=k*k/d, ux=dx/d, uy=dy/d;
        disp[i*2]+=ux*f; disp[i*2+1]+=uy*f;
        disp[j*2]-=ux*f; disp[j*2+1]-=uy*f;
      }
    }
    // 引力（只作用于有边的点对；权重按边类型 —— 结构边主导布局）
    for(var e=0;e<edges.length;e++){
      var ed=edges[e], ia=ei[ed.s], ib=ei[ed.d];
      if(ia===undefined||ib===undefined) continue;
      var p=nodes[ia], q=nodes[ib];
      var dx2=p.x-q.x, dy2=p.y-q.y;
      var dd=Math.sqrt(dx2*dx2+dy2*dy2)||0.01;
      var w = ed.k==='inherit'?1.7 : (ed.k==='mixin'?1.2 : 0.3);
      var f2=dd*dd/k*w, vx=dx2/dd, vy=dy2/dd;
      disp[ia*2]-=vx*f2; disp[ia*2+1]-=vy*f2;
      disp[ib*2]+=vx*f2; disp[ib*2+1]+=vy*f2;
    }
    // 弱重力：防止连通分量飞散
    for(var g=0;g<n;g++){
      disp[g*2]  += (W/2-nodes[g].x)*0.008;
      disp[g*2+1]+= (H/2-nodes[g].y)*0.008;
    }
    // 按温度限幅位移
    for(var m=0;m<n;m++){
      var px=disp[m*2], py=disp[m*2+1];
      var mag=Math.sqrt(px*px+py*py);
      if(mag<1e-4) continue;
      var lim=mag<t?mag:t;
      nodes[m].x+=px/mag*lim; nodes[m].y+=py/mag*lim;
    }
    t-=cool; if(t<0.3) t=0.3;
  }
}
function warmup(iters){
  if(viewMode==='layer') layeredLayout(); else layout(iters);
  fitView();
}

function fitView(){
  var n=nodes.length; if(!n) return;
  if(viewMode==='layer'){
    // 层次视图是结构化布局：用真实包围盒（不能按分位数裁，否则会切掉整层）
    var lx=nodes.map(function(p){return p.x;}), ly=nodes.map(function(p){return p.y;});
    var x0=Math.min.apply(null,lx)-BAND_PAD, x1=Math.max.apply(null,lx)+BAND_PAD+100;
    var y0=Math.min.apply(null,ly)-30, y1=Math.max.apply(null,ly)+30;
    var leftGutter=236, availW=Math.max(240,W-leftGutter-16), availH=Math.max(200,H-60);
    var kl=Math.min(availW/Math.max(1,x1-x0), availH/Math.max(1,y1-y0));
    view.k=Math.max(0.1,Math.min(2.6,kl));
    view.x=leftGutter+availW/2-(x0+x1)/2*view.k;
    view.y=H/2-(y0+y1)/2*view.k;
    return;
  }
  // 用 3%~97% 分位数当边界，而不是 min/max ——
  // 几个孤立离群点会把包围盒拉得很大，导致主簇只占屏幕中间一小块。
  var xs2=nodes.map(function(p){return p.x;}).sort(function(a,b){return a-b;});
  var ys2=nodes.map(function(p){return p.y;}).sort(function(a,b){return a-b;});
  var lo=Math.floor(n*0.03), hi=Math.min(n-1, Math.ceil(n*0.97)-1);
  var qx0=xs2[lo], qx1=xs2[hi], qy0=ys2[lo], qy1=ys2[hi];
  var pad=64;
  var k2=Math.min((W-pad*2)/Math.max(1,qx1-qx0), (H-pad*2)/Math.max(1,qy1-qy0));
  view.k=Math.max(0.12,Math.min(2.6,k2));
  view.x=W/2-(qx0+qx1)/2*view.k;
  view.y=H/2-(qy0+qy1)/2*view.k;
}

/* ---------- 层次视图布局（Sugiyama 简化版） ----------
   按「目标分层」把类排成自下而上的横带；层内用 barycenter 两趟扫描减少交叉。
   违规边（下层依赖上层）在 draw() 里标红 —— 「层次在哪断的」一眼可见。 */
var bands=[], bandW=0;
var PER_ROW=26, SLOT=42, ROW_H=48, LAYER_GAP=86, BAND_PAD=26;

function layeredLayout(){
  bands=[]; bandW=PER_ROW*SLOT;
  var byLayer={};
  nodes.forEach(function(n){
    var L=(n.layer===undefined||n.layer===null)?3:n.layer;
    n.layer=L;
    (byLayer[L]=byLayer[L]||[]).push(n);
  });
  var keys=Object.keys(byLayer).map(Number).sort(function(a,b){return a-b;});
  if(!keys.length) return;

  keys.forEach(function(L){
    byLayer[L].sort(function(a,b){return (a.module+'/'+a.id).localeCompare(b.module+'/'+b.id);});
  });

  var xpos={};
  function sweep(orderKeys){
    orderKeys.forEach(function(L){
      var arr=byLayer[L];
      arr.forEach(function(n){
        var s=0,c=0;
        var es=(adj[n.id]||[]).concat(radj[n.id]||[]);
        for(var i=0;i<es.length;i++){
          var o=(es[i].s===n.id)?es[i].d:es[i].s;
          if(xpos[o]!==undefined){ s+=xpos[o]; c++; }
        }
        n._bc = c ? s/c : (xpos[n.id]!==undefined ? xpos[n.id] : 1e6);
      });
      arr.sort(function(a,b){ return a._bc-b._bc; });
      arr.forEach(function(n,i){ xpos[n.id]=i; });
    });
  }
  byLayer[keys[0]].forEach(function(n,i){ xpos[n.id]=i; });
  sweep(keys.slice(1));                                    // 自下而上
  sweep(keys.slice(0,keys.length-1).reverse());             // 自上而下修正

  var h=0;
  keys.forEach(function(L){
    var arr=byLayer[L];
    var rows=Math.max(1,Math.ceil(arr.length/PER_ROW));
    var y0=h, y1=h+rows*ROW_H;
    bands.push({L:L, y0:y0, y1:y1, count:arr.length});
    h=y1+LAYER_GAP;
    var span=Math.min(PER_ROW, arr.length);
    arr.forEach(function(n,i){
      var row=Math.floor(i/PER_ROW), col=i%PER_ROW;
      var inRow=Math.min(PER_ROW, arr.length-row*PER_ROW);
      n.x=(col+0.5)/span*bandW + (span-inRow)*SLOT/2;
      n.y=-(y0+row*ROW_H+ROW_H/2);
    });
  });
}

/* ---------- 视图 ---------- */
var view={x:0,y:0,k:1};
function toScreen(p){return [p.x*view.k+view.x, p.y*view.k+view.y];}
function toWorld(sx,sy){return [(sx-view.x)/view.k, (sy-view.y)/view.k];}

var sel=null, selUp={}, selDown={}, searchHit={};

function computeSel(){
  selUp={};selDown={};
  if(!sel)return;
  var depth=parseInt(document.getElementById('depth').value,10);
  var front=[sel], seen={}; seen[sel]=1;
  for(var d=0;d<depth;d++){
    var nx=[];
    front.forEach(function(id){
      (radj[id]||[]).forEach(function(e){ if(!seen[e.s]){seen[e.s]=1;selUp[e.s]=1;nx.push(e.s);} });
    });
    front=nx;
  }
  front=[sel]; seen={}; seen[sel]=1;
  for(var d2=0;d2<depth;d2++){
    var nx2=[];
    front.forEach(function(id){
      (adj[id]||[]).forEach(function(e){ if(!seen[e.d]){seen[e.d]=1;selDown[e.d]=1;nx2.push(e.d);} });
    });
    front=nx2;
  }
}

function visibleEdges(){
  var on={inherit:document.getElementById('eInherit').checked,
          use:document.getElementById('eUse').checked,
          mixin:document.getElementById('eMixin').checked};
  return edges.filter(function(e){
    return on[e.k] && !hidden[idx[e.s]?idx[e.s].module:''] && !hidden[idx[e.d]?idx[e.d].module:''];
  });
}
function nodeVisible(n){ return !hidden[n.module]; }

var showLabel=true;
function draw(){
  ctx.setTransform(DPR,0,0,DPR,0,0);
  ctx.clearRect(0,0,W,H);
  var es=visibleEdges();
  var dimAll = !!sel || Object.keys(searchHit).length>0;

  // 层次视图：先铺层带（含层名），再画边
  if(viewMode==='layer'){
    for(var bi=0;bi<bands.length;bi++){
      var bd=bands[bi];
      var q0=toScreen({x:-BAND_PAD, y:-bd.y1});
      var q1=toScreen({x:bandW+BAND_PAD, y:-bd.y0});
      ctx.fillStyle='rgba(255,255,255,0.030)';
      ctx.fillRect(q0[0],q0[1],q1[0]-q0[0],q1[1]-q0[1]);
      ctx.strokeStyle='rgba(140,134,124,0.20)';
      ctx.lineWidth=0.5;
      ctx.strokeRect(q0[0],q0[1],q1[0]-q0[0],q1[1]-q0[1]);
      var midY=-(bd.y0+bd.y1)/2;
      var lb=toScreen({x:bandW+BAND_PAD+12, y:midY});
      ctx.textAlign='left'; ctx.textBaseline='middle';
      ctx.font='500 '+Math.min(15,Math.max(10,11.5*view.k))+'px "Segoe UI","Microsoft YaHei",sans-serif';
      ctx.fillStyle='#c9c3b8';
      ctx.fillText(layerName[bd.L]||('L'+bd.L), lb[0], lb[1]);
      var lb2=toScreen({x:bandW+BAND_PAD+12, y:midY-17});
      ctx.font=Math.min(12,Math.max(9.5,10*view.k))+'px "Segoe UI","Microsoft YaHei",sans-serif';
      ctx.fillStyle='#6f6a62';
      ctx.fillText(bd.count+' 个类', lb2[0], lb2[1]);
    }
    // 相邻层之间直接标出违规条数 —— 把「红边」和「数字」对上
    var vc={};
    for(var vi=0;vi<edges.length;vi++){
      var ee=edges[vi], na=idx[ee.s], nb=idx[ee.d];
      if(!na||!nb||na.layer===undefined||nb.layer===undefined)continue;
      if(!nodeVisible(na)||!nodeVisible(nb))continue;
      if(na.layer<nb.layer){ var kk=na.layer+'|'+nb.layer; vc[kk]=(vc[kk]||0)+1; }
    }
    ctx.textAlign='center'; ctx.textBaseline='middle';
    for(var bj=0;bj<bands.length-1;bj++){
      var lo=bands[bj].L, hi=bands[bj+1].L;
      if(hi!==lo+1) continue;
      var cnt=vc[lo+'|'+hi];
      if(!cnt) continue;
      var gp=toScreen({x:bandW/2, y:-(bands[bj].y1+bands[bj+1].y0)/2});
      ctx.font='500 '+Math.min(14,Math.max(10,11*view.k))+'px "Segoe UI","Microsoft YaHei",sans-serif';
      var txt='违规 '+cnt+' 条';
      var tw=ctx.measureText(txt).width;
      ctx.fillStyle='rgba(18,16,14,0.88)';
      ctx.fillRect(gp[0]-tw/2-8, gp[1]-9, tw+16, 18);
      ctx.strokeStyle='rgba(232,86,74,0.55)'; ctx.lineWidth=0.5;
      ctx.strokeRect(gp[0]-tw/2-8, gp[1]-9, tw+16, 18);
      ctx.fillStyle='#e8564a';
      ctx.fillText(txt, gp[0], gp[1]);
    }
    ctx.lineWidth=1;
  }

  // 边
  for(var i=0;i<es.length;i++){
    var e=es[i], a=idx[e.s], b=idx[e.d];
    if(!a||!b||!nodeVisible(a)||!nodeVisible(b))continue;
    // 高亮判定：**两端都在高亮集合里**才算这条线「在邻域内」。
    // ⚠️ 不能写成 `selUp[e.s] || selDown[e.d]` 之类 —— 那会把「上游的上游」
    // 也点亮 ⇒ 深度 1 时线跑到 2 层（2026-10-06 用户实测发现）。
    var rel = sel ? ((e.s===sel||selUp[e.s]||selDown[e.s]) &&
                     (e.d===sel||selUp[e.d]||selDown[e.d])) : false;
    // 层次视图：**下层依赖上层 = 违规** ⇒ 标红（这是「层次在哪断的」）
    var viol = (viewMode==='layer') && (a.layer!==undefined) && (b.layer!==undefined) && (a.layer<b.layer);
    ctx.lineWidth = e.w ? Math.min(4.5, 0.6+e.w*0.16) : (viol?1.3:1);   // 包视图：粗细 = 耦合强度
    if(dimAll && !rel){ ctx.strokeStyle='rgba(120,113,102,0.055)'; }
    else if(viol){ ctx.strokeStyle=rel?'rgba(232,86,74,0.95)':'rgba(232,86,74,0.42)'; }
    else if(e.k==='inherit'){ ctx.strokeStyle=rel?'rgba(224,162,74,0.85)':'rgba(224,162,74,0.26)'; }
    else if(e.k==='mixin'){ ctx.strokeStyle=rel?'rgba(190,120,220,0.9)':'rgba(190,120,220,0.24)'; }
    else { ctx.strokeStyle=rel?'rgba(150,190,220,0.75)':(e.w?'rgba(150,175,195,0.34)':'rgba(140,160,175,0.12)'); }
    var p1=toScreen(a), p2=toScreen(b);
    ctx.beginPath(); ctx.moveTo(p1[0],p1[1]); ctx.lineTo(p2[0],p2[1]); ctx.stroke();
  }
  ctx.lineWidth=1;

  // 节点
  for(var j=0;j<nodes.length;j++){
    var n=nodes[j];
    if(!nodeVisible(n))continue;
    var p=toScreen(n), r=Math.max(2, n.r*view.k);
    var st='idle';
    if(sel){ if(n.id===sel)st='sel'; else if(selUp[n.id])st='up'; else if(selDown[n.id])st='down'; else st='fade'; }
    else if(Object.keys(searchHit).length){ st = searchHit[n.id]?'sel':'fade'; }
    var a1 = st==='fade'?0.14:1;
    ctx.globalAlpha=a1;
    ctx.beginPath(); ctx.arc(p[0],p[1],r,0,6.2832);
    ctx.fillStyle = n.kind==='external' ? 'rgba(0,0,0,0)' : colorOf(n.id);
    if(n.kind==='external'){ ctx.lineWidth=1.4; ctx.strokeStyle='#6f6a62'; ctx.stroke(); }
    else ctx.fill();
    if(st==='sel'){ ctx.lineWidth=2.2; ctx.strokeStyle='#fff'; ctx.stroke(); }
    else if(st==='up'){ ctx.lineWidth=2.2; ctx.strokeStyle='#e0524a'; ctx.stroke(); }
    else if(st==='down'){ ctx.lineWidth=2.2; ctx.strokeStyle='#3f9ad6'; ctx.stroke(); }
    ctx.globalAlpha=1;
  }

  // 标签：候选按度数排序 + 矩形碰撞避让 —— 否则核心区标签会糊成一团
  if(showLabel){
    ctx.textBaseline='middle'; ctx.textAlign='left';
    var cand=[];
    for(var m=0;m<nodes.length;m++){
      var nd=nodes[m];
      if(!nodeVisible(nd))continue;
      var must = nd.id===sel||selUp[nd.id]||selDown[nd.id]||searchHit[nd.id]||nd.kind==='module';
      if(!must && (sel || !(view.k>1.7 || nd.deg>=labelMin))) continue;
      cand.push(nd);
    }
    cand.sort(function(a,b){return (b.deg||0)-(a.deg||0);});
    var placed=[];
    for(var c=0;c<cand.length;c++){
      var nd2=cand[c], q=toScreen(nd2);
      var fs=Math.min(13,Math.max(10,10.5*view.k));
      ctx.font=(nd2.id===sel?'500 ':'')+fs+'px "Segoe UI","Microsoft YaHei",sans-serif';
      var label = nd2.kind==='module' ? (nd2.id+' ('+nd2.count+')') : nd2.id;
      var tw=ctx.measureText(label).width;
      var tx=q[0]+Math.max(4,nd2.r*view.k+3), ty=q[1];
      var box={x:tx-2,y:ty-7,w:tw+4,h:14};
      var clash=false;
      for(var k2=0;k2<placed.length;k2++){
        var pp=placed[k2];
        if(box.x<pp.x+pp.w && box.x+box.w>pp.x && box.y<pp.y+pp.h && box.y+box.h>pp.y){clash=true;break;}
      }
      if(clash && nd2.id!==sel) continue;
      placed.push(box);
      ctx.globalAlpha=0.92;
      ctx.fillStyle='rgba(18,16,14,0.72)';
      ctx.fillRect(box.x,box.y,box.w,box.h);
      ctx.fillStyle = nd2.id===sel?'#fff':(selUp[nd2.id]?'#e0524a':(selDown[nd2.id]?'#8fc7ea':'#d8d2c7'));
      ctx.fillText(label,tx,ty);
      ctx.globalAlpha=1;
    }
  }
}

/* ---------- 交互 ---------- */
function resize(){
  var w=cv.parentNode.clientWidth, h=cv.parentNode.clientHeight;
  DPR=Math.min(window.devicePixelRatio||1,2);
  cv.width=w*DPR; cv.height=h*DPR;
  cv.style.width=w+'px'; cv.style.height=h+'px';
  W=w;H=h;
  draw();
}
var drag=null;
cv.addEventListener('mousedown',function(e){
  var r=cv.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  var hit=pick(mx,my);
  if(hit && !e.shiftKey){ select(hit.id); return; }
  drag={x:e.clientX,y:e.clientY,vx:view.x,vy:view.y}; cv.classList.add('drag');
});
window.addEventListener('mousemove',function(e){
  var r=cv.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  if(drag){ view.x=drag.vx+(e.clientX-drag.x); view.y=drag.vy+(e.clientY-drag.y); draw(); return; }
  var hit=pick(mx,my);
  var tip=document.getElementById('tip');
  if(hit){
    tip.style.display='block';
    tip.style.left=Math.min(mx+14,W-350)+'px'; tip.style.top=(my+14)+'px';
    var h='<div class="t">'+esc(hit.id)+'</div><div class="m">'+esc(hit.pkg)+' · '+hit.kind+
          ' · 上游 '+hit.in+' / 下游 '+hit.out+'</div>';
    if(hit.summary) h+='<div class="s">'+esc(hit.summary)+'</div>';
    if(hit.path) h+='<div class="m" style="margin-top:4px">'+esc(hit.path)+'</div>';
    tip.innerHTML=h;
  } else tip.style.display='none';
});
window.addEventListener('mouseup',function(){drag=null;cv.classList.remove('drag');});
cv.addEventListener('wheel',function(e){
  e.preventDefault();
  var r=cv.getBoundingClientRect(), mx=e.clientX-r.left, my=e.clientY-r.top;
  var w0=toWorld(mx,my);
  var k=view.k*(e.deltaY<0?1.12:1/1.12);
  view.k=Math.max(0.12,Math.min(6,k));
  var w1=toWorld(mx,my);
  view.x+=(w1[0]-w0[0])*view.k; view.y+=(w1[1]-w0[1])*view.k;
  draw();
},{passive:false});
cv.addEventListener('dblclick',function(e){
  var r=cv.getBoundingClientRect(); var hit=pick(e.clientX-r.left,e.clientY-r.top);
  if(hit){ view.k=1.6; view.x=W/2-hit.x*view.k; view.y=H/2-hit.y*view.k; draw(); }
});

function pick(mx,my){
  var best=null,bd=1e9;
  for(var i=0;i<nodes.length;i++){
    var n=nodes[i]; if(!nodeVisible(n))continue;
    var p=toScreen(n); var r=Math.max(5,n.r*view.k+4);
    var d=(p[0]-mx)*(p[0]-mx)+(p[1]-my)*(p[1]-my);
    if(d<r*r && d<bd){bd=d;best=n;}
  }
  return best;
}
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){
  return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}

function select(id){
  sel=id; computeSel(); renderPanel(); draw();
  try{ history.replaceState(null,'','#'+encodeURIComponent(id)); }catch(e){}
}
function clearSel(){
  sel=null; selUp={};selDown={};
  document.getElementById('panel').style.display='none'; draw();
  try{ history.replaceState(null,'',location.pathname+location.search); }catch(e){}
}

function renderPanel(){
  var p=document.getElementById('panel');
  if(!sel){ p.style.display='none'; return; }
  var n=idx[sel];
  var up=[],down=[];
  (radj[sel]||[]).forEach(function(e){ if(idx[e.s]) up.push({n:idx[e.s],k:e.k}); });
  (adj[sel]||[]).forEach(function(e){ if(idx[e.d]) down.push({n:idx[e.d],k:e.k}); });
  up.sort(function(a,b){return b.n.in-a.n.in;}); down.sort(function(a,b){return b.n.in-a.n.in;});
  function list(arr,cls){
    if(!arr.length) return '<div class="meta">（无）</div>';
    return '<ul>'+arr.slice(0,60).map(function(x){
      return '<li data-go="'+esc(x.n.id)+'"><span class="k">'+esc(x.k)+'</span><span>'+esc(x.n.id)+'</span></li>';
    }).join('')+'</ul>'+(arr.length>60?'<div class="meta">…共 '+arr.length+' 个</div>':'');
  }
  var html='<div class="close" id="pclose">×</div>'+
    '<h3>'+esc(n.id)+'</h3>'+
    '<div class="meta">'+esc(n.pkg||'')+'<br>'+
    (n.layer!==undefined ? esc(layerName[n.layer]||('L'+n.layer))+' · ' : '')+
    esc(n.kind)+' · '+esc(n.path||'')+'<br>'+
    '上游 <b style="color:#e0524a">'+n.in+'</b> · 下游 <b style="color:#3f9ad6">'+n.out+'</b></div>'+
    (n.summary?'<div class="sum">'+esc(n.summary)+'</div>':'')+
    '<h5><span style="color:#e0524a">▲ 上游</span><span class="bar"></span><span style="color:#8f8a80;font-weight:400">谁依赖我</span></h5>'+list(up)+
    '<h5><span style="color:#3f9ad6">▼ 下游</span><span class="bar"></span><span style="color:#8f8a80;font-weight:400">我依赖谁</span></h5>'+list(down);
  p.innerHTML=html; p.style.display='block';
  p.querySelector('#pclose').onclick=function(ev){ev.stopPropagation();clearSel();};
  p.querySelectorAll('li[data-go]').forEach(function(li){
    li.onclick=function(){ var t=idx[li.getAttribute('data-go')]; if(t){ view.x=W/2-t.x*view.k; view.y=H/2-t.y*view.k; select(t.id);} };
  });
}

/* 图例 */
function renderLegend(){
  var box=document.getElementById('legend');
  var counts={};
  nodes.forEach(function(n){counts[n.module]=(counts[n.module]||0)+1;});
  var list = D.modules.map(function(m){return m.id;});
  box.innerHTML='<h4>模块（点击过滤）</h4>'+list.map(function(m){
    return '<div class="lg" data-m="'+esc(m)+'"><span class="dot" style="background:'+
      (modColor[m]||'#888')+'"></span><span>'+esc(m)+'</span><span class="n">'+(counts[m]||0)+'</span></div>';
  }).join('');
  box.querySelectorAll('.lg').forEach(function(el){
    el.onclick=function(){
      var m=el.getAttribute('data-m');
      hidden[m]=!hidden[m]; el.classList.toggle('off',!!hidden[m]);
      if(sel && hidden[idx[sel].module]) clearSel(); else draw();
    };
  });
}

/* 顶栏 */
function bindHead(){
  document.getElementById('mode').addEventListener('click',function(e){
    var b=e.target.closest('button'); if(!b)return;
    this.querySelectorAll('button').forEach(function(x){x.classList.toggle('on',x===b);});
    sel=null; selUp={};selDown={}; searchHit={};
    document.getElementById('panel').style.display='none';
    loadMode(b.getAttribute('data-m'));
    renderLegend(); computeStats();
    warmup(400); draw();
  });
  ['eInherit','eUse','eMixin'].forEach(function(id){
    document.getElementById(id).addEventListener('change',draw);
  });
  document.getElementById('eLabel').addEventListener('change',function(){showLabel=this.checked;draw();});
  document.getElementById('depth').addEventListener('input',function(){
    document.getElementById('depthv').textContent=this.value;
    if(sel){computeSel();renderPanel();draw();}
  });
  var t=null;
  document.getElementById('q').addEventListener('input',function(){
    var v=this.value.trim().toLowerCase();
    clearTimeout(t);
    t=setTimeout(function(){
      searchHit={};
      if(v){ nodes.forEach(function(n){ if(n.id.toLowerCase().indexOf(v)>=0) searchHit[n.id]=1; }); }
      draw();
    },110);
  });
}
function computeStats(){
  document.getElementById('stat').textContent =
    nodes.length+' 个节点 · '+edges.length+' 条关系';
}

window.addEventListener('resize',resize);
document.addEventListener('keydown',function(e){ if(e.key==='Escape') clearSel(); });

resize();
var qs=new URLSearchParams(location.search);
var wantView=qs.get('view');
var startMode=(wantView==='module'||wantView==='layer') ? wantView : 'class';
if(startMode!=='class'){
  document.querySelectorAll('#mode button').forEach(function(b){
    b.classList.toggle('on', b.getAttribute('data-m')===startMode);
  });
}
loadMode(startMode); bindHead(); renderLegend(); computeStats();
// 支持 ?depth=2 预设上下游深度（可分享的链接）
var dv=parseInt(qs.get('depth'),10);
if(dv>=1 && dv<=4){
  document.getElementById('depth').value=dv;
  document.getElementById('depthv').textContent=dv;
}
warmup(400);
// 支持 code-map.html#ContainerFluidData 直接定位（可分享 / 可收藏）
var hash=decodeURIComponent((location.hash||'').replace(/^#/,''));
if(hash && idx[hash]) select(hash); else draw();
})();
</script>
</body>
</html>
"""


def find_cycles(ids: list[str], edges: list[dict]) -> list[list[str]]:
    """模块级强连通分量（Tarjan）—— 大小 > 1 就是循环依赖。

    类之间互相引用是 Java 的常态、不必管；但**模块之间成环**说明
    「改 A 要动 B、改 B 要动 A」，是框架演进最该盯的信号。
    """
    g: dict[str, list[str]] = defaultdict(list)
    for e in edges:
        g[e["s"]].append(e["d"])

    sys.setrecursionlimit(max(10000, len(ids) * 4 + 100))
    index: dict[str, int] = {}
    low: dict[str, int] = {}
    on: dict[str, bool] = {}
    stack: list[str] = []
    comps: list[list[str]] = []
    counter = [0]

    def dfs(v: str) -> None:
        index[v] = low[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on[v] = True
        for w in g[v]:
            if w not in index:
                dfs(w)
                low[v] = min(low[v], low[w])
            elif on.get(w):
                low[v] = min(low[v], index[w])
        if low[v] == index[v]:
            comp = []
            while True:
                w = stack.pop()
                on[w] = False
                comp.append(w)
                if w == v:
                    break
            comps.append(comp)

    for v in ids:
        if v not in index:
            dfs(v)
    return [c for c in comps if len(c) > 1]


def _closure(start: str, edges: list[dict], reverse: bool) -> set[str]:
    """传递闭包：reverse=True 取「谁（间接）依赖我」，False 取「我（间接）依赖谁」。"""
    adj: dict[str, list[str]] = defaultdict(list)
    for e in edges:
        if reverse:
            adj[e["d"]].append(e["s"])
        else:
            adj[e["s"]].append(e["d"])
    seen: set[str] = set()
    front = [start]
    while front:
        nxt = []
        for x in front:
            for y in adj[x]:
                if y != start and y not in seen:
                    seen.add(y)
                    nxt.append(y)
        front = nxt
    return seen


def query_report(graph: dict, name: str) -> int:
    """文本报告 —— 给 **AI** 用的入口（AI 不该去解析 HTML）。

    始终**直接解析源码**（不读 build/ 里的产物）⇒ 不存在「读到过期图」的问题。
    """
    nodes = {n["id"]: n for n in graph["nodes"]}
    mods = {m["id"]: m for m in graph["modules"]}
    edges = graph["edges"]
    lname = {l: nm for l, nm, _ in LAYERS}
    ldesc = {l: d for l, _, d in LAYERS}

    if name in nodes:
        n = nodes[name]
        ups = [e for e in edges if e["d"] == name]
        downs = [e for e in edges if e["s"] == name]
        up_all = _closure(name, edges, True)
        down_all = _closure(name, edges, False)
        print(name)
        print("  层      %s（%s）—— %s" % (lname.get(n["layer"], n["layer"]), n["module"],
                                          ldesc.get(n["layer"], "")))
        print("  文件    %s" % n["path"])
        if n["summary"]:
            print("  摘要    %s" % n["summary"])
        print("  上游 %d 个（传递闭包 %d）· 下游 %d 个（传递闭包 %d）"
              % (len(ups), len(up_all), len(downs), len(down_all)))
        if up_all:
            worst = sorted(up_all, key=lambda x: -nodes[x]["in"])[:5]
            print("  ⇒ 改它会（间接）波及 %d 个类；最重的几个：%s"
                  % (len(up_all), "、".join("%s(%d)" % (w, nodes[w]["in"]) for w in worst)))

        def dump(title, es, key):
            print("\n  %s" % title)
            if not es:
                print("    （无）")
                return
            rows = sorted(es, key=lambda e: (e["k"], -nodes[e[key]]["in"]))
            for e in rows:
                o = nodes[e[key]]
                print("    %-32s %-7s %s" % (o["id"], e["k"], lname.get(o["layer"], o["layer"])))

        dump("上游（谁依赖我 —— 改我，它们全受影响）", ups, "s")
        dump("下游（我依赖谁 —— 改它们，我可能坏）", downs, "d")
        return 0

    if name in mods:
        m = mods[name]
        out = [(e, e["w"]) for e in graph["moduleEdges"] if e["s"] == name]
        inn = [(e, e["w"]) for e in graph["moduleEdges"] if e["d"] == name]
        lay = layer_of(name)
        print("%s（模块）" % name)
        print("  层      %s —— %s" % (lname.get(lay, lay), ldesc.get(lay, "")))
        print("  类 %d 个 · 跨模块出边 %d / 入边 %d" % (m["count"], len(out), len(inn)))
        viol_out = [(e, w) for e, w in out if lay < layer_of(e["d"])]
        viol_in = [(e, w) for e, w in inn if layer_of(e["s"]) < lay]
        if viol_out:
            print("\n  🔴 向下违规（它伸手到了上层 —— 该断的正是这些）")
            for e, w in sorted(viol_out, key=lambda x: -x[1]):
                print("    → %-26s %2d 条（%s）" % (e["d"], w, lname.get(layer_of(e["d"]), "?")))
        if viol_in:
            print("\n  🔴 被下层伸手（下层依赖它 —— 该断的是对方）")
            for e, w in sorted(viol_in, key=lambda x: -x[1]):
                print("    ← %-26s %2d 条（%s）" % (e["s"], w, lname.get(layer_of(e["s"]), "?")))
        print("\n  出边（它依赖谁）")
        for e, w in sorted(out, key=lambda x: -x[1]):
            print("    → %-26s %2d 条" % (e["d"], w))
        print("\n  入边（谁依赖它）")
        for e, w in sorted(inn, key=lambda x: -x[1]):
            print("    ← %-26s %2d 条" % (e["s"], w))
        return 0

    near = sorted([k for k in list(nodes) + list(mods)
                   if name.lower() in k.lower()], key=len)[:8]
    print("找不到 %r。" % name)
    if near:
        print("  相近的：%s" % "、".join(near))
    else:
        print("  提示：类名要写简单名（如 ContainerFluidData）；模块名如 living/transfer。")
    return 1


def extend_report(graph: dict, threshold: float = 0.6) -> int:
    """**扩展点**：被多数领域共同依赖的类 = 新增一个子系统要接的口子。

    这是 AI 在本项目里最该问的问题（「我要新写一个活物品功能，动哪些地方」）——
    答案不是去读 12 个领域，而是看**它们共同依赖谁**。
    """
    nodes = {n["id"]: n for n in graph["nodes"]}
    edges = graph["edges"]
    lname = {l: nm for l, nm, _ in LAYERS}
    domains = sorted({n["module"] for n in graph["nodes"]
                      if n["module"].startswith("living/domain/")})
    if not domains:
        print("找不到 living/domain/* 模块。")
        return 1

    used: dict[str, set[str]] = defaultdict(set)
    for e in edges:
        a = nodes[e["s"]]
        if a["module"].startswith("living/domain/") and nodes[e["d"]]["module"] != a["module"]:
            used[e["d"]].add(a["module"])

    total = len(domains)
    rows = [(len(m), cid) for cid, m in used.items() if len(m) / total >= threshold]
    rows.sort(key=lambda x: (-x[0], x[1]))

    print("扩展点 —— %d 个领域共同依赖的类（新增一个子系统要接的口子）" % total)
    print("  判据：被 ≥ %d%% 的领域依赖（%d / %d）\n" % (int(threshold * 100),
                                                      int(threshold * total), total))
    if not rows:
        print("  （无 —— 领域之间没有共同依赖，说明契约可能散落了）")
        return 0
    for cnt, cid in rows:
        n = nodes[cid]
        who = "、".join(sorted(used[cid])[:3])
        more = "" if len(used[cid]) <= 3 else " 等"
        print("  %2d/%d  %-32s %-14s %s" % (cnt, total, cid, lname.get(n["layer"], ""),
                                            n["summary"][:40] if n["summary"] else ""))
        if cnt < total:
            miss = sorted(set(domains) - used[cid])
            print("        ↳ 未用：%s%s" % ("、".join(miss[:4]),
                                          " 等" if len(miss) > 4 else ""))
    print("\n  提示：**实现 / 注册** 这些口子，就接入了容器 tick、槽位、数据同步与落盘。")
    return 0


def main() -> int:
    global SRC_ROOT

    ap = argparse.ArgumentParser(description="生成活物品代码关系图")
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "code-map.html"))
    ap.add_argument("--json", action="store_true", help="同时输出 build/code-map.json")
    ap.add_argument("--query", metavar="NAME",
                    help="查某个类 / 模块的上下游（**直接解析源码，始终最新**，不产出 HTML）")
    ap.add_argument("--extend", action="store_true",
                    help="列出扩展点：被多数领域共同依赖的类（新增子系统要接的口子）")
    ap.add_argument("--src", default=SRC_ROOT)
    args = ap.parse_args()

    SRC_ROOT = args.src

    files = [parse_file(p) for p in collect_java()]
    graph = build_graph(files)

    if args.query:
        return query_report(graph, args.query)
    if args.extend:
        return extend_report(graph)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    payload = json.dumps(graph, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(HTML.replace("__DATA__", payload))

    if args.json:
        jp = os.path.join(os.path.dirname(args.out), "code-map.json")
        with open(jp, "w", encoding="utf-8") as fh:
            json.dump(graph, fh, ensure_ascii=False, indent=1)

    kinds = defaultdict(int)
    for e in graph["edges"]:
        kinds[e["k"]] += 1
    print("源文件      : %d" % len(files))
    print("节点        : %d（其中 external %d）" % (
        len(graph["nodes"]), sum(1 for n in graph["nodes"] if n["kind"] == "external")))
    print("关系        : %d  (%s)" % (
        len(graph["edges"]), "，".join("%s %d" % (k, v) for k, v in sorted(kinds.items()))))
    print("模块        : %d" % len(graph["modules"]))
    top = sorted(graph["nodes"], key=lambda n: -(n["in"] or 0))[:10]
    print("\n被依赖最多的类（改动的爆炸半径最大）：")
    for n in top:
        print("  %-32s 上游 %-4d 下游 %d" % (n["id"], n["in"], n["out"]))

    cycles = find_cycles([m["id"] for m in graph["modules"]], graph["moduleEdges"])
    mod_total = len(graph["modules"])
    print("\n模块级循环依赖（框架演进的信号）：")
    if not cycles:
        print("  （无 —— 模块之间是单向依赖，分层成立）")
    else:
        biggest = max(cycles, key=len)
        print("  最大强连通分量：%d / %d 个模块" % (len(biggest), mod_total))
        if len(biggest) >= mod_total * 0.5:
            print("  ⇒ 超过一半模块互相可达：**模块之间不存在单向分层**，"
                  "「改 A 会不会牵动 B」只能看图，不能靠包名推断。")
        for c in sorted(cycles, key=lambda x: -len(x))[:3]:
            if len(c) <= 12:
                print("  ⇄ %s" % " ⇄ ".join(sorted(c)))

    # 双向耦合最强的模块对 —— 比 SCC 更可落地：这两块拆不开
    pair: dict[tuple[str, str], list[int]] = defaultdict(lambda: [0, 0])
    for e in graph["moduleEdges"]:
        a, b = e["s"], e["d"]
        pair[(a, b) if a < b else (b, a)][0 if a < b else 1] = e["w"]
    bidir = [(a, b, v[0], v[1]) for (a, b), v in pair.items() if v[0] and v[1]]
    bidir.sort(key=lambda x: -min(x[2], x[3]))
    if bidir:
        print("\n双向耦合最强的模块对（互相依赖，改一边必然动另一边）：")
        for a, b, w1, w2 in bidir[:8]:
            print("  %-24s ⇄ %-24s  %d ↔ %d" % (a, b, w1, w2))

    # 分层违规：**下层依赖上层**（层次视图里标红的就是这些）
    # 这是「架构演进」的进度指标 —— 目标是把跨模块违规压到 0。
    lay = {n["id"]: n["layer"] for n in graph["nodes"]}
    mod_lay = {m["id"]: layer_of(m["id"]) for m in graph["modules"]}
    mw = {(e["s"], e["d"]): e["w"] for e in graph["moduleEdges"]}
    viol = collections.defaultdict(int)
    for (a, b), w in mw.items():
        if a in mod_lay and b in mod_lay and mod_lay[a] < mod_lay[b]:
            viol[(mod_lay[a], mod_lay[b])] += w
    name_of = {l: n for l, n, _ in LAYERS}
    print("\n分层违规（下层依赖上层 —— 与层次视图的红边同一批）：")
    if not viol:
        print("  （无 —— 模块图完全单向，分层成立）")
    else:
        tot_v = sum(viol.values())
        print("  合计 %d 条（占跨模块依赖 %.0f%%）" % (tot_v, 100.0 * tot_v / max(1, sum(mw.values()))))
        for (a, b), w in sorted(viol.items(), key=lambda x: -x[1]):
            print("    %-14s → %-14s %3d 条" % (name_of.get(a, a), name_of.get(b, b), w))
    if UNMAPPED:
        print("\n⚠ 未登记分层的模块（已按 L3 处理，请补进 LAYERS/MODULE_LAYER）：%s"
              % "、".join(sorted(UNMAPPED)))

    print("\n输出        : %s" % os.path.relpath(args.out, ROOT).replace("\\", "/"))
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError, OSError):
        pass
    sys.exit(main())

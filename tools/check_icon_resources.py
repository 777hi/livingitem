#!/usr/bin/env python3
"""静态图标资源门禁：从本仓库声明检查本地模型/纹理，不验证实际烘焙或外部材质包。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = "src/main/java/com/qiqi/li/client/icon/LivingIconRegistry.java"
OWN_NAMESPACE = "living_item"
# 当前注册帮助方法的模型参数位置；方法体同样校验，不靠名称猜新写法。
HELPERS = {
    "registerCopperBlock": (1,),
    "registerChiseledCopper": (1,),
    "registerWaxedChiseledCopper": (1,),
    "registerCopperBulb": (1, 2),
    "registerWaxedCopperBulb": (1, 2),
}
TOKEN = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[A-Za-z_$][\w$]*|\S', re.S)


class InvalidDeclaration(ValueError):
    pass


def tokens(source: str) -> list[str]:
    return [m.group() for m in TOKEN.finditer(source)
            if not m.group().startswith(("//", "/*"))]


def end_group(ts: list[str], start: int) -> int:
    closes = {"(": ")", "[": "]", "{": "}"}
    stack = []
    for i in range(start, len(ts)):
        if ts[i] in closes:
            stack.append(closes[ts[i]])
        elif ts[i] in closes.values():
            if not stack or stack.pop() != ts[i]:
                raise InvalidDeclaration("括号不配对")
            if not stack:
                return i
    raise InvalidDeclaration("未闭合声明")


def arguments(ts: list[str], start: int) -> tuple[list[list[str]], int]:
    end = end_group(ts, start)
    out, current = [], []
    i = start + 1
    while i < end:
        if ts[i] in ("(", "{", "["):
            stop = end_group(ts, i)
            current.extend(ts[i:stop + 1])
            i = stop + 1
        elif ts[i] == ",":
            out.append(current)
            current = []
            i += 1
        else:
            current.append(ts[i])
            i += 1
    if current:
        out.append(current)
    return out, end


def string(ts: list[str]) -> str:
    if len(ts) == 1 and ts[0].startswith('"'):
        return json.loads(ts[0])
    raise InvalidDeclaration("模型路径必须是字面量或已声明帮助方法的模型参数")


def model_id(method: str, arg: list[str]) -> str:
    if method == "addLocalVariant":
        value = string(arg)
        if ":" in value:
            raise InvalidDeclaration("addLocalVariant 不接受带命名空间的路径")
        return f"{OWN_NAMESPACE}:{value}"
    # 显式 ResourceLocation，仅认标准字面量构造；动态来源不能默默漏检。
    text = "".join(arg)
    match = re.fullmatch(r'(?:[\w.]+\.)?(?:parse|withDefaultNamespace)\(("(?:\\.|[^"\\])*"?)\)', text)
    if match:
        value = json.loads(match[1])
        return value if ":" in value else f"minecraft:{value}"
    match = re.fullmatch(r'(?:[\w.]+\.)?fromNamespaceAndPath\(("(?:\\.|[^"\\])*"),("(?:\\.|[^"\\])*")\)', text)
    if match:
        return f"{json.loads(match[1])}:{json.loads(match[2])}"
    raise InvalidDeclaration("addVariant 必须显式使用可解析的 ResourceLocation 字面量构造")


def declared_models(source: str) -> tuple[set[str], int]:
    ts = tokens(source)
    helper_bodies, covered_calls, models = {}, set(), set()
    # 识别帮助方法定义并获取模型参数名。
    for i, token in enumerate(ts[:-1]):
        if token not in HELPERS or ts[i + 1] != "(" or i == 0 or ts[i - 1] != "void":
            continue
        params, end = arguments(ts, i + 1)
        if ts[end + 1] != "{":
            raise InvalidDeclaration(f"帮助方法 {token} 缺少方法体")
        body_end = end_group(ts, end + 1)
        names = {params[index][-1] for index in HELPERS[token]}
        helper_bodies[token] = (end + 1, body_end, names, set())

    rotating = 0
    for i in range(len(ts) - 3):
        if ts[i:i + 3] != ["LivingIconSpec", ".", "builder"] or ts[i + 3] != "(":
            continue
        _, cursor = arguments(ts, i + 3)
        declarations = []
        is_rotating = False
        while cursor + 2 < len(ts) and ts[cursor + 1] == ".":
            method = ts[cursor + 2]
            args, cursor = arguments(ts, cursor + 3)
            if method in ("addLocalVariant", "addVariant"):
                if len(args) != 3:
                    raise InvalidDeclaration(f"{method} 应为三个参数")
                declarations.append((method, args[1]))
                covered_calls.add(cursor)
            elif method == "rotating":
                is_rotating = True
            elif method == "build":
                break
            elif method not in ("decorator", "directional", "guiScale"):
                raise InvalidDeclaration(f"未知图标 builder 调用 {method}")
        else:
            raise InvalidDeclaration("图标 builder 未找到 build()")
        if not declarations:
            raise InvalidDeclaration("图标声明无可识别变体")
        if is_rotating:
            rotating += 1
            continue
        for method, arg in declarations:
            owner = next((v for v in helper_bodies.values() if v[0] < i < v[1]), None)
            if owner and len(arg) == 1 and arg[0] in owner[2] and method == "addLocalVariant":
                owner[3].add(arg[0])
                continue
            models.add(model_id(method, arg))

    for name, (_, _, names, used) in helper_bodies.items():
        if names != used:
            raise InvalidDeclaration(f"帮助方法 {name} 的模型参数未全部由 addLocalVariant 使用")
    for i, token in enumerate(ts[:-1]):
        if ts[i + 1] != "(":
            continue
        if token in ("addLocalVariant", "addVariant"):
            _, end = arguments(ts, i + 1)
            if end not in covered_calls:
                raise InvalidDeclaration("存在 builder 链外的模型声明，检查器需显式支持")
        elif token.startswith("register") and i > 0 and ts[i - 1] != "void":
            args, _ = arguments(ts, i + 1)
            if token in HELPERS:
                if token not in helper_bodies:
                    raise InvalidDeclaration(f"未找到帮助方法定义 {token}")
                for index in HELPERS[token]:
                    models.add(f"{OWN_NAMESPACE}:{string(args[index])}")
            elif token not in ("register", "registerAll", "registerCopperIcons"):
                # 只在本文件的图标声明域内接受已知注册形状。
                raise InvalidDeclaration(f"未知图标注册调用 {token}")
    if not models:
        raise InvalidDeclaration("没有捕获到任何非旋转模型声明")
    return models, rotating


class ResourceCheck:
    def __init__(self, resources: Path):
        self.resources = resources
        self.checked = set()
        self.external = set()

    def resource_path(self, identifier: str, kind: str, suffix: str) -> Path | None:
        namespace, sep, path = identifier.partition(":")
        if not sep:
            namespace, path = "minecraft", namespace
        if namespace != OWN_NAMESPACE:
            self.external.add(identifier)
            return None
        if (not re.fullmatch(r'[a-z0-9_/.-]+', path) or path.startswith("/")
                or any(part in ("", ".", "..") for part in path.split("/"))):
            raise InvalidDeclaration(f"非法本地资源路径 {identifier}")
        return self.resources / "assets" / namespace / kind / f"{path}{suffix}"

    def model(self, identifier: str, active: tuple[str, ...] = ()) -> None:
        path = self.resource_path(identifier, "models", ".json")
        if path is None:
            return
        if identifier in active:
            raise InvalidDeclaration("本地模型 parent 环: " + " -> ".join((*active, identifier)))
        if identifier in self.checked:
            return
        if not path.is_file():
            raise InvalidDeclaration(f"缺少声明模型/parent: {identifier}")
        try:
            obj = json.loads(path.read_text(encoding="utf-8"))
        except (ValueError, OSError) as exc:
            raise InvalidDeclaration(f"模型 JSON 解析失败 {identifier}: {exc}") from exc
        if not isinstance(obj, dict):
            raise InvalidDeclaration(f"模型 JSON 必须是对象: {identifier}")
        parent = obj.get("parent")
        if parent is not None:
            if not isinstance(parent, str):
                raise InvalidDeclaration(f"parent 必须是字符串: {identifier}")
            self.model(parent, (*active, identifier))
        textures = obj.get("textures", {})
        if not isinstance(textures, dict):
            raise InvalidDeclaration(f"textures 必须是对象: {identifier}")
        for texture in textures.values():
            if not isinstance(texture, str):
                raise InvalidDeclaration(f"texture 必须是字符串: {identifier}")
            if texture.startswith("#"):
                continue
            tex = self.resource_path(texture, "textures", ".png")
            if tex is not None and not tex.is_file():
                raise InvalidDeclaration(f"模型 {identifier} 缺少本地纹理: {texture}")
        self.checked.add(identifier)


def check(root: Path) -> tuple[int, int, int]:
    models, rotating = declared_models((root / REGISTRY).read_text(encoding="utf-8"))
    checker = ResourceCheck(root / "src/main/resources")
    for model in sorted(models):
        checker.model(model)
    return len(models), len(checker.checked), rotating


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        entries, checked, rotating = check(args.root.resolve())
    except (InvalidDeclaration, OSError, IndexError) as exc:
        print(f"[check_icon_resources] 失败: {exc}")
        return 1
    print(f"[check_icon_resources] 通过：{entries} 个声明入口 / {checked} 个本地模型；"
          f"跳过 {rotating} 个旋转声明。外部资源/实际烘焙不在静态验证范围。")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

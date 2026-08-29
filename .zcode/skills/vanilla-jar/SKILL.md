---
name: "vanilla-jar"
description: "Browse and extract Minecraft + NeoForge jars (assets, source code, compiled classes). Invoke when user needs to check vanilla resources, Minecraft source, or NeoForge compiled classes."
---

# 原版 Minecraft & NeoForge Jar 浏览器

从 NeoForge 开发环境的 jar 文件中浏览和提取资源、源码、编译类。

> 本环境 shell 是 Git Bash，以下命令均为 bash 语法。

## 适用场景

- 查看原版方块/物品模型 JSON 结构及纹理变量（如 `#top`, `#lit`, `#unlit`）
- 查看原版纹理文件路径
- 查看原版 blockstates 配置
- 查看原版数据文件（配方、战利品表、标签等）
- 阅读原版 Minecraft 或 NeoForge 源码（.java 文件）
- 查看编译后的 .class 文件结构

## 四个 Jar 文件

| Jar | 大小 | 内容 |
|-----|------|------|
| `neoforge-21.1.230-client-extra-aka-minecraft-resources.jar` | 9.4 MB | 原版 Minecraft 资产（模型、纹理、blockstates、数据、声音等） |
| `neoforge-21.1.230-sources.jar` | 9.4 MB | 原版 Minecraft + NeoForge 源码（.java 文件） |
| `neoforge-21.1.230-merged.jar` | 32 MB | 合并后的编译类（.class 文件）+ 资产 |
| `neoforge-21.1.230.jar` | 23 MB | NeoForge 编译类 |

所有 jar 位于 `build/moddev/artifacts/` 目录（下文用 `$ART` 指代该目录）。

```bash
ART="build/moddev/artifacts"
RES="$ART/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar"
SRC="$ART/neoforge-21.1.230-sources.jar"
```

## 资源 Jar（resources）

**路径**: `build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar`

### 列出文件

```bash
jar tf "$RES" | grep "<关键词>"
# 若无 jar 命令，可用 unzip 替代：
unzip -l "$RES" | grep "<关键词>"
```

### 按路径过滤

```bash
# 方块模型
jar tf "$RES" | grep "models/block/"

# 物品模型
jar tf "$RES" | grep "models/item/"

# 纹理
jar tf "$RES" | grep "textures/"

# 方块状态
jar tf "$RES" | grep "blockstates/"

# 配方
jar tf "$RES" | grep "recipe/"

# 战利品表
jar tf "$RES" | grep "loot_table/"

# 标签
jar tf "$RES" | grep "tags/"
```

### 提取文件

```bash
# 在项目根目录执行，避免污染源码目录
jar xf "$RES" "assets/minecraft/models/block/comparator.json" "assets/minecraft/blockstates/comparator.json"
```

### 查看内容（不落盘）

```bash
unzip -p "$RES" "assets/minecraft/models/block/comparator.json"
```

### 目录结构

```
assets/minecraft/
├── models/
│   ├── block/          # 方块模型
│   └── item/           # 物品模型
├── textures/
│   ├── block/          # 方块纹理
│   └── item/           # 物品纹理
├── blockstates/        # 方块状态定义
├── sounds.json         # 声音定义
└── ...
data/minecraft/
├── recipe/             # 配方
├── loot_table/         # 战利品表
├── tags/               # 标签
└── advancement/        # 进度
```

## 源码 Jar（sources）

**路径**: `build/moddev/artifacts/neoforge-21.1.230-sources.jar`

包含原始 .java 源文件，可提取后直接阅读。

### 查找源码

```bash
# 查找某个类
jar tf "$SRC" | grep "ComparatorBlock"
```

### 提取源码

```bash
jar xf "$SRC" "net/minecraft/world/level/block/ComparatorBlock.java"
```

提取后文件在 `net/minecraft/...` 目录下。

### 源码目录结构

```
net/minecraft/          # 原版 Minecraft 源码
com/mojang/             # Mojang 库（blaze3d, math, serialization 等）
net/neoforged/          # NeoForge 源码
```

## 合并 Jar（merged）

**路径**: `build/moddev/artifacts/neoforge-21.1.230-merged.jar`

包含编译后的 .class 文件和资产，是运行时使用的完整 jar。

## 注意事项

- 提取的文件会留在项目根目录，用完后务必删除 `assets/`、`data/`、`net/`、`com/` 等临时目录
- jar 内的路径是 `assets/minecraft/...`，不是 `src/main/resources/...`
- 源码 jar 中的路径是 `net/minecraft/...`（Java 包路径）
- 切勿将提取的 jar 文件提交到版本控制

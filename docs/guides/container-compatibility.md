# 🌐 活物品容器兼容性指南

## 概述

活物品（活漏斗、活熔炉等）需要理解容器的「槽位布局」才能正确解析相邻槽位。不同模组的容器可能有不同的尺寸和列数，本系统通过 **配置文件 + 游戏内指令** 的方式灵活管理容器规则。

## 架构

```
┌──────────────────────────────────────────────────┐
│                三条路径                           │
│                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌────────┐ │
│  │ 配置文件注册   │  │ 游戏指令注册   │  │ 自动推断 │ │
│  │ (启动时加载)  │  │ (运行时注册)  │  │ (兜底)  │ │
│  └──────┬───────┘  └──────┬───────┘  └───┬────┘ │
│         │                 │               │       │
│         ▼                 ▼               ▼       │
│  ┌──────────────────────────────────────────────┐ │
│  │         ContainerCompatibilityConfig         │ │
│  │            (规则注册表 Map)                   │ │
│  └──────────────────┬───────────────────────────┘ │
│                     │                              │
│                     ▼                              │
│  ┌──────────────────────────────────────────────┐ │
│  │          SlotResolver.resolve()               │ │
│  │     (根据规则解析相邻槽位偏移量)               │ │
│  └──────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

## 配置文件

配置文件有两个层级：

### 1. 模组自带规则（项目级）

位于 `src/main/resources/assets/living_item/container_rules.json`，随 jar 打包发布。

**特点：**
- 源码可见，无需运行游戏即可查看和编辑
- 社区贡献的兼容性数据可通过 PR 合并
- 换整合包也不会丢失，因为规则在模组 jar 里

### 2. 玩家本地规则（实例级）

位于 `config/living_item/container_rules.json`，玩家通过指令注册的额外规则。

**特点：**
- 玩家在游戏内通过指令注册的规则保存在这里
- 不会覆盖模组自带规则（模组规则始终优先）
- 换整合包后需要重新校准（但模组自带规则仍然可用）

### 加载顺序

```
模组启动
  ├─ 1. 加载模组自带规则（assets/living_item/container_rules.json）← 始终加载
  └─ 2. 加载玩家本地规则（config/living_item/container_rules.json）← 可选，仅加载新增项
```

### 格式

MOD版本和玩家配置文件的格式是相同的，可以直接复制内容到配置文件中甄别喵。

| 类别 | 容器 | 规则 |
|------|------|------|
| 原版 | 箱子、木桶 | 27 格×9 列 |
| 原版 | 大箱子 | 54 格×9 列 |
| 原版 | 漏斗 | 5 格×5 列 |
| 原版 | 发射器、投掷器 | 9 格×3 列 |
| 原版 | 熔炉、高炉、烟熏炉 | 3 格×3 列 |
| 原版 | 酿造台 | 5 格×5 列 |
| 铁箱子 | 铜/铁/金/银/钻石/水晶/黑曜石箱子 | 36~144 格 |
| 精妙背包 | 背包 | 108~120 格 |
| 精妙存储 | 箱子/受限桶 | 16~80 格 |
| 存储抽屉 | 抽屉 | 4~12 格 |
| 功能存储 | 存储控制器 | 4~8 格 |
| 旅行者背包 | 背包 | 54 格 |

### 自动推断兜底

当容器未注册时，系统会按加权启发式自动推断列数：

```
候选宽度排序：9, 10, 12, 13, 8, 7, 6, 11, 5, 4, 3, 2, 1
例如：36 格 → 9 列（36%9=0，9 优先于 12）
      40 格 → 10 列（40%10=0，10 优先于 8）
```

## 游戏内指令

玩家可以通过指令在游戏内注册容器规则，无需修改配置文件。

### 指令列表

| 指令 | 说明 |
|------|------|
| `/livingitem container register <columns>` | 注册准心指向的容器，自动检测槽位数和容器 ID |
| `/livingitem container register <columns> <size>` | 手动指定槽位数（自动检测不准时纠错） |
| `/livingitem container register <columns> <containerId>` | 手动指定容器 ID |
| `/livingitem container register <columns> <size> <containerId>` | 全手动指定 |
| `/livingitem container inspect` | 查看准心指向容器的规则（含**来源**：内置 / 玩家覆盖 / 玩家注册） |
| `/livingitem container list` | 列出所有已注册的规则（`+` = 玩家注册，`*` = 玩家覆盖内置） |
| `/livingitem container remove <containerId>` | 移除指定规则（删内置规则会记入 `removed`，重启不复活） |
| `/livingitem container reload` | 从配置文件重新加载（幂等，会重建全部状态） |
| `/livingitem container export` | **开发期**：导出**全量生效规则快照**到 `config/living_item/exported_rules.json` |

> ⚠️ **`register` 会覆盖已存在的规则**（2026-09-20 起）。此前重复注册会被拒绝，
> 导致玩家发现内置数据有误时**无法修正**——而内置数据确实可能出错
> （见 [`living-item-infrastructure.md` §5.2](../system-design/living-item-infrastructure.md) 的 IronChests 勘误）。
> 覆盖内置规则会记入玩家配置，可用 `export` 导出。

### 社区贡献：文件级覆盖

`export` 导出的**不是差异，而是全量生效快照**（内置 + 玩家新增 − 玩家删除，按 ID 排序）。
所以那份文件**已经包含作者原有的全部内置条目**，可以直接**整体复制覆盖**：

```
config/living_item/exported_rules.json
        ↓  整文件复制粘贴（无需逐条摘录、无需合并脚本）
src/main/resources/assets/living_item/container_rules.json
        ↓  ./gradlew build
兼容性随模组发布给所有玩家
```

作者侧的注意点：

- **冲突先到先得 + WARN**：合并多份玩家贡献后同一 ID 出现两条不同数值时，
  保留先出现的一条并打警告；只比 `containerSize`/`columns`，描述措辞不同不算冲突。
- **编码固定 UTF-8**：`save`/`export`/`load` 三处均显式 UTF-8，跨平台交换不会乱码。

### 使用示例

```bash
# 场景1：标准用法
/livingitem container register 9
# → 准心指向箱子，自动检测容器 ID 和槽位数

# 场景2：自动检测不准，手动纠错
/livingitem container register 9 54
# → 手动指定槽位数为 54，跳过自动检测结果

# 场景3：全手动指定
/livingitem container register 12 108 mymod:big_chest
# → 指定容器 ID 为 mymod:big_chest，108 槽位，12 列
```

## 核心类说明

### ContainerCompatibilityConfig

规则注册表，管理所有容器规则。

| 方法 | 说明 |
|------|------|
| `register(id, rule)` | 注册容器规则 |
| `remove(id)` | 移除容器规则 |
| `findRule(id)` | 按容器 ID 查找规则 |
| `findOrGenerateRule(size)` | 查找或自动生成规则 |
| `getAllRules()` | 获取所有已注册规则 |

### ContainerRuleConfig

配置文件管理器，负责 JSON 文件的读写。

| 方法 | 说明 |
|------|------|
| `init(path)` | 初始化配置目录 |
| `load()` | 重新加载（幂等：先清空状态，再读内置资源 + 玩家差异） |
| `save()` | 保存**玩家差异**（新增/覆盖/删除），不回写内置规则 |
| `addAndSave(id, rule)` | 注册或**覆盖**并保存；返回是否覆盖了已有规则 |
| `removeAndSave(id)` | 移除并保存（内置规则会记入 `removed`，否则重启复活） |
| `exportBundledFormat()` | 开发期：导出**全量生效规则快照**（与内置资源同格式，不含 `removed`），返回条数；失败返回 `-1` |
| `countBundled()` / `countUser()` | 生效规则中来自内置 / 玩家新增的条数（用于 `export` 提示与启动日志） |
| `isBundledRule(id)` | 该 ID 是否来自内置资源（与是否被覆盖无关） |
| `isUserModified(id)` | 玩家是否动过它（新增/覆盖/删除） |
| `isRemovedByUser(id)` | 该 ID 是否被玩家显式删除（重启不复活） |

### ContainerRule

容器规则记录，描述一个容器的布局信息。

| 字段 | 说明 |
|------|------|
| `containerSize` | 槽位总数 |
| `layoutType` | 布局类型（RECTANGULAR_STANDARD / LINEAR 等） |
| `columns` | 列数 |
| `validHostSlots` | 可放置活物品的槽位列表 |
| `directionMappings` | 方向 → 偏移量映射 |
| `edgeBehavior` | 边界行为（INVALIDATE / WRAP） |
| `crossBlockEntitySupport` | 是否支持跨方块实体（如大箱子） |
| `description` | 描述文字 |

## 代码注册（已废弃）

~~内置规则已全部移除，改为配置文件 + 指令注册。~~ 参见 [配置文件格式](#配置文件-active)

## 常见问题

### Q: 自动推断的列数不对怎么办？

使用指令手动指定：
```bash
/livingitem container register 9 54
# 手动指定 54 槽位，9 列
```

### Q: 配置文件的规则可以手动编辑吗？

可以，编辑 `config/living_item/container_rules.json`，然后执行：
```bash
/livingitem container reload
```

### Q: 大箱子跨方块实体的支持还有吗？

有，大箱子（`minecraft:double_chest`）的规则在配置文件中自动注册，其 `crossBlockEntitySupport` 由 `SimpleContainerContext` 在运行时自动检测并启用。

### Q: 如何查看当前已注册的规则？

```bash
/livingitem container list
```

### Q: 哪些容器需要注册？

以下情况需要注册：
- 自动推断的列数不正确（如 96 格容器推断为 12 列，但实际是 8 列）
- 容器有特殊布局（如漏斗只有中间几个槽位可放置活物品）
- 容器跨多个方块实体（如大箱子、精妙存储的升级箱子）
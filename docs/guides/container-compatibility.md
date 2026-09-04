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
| `/livingitem container list` | 列出所有已注册的规则 |
| `/livingitem container remove <containerId>` | 移除指定规则 |
| `/livingitem container reload` | 从配置文件重新加载 |

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
| `load()` | 从配置文件加载（首次自动创建默认文件） |
| `save()` | 保存所有规则到配置文件 |
| `addAndSave(id, rule)` | 注册并保存 |
| `removeAndSave(id)` | 移除并保存 |

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
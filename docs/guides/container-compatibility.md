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
| 无尽（AvaritiaNeo） | 无尽箱子 | 300 格×25 列 |

**规则键是 `(容器 ID, 容量)` 两段，不是单 ID**（2026-10-19 起）：同一 ID 可以有多档
（精妙背包六档 27/45/54/81/108/120 各一条）。查找顺序是 `findRule(id, size)` 精确命中，
命中不到再 `findRule(id)` 取该 ID 的唯一档。⇒ **加多档容器时，每档都要单独登记**；
只登记一档时另一档会落到启发式。

**`contentRetaining`（内容随物品走的容器）**：独立的**声明集合**，不是 `ContainerRule` 的字段。
列在这里的容器，破坏时容器级持久数据（流体 / 电力相位）会跟着掉落物搬走；**不在表里 ⇒ 不搬**
（保守：让数据跟内容一起消失，避免「空箱子但有水」）。玩家可在 `config/living_item/container_rules.json`
**追加**，但不能取消内置声明。契约见 [container-boundary.md](../system-design/container-boundary.md) I-C9。

### 自动推断兜底

当容器未注册时，系统会按加权启发式自动推断列数：

```
候选宽度排序：9, 10, 12, 13, 8, 7, 6, 11, 5, 4, 3, 2, 1
例如：36 格 → 9 列（36%9=0，9 优先于 12）
      40 格 → 10 列（40%10=0，10 优先于 8）
```

## 实体容器（船 / 矿车 / 带箱驴骡）

实体宿主 2026-10-21 起是第四类 tick 宿主（见 [container-boundary.md](../system-design/container-boundary.md) I-C1）。
它**不走**上面的规则表（`register` 只认方块容器 ID），列数来源有两条：

| 实体 | 列数来源 |
|---|---|
| 马 / 驴 / 骡 | **原版 API** `AbstractHorse#getInventoryColumns()`（= 5）—— 原版给了权威答案就用它 |
| 箱子船 / 箱子矿车 / 漏斗矿车 | 启发式（27 → 9 列、5 → 5 列，已实测） |

两条要点：

- **白名单门槛**：`ItemHandler.ENTITY` 对**所有** `LivingEntity` 都有 fallback ⇒ 能力面非空
  **不是**「这是容器」的判据。实体容器必须过 `ContainerEntity` / `hasChest()` 白名单。
- **前置马鞍槽**：马 / 驴 / 骡的 inventory 索引 0 是马鞍 ⇒ 逻辑槽 0 对应 handler 槽 1。
  新增这类「前置非存储槽」时必须一次性枚举全部跨边界点（读写 / 同步包 / 缓存键 / GUI 反查），
  否则表现为「东西都在、位置全错」——见 I-C10。

> ⚠️ **指令目前只支持方块容器**（`register` / `inspect` 都按准心方块解析）。实体容器的列数
> 校准是**未实现**的（启发式四类全对 ⇒ 暂不需要）；将来要支持，入口在
> `EntityContainerContext.getWidth()` 的「先按实体类型 id 查规则表」分支。

## 新容器形态接入清单（2026-10-22 定）

> **来源**：活背包真机首测「能 tick，但槽位对不上、tooltip 空」—— 因为只接了 6 条线就以为做完了。
> 这张表把「一个新容器形态要让里面的活物品正常工作」拆成 11 条，**逐条打勾才算接完**。

| # | 线 | 具体是什么 | 不接会怎样 |
|---|---|---|---|
| 1 | **tick 驱动** | 谁让它被 tick：框架扫描 / 功能内部递归 / viewer 驱动。**掉落物形态另需功能自声明** `LivingItemFunction#supportsDroppedItem()`（默认 false） | 里面的活物品不动；掉落物形态会「看着在跑、其实永远不生效」 |
| 2 | **ctx 身份** | `getContainerKey` + `getCacheKey`，必须跨 tick 稳定 | 每次 tick 都是「新容器」⇒ 容器级数据丢 |
| 3 | **槽位几何** | `getSize` / `getWidth` / `toPhysicalSlot`·`toLogicalSlot` | **邻居判定错**（流体流错格、漏斗连错槽） |
| 4 | **内容读写** | 背后的 `IItemHandler`（能力面 / 菜单 / 自定义） | 读不到也写不进 |
| 5 | **数据持久化** | `getDataHosts()` + `persist()`：容器级数据存哪、谁写 | 不 tick 就丢、重启就丢 |
| 6 | **菜单解析** | `ContainerContexts.resolve(player, slot)` → ctx | **tooltip 空、倒汲错格、GUI 设置错位** |
| 7 | **viewer 匹配** | **唯一入口** `ContainerViewers.of(players, cacheKey, positions)`：按缓存键分派（玩家态⇒主人+末影箱门控 / 物品形态⇒菜单锚点 / 其余⇒位置）。两条数据轨（流体快照、运行时数据）都只调它 | 流体层 / 实时数据不渲染 |
| 8 | **客户端同步** | 内容变化与容器级数据下发 | GUI 看到旧状态 |
| 9 | **激活与清理** | 怎么变成活容器、取消活化怎么清 | 全都在跑 / 残留孤儿数据 |
| 10 | **世界语义** | `ContainerAnchor`（`AtBlock`/`AtPlayer`/`AtEntity`/null）回答「容器在世界里**在哪**」，取哪个点由功能自己定；物品形态（活背包）另可**继承宿主锚点** `getHostAnchor()`（谁要用谁显式拿） | 靠位置的逻辑失效（爆炸 / 应力 / 射线 / 信标光束） |
| 11 | **嵌套** | 容器里有容器：深度上限 + 环检测 | 无限递归 / 卡死 |

**两条经验**（都来自这次踩坑）：

- **#1 和 #6 是两条独立路径**。tick 可以自己建 ctx（功能内部递归），
  而菜单解析走的是 `ContainerContexts.resolve` —— **只接前者会出现「能 tick 但交互全废」**，
  而且症状看起来像「槽位算错了」，实际是「设置时和 tick 时用的不是同一个 ctx」。
- **#6 的默认实现只兜「无锚点」的常态**。`ItemFormContainers.Source.handleOfMenu` 的默认实现是
  「扫玩家物品栏 + **唯一一件才认**」——玩家带**两个**同类活容器时必然返回 null
  （2026-10-23 真机：两个活背包 ⇒ 活流体倒不了）。⇒ 只要模组的菜单对象能反查到
  「正在看的是哪一个」，联动模块就**必须覆写**它（精妙的
  `BackpackContainer#getBackpackContext() → getBackpackWrapper(player).getBackpack()` 就是现成锚点）。
- **#3 的列数不要猜**。`resolveColumns(size)` 是启发式（`{9,10,12,13,8,7,6,11,5,4,3,2,1}`
  里找第一个能整除的）：27/45/54 都得到 9，但**槽位数不是 9 的倍数时（如含升级槽的 30）会选 10**。
  能拿到权威列数就显式提供，不要依赖启发式。
- **#7 只能有一个入口**。这个判定原本散在三条轨里各写一份 if-chain，加新形态时漏改一条就静默失效
  （2026-10-23 一天内连踩两次：流体轨修了、运行时轨没修 ⇒ 真机「流体正常但 tooltip 空」）。
  ⇒ 新形态**只改 `ContainerViewers` 一个分支**，别在调用点另写。
- **#10 的锚点是「参考物」不是坐标**。同一容器在不同用途要不同点（爆炸取中心、应力取下方、
  射线取眼睛），这个差异是对的 ⇒ 框架只答「在哪」，取点归功能。
  物品形态（活背包）缺的是**宿主**：`getHostAnchor()` 递下去，但**默认谁都不用**——
  用它会变成加强玩法（背包里的工具自动挖矿 / 水车随身发电 / 信标随身光环），必须逐个拍板。

**活背包的勾选状态**（2026-10-23）：

✅ 1 · ✅ 2 · ✅ **3（`Source#columns` 权威列数，2026-10-23 真机验收：储罐插件动态列数正常）** · ✅ 4 · ✅ 5 · ✅ **6（含精妙菜单锚定覆写）** ·
✅ **7（`ContainerViewers` 唯一入口，两条数据轨同源）** · ⚠️ 8（部分）· ✅ 9 · ❌ 10（**已定不做**：归活潜影箱）· ✅ 11



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
| `remove(id)` | 移除该 ID 的全部档 |
| `removeTier(id, containerSize)` | 只移除某一档（多档容器用） |
| `findRule(id)` | 按容器 ID 查找规则（该 ID 只有一档时用） |
| `findRule(id, containerSize)` | 按 **(ID, 容量)** 精确查找 —— 多档容器的正确查法 |
| `findRuleBySize(size)` | 按容量查找（跨 ID，给「只知道几格」的场景兜底） |
| `findOrGenerateRule(size)` | 查找或自动生成规则 |
| `getAllRules()` | 获取所有已注册规则 |
| `registerContentRetaining(id)` | 声明「内容随物品走」（破坏时搬容器级持久数据，见 I-C9） |
| `retainsContentsOnBreak(id)` | 查询该 ID 是否声明了 `contentRetaining` |

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
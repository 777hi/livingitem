# 服务端菜单自省（布局探测 + 实体容器）· 设计稿

> **状态：部分完成（2026-10-21）** —— 用户拍板「B+B」：位置广播经查证证伪关闭（§0.1），
> **实体容器端到端已实施并真机验证通过**（§2.2 定案，§6 并为一段；含两处真机修正：
> 驴的马鞍槽偏移、逻辑槽/物理槽双口径贯通）。**列数探测仍延后**（§0.2：四类实体容器启发式
> 全对，且它是纯优化非缺口）—— 本文因此仍留在 buffer（唯一未落地项）。
> 契约正文见 [system-design/container-boundary.md](../system-design/container-boundary.md)（I-C1~I-C10）。
> 本文只写「做什么、怎么做、做到什么算完」。

---

## 0. 为什么立文（两个悬而未决的问题）

### 问题一：新容器的列数只能靠人录

依赖几何的 8 个活物品域（熔炉 / 漏斗 / 酿造 / 信标 / 耕地 / 红石 / 蜡镀铜电力 / 流体）
的兼容性完全押在 `container_rules.json` 的列数覆盖上。未收录的容器靠启发式猜，
猜错时这 8 个域一起失效——**症状分裂**：熔炉「不烧」（可见）、漏斗「搬错格子但仍在搬」（隐形）。

### 问题二：实体容器从未进门

箱子船 / 箱子矿车 / 漏斗矿车 / 驴骡带箱，四类都有 `ItemHandler.ENTITY`
（NeoForge `CapabilityHooks` 显式注册前三类，`AbstractHorse` 靠 fallback），
但扫描器只有「区块 BE」一条路 ⇒ 完全不 tick。
`ContainerKeys` 里预留的 `container_<dim>_x_y_z` 一天没用过。

### 0.1 「位置广播」经查证件：不存在需求，B①关闭（2026-10-21）

用户直觉是「移动容器的位置相关视觉要跟着实体走 ⇒ 需要位置广播（每 tick/每 N tick 发给附近客户端）」。
查证后**唯一候选需求就消失了**：

- 唯一候选是**活信标光束**（唯一按世界位置渲染、GUI 无关的视觉）。
- 但信标宿主判据是 `isLiddedChest(...)`，而 `LidBlockEntity` 的实现**只有三个、全是方块实体**
  （`ChestBlockEntity` / `EnderChestBlockEntity`）——船、矿车、驴、骡一个都不是。
- ⇒ **实体容器里的活信标压根不开光** ⇒ 没有「光束要跟着船走」。

枚举全部位置相关视觉后的结论：信标光束（不开光）/ 活流体 GUI 轨（关箱即失，不需广播）/
图标与 tooltip（viewer 匹配**每次实时解**，已是最新）⇒ **无一需要广播**。

> 顺带的认知收获（用户自省「当时把信标限定在有盖容器是偷懒」）：`LidBlockEntity` 这个判据
> **不是顺手，是选对了**——它其实是「**容器有世界存在感**」的代理指标：有盖箱子在 GUI 之外
> 你看得见盖子开合，实体容器关箱后 GUI 外什么都看不见，没有需要锚定的世界存在。
> 若判据当年写成「任何 27 格容器」，今天才真的要还一笔广播债。
> ⇒ **先问「这条限制为什么存在」，再问「它挡住了什么」**——前者问出意义，后者证明是资产。

### 0.2 列数探测延后：实体容器启发式全对（2026-10-21 查证）

`resolveColumns` 的候选宽度顺序 `{9,10,12,13,8,7,6,11,5,4,3,2,1}` 让四类实体容器**全部命中**：

| 容器 | 槽数 | 启发式 | 事实 |
|---|---|---|---|
| 箱子船 / 箱子矿车 | 27 | **9** ✓ | 原版 `ChestMenu.threeRows` = 9 列 |
| 漏斗矿车 | 5 | **5** ✓ | 原版一排 5 个 |
| 驴 / 骡带箱 | 15 | **5** ✓ | 原版 `AbstractHorse#getInventoryColumns()` = 5 |

⇒ §2.1 的列数缓存**降级为纯优化**（改善「未登记新模组容器」），不再是实体容器的前置。
它的优先级低于「把探测机制本身想透」（不规则布局的众数错误、缓存淘汰），
按 §6 新排序**延后实施**；规则表 + 启发式继续服务。

### 共用的缺口

两个问题都指向同一件事：**服务端没有从「玩家菜单」这条路获取容器信息。**

---

## 1. 关键事实（查证过，含两次纠错）

> ⚠️ 本节是全文的地基。**它推翻过两个想当然的判断**，所以把证据列全。

### 1.1 服务端**有**槽位坐标 —— 不是「只有客户端知道」

- `Slot` 带 `public final int x; public final int y;`（`world/inventory/Slot.java:15-16`）
- `addSlot(new Slot(container, k + j * 9, 8 + k * 18, 18 + j * 18))` 是**双端都会跑的构造逻辑**
  （`ChestMenu` 等菜单类），x/y 在 `addSlot` 时写进 `Slot` 对象
- ⇒ 每个打开容器的玩家，服务端的 `player.containerMenu.slots` 里就带着完整布局

**纠错记录**：设计过程中两次断言「列数是客户端属性，服务端看不到」，
并提出「客户端探测 + 上报」方案。**前提是错的**——我们只是从没读过服务端的菜单对象。

### 1.2 两端坐标天然一致 ⇒ 不需要同步、不需要信任

客户端 `handleOpenScreen` 也用**同一套 `addSlot` 代码**构造自己的菜单副本。

> 坐标不走网络，因为两端各自算、算的是同一套逻辑。

所以服务端读**自己的**那份即可，**完全不必问客户端**——「客户端不可信」这个约束从根上消失。
这正是它优于「客户端上报」的全部理由。

### 1.3 已有两个「会动的实体容器」先例

```
① getPlayerList()  → player.getInventory()   键 = player_<uuid>
③ level.getAllEntities() → ItemEntity …      键 = item_entity_<uuid>
```

⇒ 实体容器的键形答案是 **`entity_<uuid>`**，不是预留的 `container_<dim>_x_y_z`（位置键，会随移动漂移）。
`ContainerKeys` 的 javadoc 明写「新增键形 ⇒ 只改本类」，加前缀即可。

### 1.4 「能力面存在 ≠ 该当容器」

NeoForge 的 fallback 给**所有** `LivingEntity` 注册 `ItemHandler.ENTITY`（主手 + 副手 + 盔甲）。
所以扫实体必须按**实体类型白名单**，否则僵尸盔甲槽也成容器。
这与 I-C1「只认能力面」构成张力：**能力面是必要条件，不是充分条件。**

---

## 2. 方案：一个机制，两个用途

新增**服务端菜单自省**（`MenuIntrospection`）：每 tick 从在线玩家的 `containerMenu`
读出「这个菜单对应哪个容器 + 那个容器几列」，喂给两个消费方。

```
每 tick：扫在线玩家的 containerMenu（!= inventoryMenu 的）
   ├─ 解析菜单归属 → 容器身份（位置 or UUID）   ─┐
   └─ 解析槽位布局 → 列数                       ─┤ 两个消费方
                                              ↓
            ① 列数缓存（BE 容器 tick 时查）
            ② viewer 匹配补强 / 实体容器身份
```

### 2.1 消费方一：列数缓存

优先级改为：

```
① 服务端菜单探测缓存（玩家开过这个容器）
② container_rules.json 规则表（显式数据）
③ Tier 2 原版类型表（ChestBlockEntity → 9 …）
④ Tier 3 加权启发式
```

缓存键：`(方块实体类型 id, 容器大小)` → `columns`。

> **为什么规则表仍在探测之前/之后要讨论**：规则表是权威数据（玩家可校准），
> 探测是自动学习。倾向 **规则表优先、探测补空**（探测只填规则表没覆盖的），
> 理由是规则表可被玩家**主动纠正**，而探测对不规则布局可能学错且无人发现。

### 2.2 消费方二：实体容器

新增第 ④ 条枚举路径：

```java
// onServerTick，与 ①②③ 并列
for (var entity : level.getAllEntities()) {
    if (!ENTITY_CONTAINER_TYPES.contains(entity.getType())) continue;   // 白名单
    processContext(new EntityContainerContext(entity, level), level);
}
```

| 维度 | 设计 |
|---|---|
| 白名单 | `CHEST_BOAT` / `CHEST_MINECART` / `HOPPER_MINECART` / **`AbstractHorse`**（按 `hasChest()` 判 15 格） |
| 键形 | `entity_<uuid>`（照 `item_entity_<uuid>`） |
| 容器级数据 | 挂**实体附件**（照玩家的 `CONTAINER_FLUID_DATA_PLAYER` 思路；NeoForge 支持实体 attachment） |
| 列数 | 27→9、5→5、15→5 启发式已对；`AbstractHorse.getInventoryColumns()` 是**原版权威 API**，应优先用它 |
| 跨形态 | 按 I-C9 **不迁移**（船/矿车/驴破坏后内容不跟物品走） |
| viewer 匹配 | 见 §3 降级边界 |

---

## 3. viewer 匹配：不降级——实体容器天然正确（2026-10-21 查证翻案）

原稿（10-21 晨）判断「菜单不存实体位置 ⇒ viewer 三级全断 ⇒ 只能降级」。
**查证后翻案：实体容器反而是位置口径的最佳展示。**

### 事实链

**① 船/矿车的菜单槽 container 就是实体本身。**

```java
// ChestBoat.createMenu —— this 即船（ContainerEntity extends Container）
return ChestMenu.threeRows(containerId, playerInventory, this);
// MinecartChest.createMenu ——— 同
```

1.21 的 `ContainerEntity extends Container` ⇒ `MenuSlotOwners` 结构化分支只要加
`container instanceof Entity`，**船与两种矿车零反射**。

**② 驴需要一条 accessor，但是范式内的。**

NeoForge 给 `AbstractHorse` 的 fallback 是 `new InvWrapper(horse.getInventory())` ⇒
驴菜单槽的 container 是匿名 `InvWrapper`，结构化认不出来。它的位置在
`HorseInventoryMenu.horse`（`private final AbstractHorse`，无 getter）⇒
照项目已有范式 `SlotAccessorFactory` 加 Mixin accessor——**Minecraft 类在编译 classpath 上，
比第三方反射硬一个量级**（D-container-09 不禁止对原版类用编译期手段）。

**③ viewer 匹配天然实时 ⇒ 比固定容器还准。**

- `MenuSlotOwners.resolve` **无缓存**，每次 flush 都重新解析；
- `entity_<uuid>` 键每 tick 从 `ServerLevel#getEntity(UUID)` 反查实体当前位置
  （API 已确认存在，`ServerLevel:1326`）；
- `EntityContainerContext` 每 tick 新建（与掉落物 ctx 同范式）⇒ 位置快照即当前 tick。

⇒ 开着箱子船 GUI 时船在漂，**图标和 tooltip 跟着船的真实位置刷新**。
固定容器位置不变所以看不出区别；实体容器是「位置是唯一事实源」这条口径的活广告。
**所谓「漂走」是伪问题——它只在「解析结果被缓存」时才存在，而我们从不缓存。**

**④ 唯一真正的新增只是 accessor**（驴）。GUI 内的倒/汲走 `ContainerContexts.resolve`
的新实体分支（slot.container 直达，不绕位置）。

---

## 4. 验收判据

| # | 判据 | 怎么测 |
|---|---|---|
| 1 | 船/矿车/驴里的活熔炉开始 tick | 游戏内实测 |
| 2 | 实体容器键 `entity_<uuid>` 稳定（移动不漂移） | 单测：键形只依赖 UUID；游戏内：推船走一段再开 |
| 3 | 非白名单实体不被当容器（僵尸盔甲槽不 tick） | 单测：白名单过滤（`ContainerEntity` + `AbstractHorse.hasChest()`） |
| 4 | 开着箱子船 GUI 时船在动，图标/tooltip 跟着船的实时位置刷新 | 单测（resolve 返回实体当前位置）+ 游戏内 |
| 5 | 船 GUI 内能对背包槽倒水（GUI 边界带） | 单测：`resolve` 实体分支 |
| 6 | 无人区块停 tick 但**不丢数据**（划回来接着烧） | 单测（容器级数据宿主=实体附件）+ 游戏内 |
| 7 | 船破坏 ⇒ 内容掉出继续 tick；流体随附件消失（I-C9 自动对齐） | 游戏内 |
| 8 | 活信标在船里不开光（`LidBlockEntity` 判据） | 单测或游戏内 |

---

## 5. 明确不做

- **不做位置广播**（§0.1：唯一候选需求被证伪，枚举后无一需要广播）
- **不做「所有实体都能当容器」**（§1.4 I-C1 张力；白名单外一概不 tick）
- **不做「船永远 tick」**：实体容器走 `getAllEntities()` ⇒ 超出 simulation distance 即停 tick
  （与 BE 卸载、掉落物路径、玩家下线**三条既有路径完全一致**）。停 tick ≠ 丢数据：
  进度在活物品自己的组件、流体/相位在实体附件（NeoForge 明示 "written, and read from disc"）
  ⇒ 划回来接着烧。强行全局 tick 需要自建持久实体账本——那是我们在消灭的东西。
  且载具无 `checkDespawn` ⇒ 卸载也不会消失。
- **暂不做客户端列数探测/上报**（§0.2 延后 + §1.2 信任论证：正解是服务端菜单，非客户端）
- **不改 `container_rules.json` 的定位**（它仍是权威数据与玩家校准入口；四类实体容器
  启发式已全对，无须加规则）

---

## 6. 实施阶段（2026-10-21 定：并为**一段**，列数探测延后）

用户拍板「直接上实体容器，跳过列数探测」。四段合并为一段端到端实施——
因为①②段的探测机制对实体容器已无必要（§0.2），独立做第一段会做出「躺缓存里、玩家零感知」
的半截价值。

| 项 | 内容 | 要点 |
|---|---|---|
| ① | `ContainerKeys` 加 `entity_<uuid>` + `ContainerKind.ENTITY` | 照 `item_entity_<uuid>` 先例；键形唯一解析入口 |
| ② | `EntityContainerContext`（新类，继承 `SimpleContainerContext` explicitKey 构造器） | positions = 实体实时位置；每 tick 新建 ⇒ 无陈旧问题 |
| ③ | 实体枚举 pass：与掉落物**合并**为一次 `getAllEntities()` 遍历 | 白名单 = `ContainerEntity`（船/两矿车）+ `AbstractHorse.hasChest()`；能力面四类均有（NeoForge 显式注册 3 + fallback 1） |
| ④ | 容器级数据宿主抽象：`ContainerContext#getPrimaryDataHost()` 返回 `IAttachmentHolder` | BE 与 Entity 同接口 ⇒ 流体/相位 writeback 不改语义即支持实体附件；掉落物返回 null 自然跳过 |
| ⑤ | `runtimeContainerPositions` 认 `entity_` 键 | `ServerLevel#getEntity(UUID)` 逐维反查 |
| ⑥ | `MenuSlotOwners` 结构化分支认 `Entity`；`resolveContainerEntity`（驴走 `HorseInventoryMenu` accessor） | 船/矿车零反射；accessor 照 `SlotAccessorFactory` 范式 |
| ⑦ | `ContainerContexts.resolve` 实体分支 | GUI 内倒/汲直达（slot.container 即实体，不绕位置） |
| ⑧ | 列数：**不接探测**，`getWidth` Tier 3 启发式已全对（§0.2） | 若未来加新模组容器再走 §2.1 |

列数探测（原一二段）**保留设计、延后实施**，优先级触发条件：真机出现「规则表没收录、
启发式算错」的具体容器。

---

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 每 tick `getAllEntities()` 全扫 + 能力查询 | 与掉落物合并为**一次**遍历；白名单判据是 `instanceof` 短路；能力查询每个容器实体每 tick 一次（`InvWrapper` 分配，相对容器 tick 成本可忽略） |
| 实体附件是新的持久化面 | 只存已被证明该持久的（附件带 serialize 判据，I-C9）；驴/船破坏即随实体消失（I-C9 对齐：内容不跟走 ⇒ 属性也不留） |
| 船恰好停在箱子上方 ⇒ `getBlockEntity(船位置)` 拿到别的箱子 | `EntityContainerContext` 的容器级数据宿主是**实体本身**，不经过位置查 BE ⇒ 误碰地形 BE 的路径不存在；信标侧 `isLiddedChest(level.getBlockEntity(pos))` 需防误判 ⇒ 让 ctx 的 `getBlockPos()` 返回 null（它不是方块容器，见 ItemEntityContainerContext 同款语义） |
| `AbstractHorse` 无箱时 inventory 只有 2 格（鞍+盔甲） | 白名单判 `hasChest()`；无箱马不进容器模型（活马铠没有意义） |
| 船/矿车有 loot table 未 unpack（`getItemStacks()` 为空） | 空容器自然无活物品；unpack 后自愈，无需特殊处理 |

---

## 附：与已记录条目的关系

- `docs/TODO.md` 的「客户端列数探测」**前提已错**（§1.1/1.2）；§0.2 起延后实施，
  TODO 条目保留为 backlog（触发条件：真机出现规则表没收录且启发式算错的容器）
- `docs/TODO.md` 的「实体容器」相关内容并入本文 §2.2 / §6
- 设计模板 `living-item-design-template.md` §1.4 第一条（列数覆盖）暂不因本文改动
  （探测延后 ⇒ 规则表+启发式仍是唯一机制）
- §0.1 的「LidBlockEntity = 世界存在感代理」判据属跨切认知，已同步进活信标文档

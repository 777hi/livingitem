<!-- markdownlint-disable -->






> 💬 用户随手记已原样归档至 `§0.5`，并展开为 `§3.12 L 组（记忆与回放）`。此处不再重复。





# 活工具系列（活镐子 / 活斧子 / 活铲子 / 活锄头）

> **状态：设计已收敛，实施中 —— 挖掘记忆回放已实装并通过实测。**
>
> 📄 **技术文档已独立**：[`docs/tech/living-tool-tech.md`](tech/living-tool-tech.md)
> （本文档保留**问题池与讨论过程**，技术结论以 tech 文档为准）
>
> 结构：`§0` 原始需求（不动）→ `§1` 语义理解 → `§2` 架构盘点 → `§3` **待决问题池** → `§4` 落地顺序草案 → `§5` 探讨日志。
>
> **用法**：`§3` 每个问题拍板后回填「结论」列并标日期；`P0` 全部收敛后再开工写代码。
>
> ### ⭐ 设计原则（2026-09-18 用户定调）
>
> **优先给玩家「信息与手段」，而不是在代码里兜底。**
> 例：射线擦边导致 MISS → 不做浮点余量兜底，而是把射线**可视化**给玩家看，
> 玩家发现不对就重录一次记忆。功能逻辑要考虑**玩家的主观能动性**。
>
> 最后更新：2026-09-20

---

## 0. 原始需求（用户原话，原样保留）

> 新活物品：活工具系列：活镐子、活斧子、活铲子、活锄头
>
> 例如活木镐子：活化后放在玩家背包里，会在玩家身边显示一个悬浮的活木镐子。玩家无需手持镐子，挖掘石头时，悬浮的活木镐子会自动帮助玩家挖掘方块。背包里有多个活镐子，会一起挖掘，加快挖掘速度。
>
> 精准采集和时运附魔优先级依据活镐子在背包里的排序，靠前的高优先级。

---

## 0.5 需求补充（用户原话，2026-09-18，原样保留）

> 掉落物也工作喵。
>
> 先确定一下活工具的具体工作逻辑吧喵：
> 就拿活斧子举例喵。
> 活工具记忆能力：
> 1.玩家手持活斧子，朝着某方向（玩家视角射线）挖掘多少格远的方块，活斧子会记住这个挖掘方向和挖掘距离，只能记住一个最新的记忆，新记忆覆盖旧记忆。这个记忆暂时称之为挖掘记忆。玩家蹲下时该记忆还会记住挖掘方块的类型。
> 2.手持活斧子右键原木去皮，活斧子会记住这个右键动作的朝向和交互距离。这个记忆暂时称之为交互记忆。玩家蹲下时该记忆还会记住交互方块的类型。
> 3.活斧子可以同时拥有挖掘记忆和交互记忆。
> 4.没有记忆的活斧子在容器里的功能逻辑会同其它活物品一样，对周围槽位里的物品施加一些影响，目前暂时不做容器里的功能逻辑，先做容器外的世界交互逻辑。
>
> 在容器里：活斧子会依据拥有的记忆与周围方块交互。例如有一个朝南方向挖掘两格远方块的记忆，那么在容器里活斧子就会尝试挖掘朝南方向两格远处的方块；如果有一个朝上方向右键交互1格远处的原木橡木的记忆，那么容器里的活斧子就会尝试朝上方向1格远处的原木橡木交互，只与原木橡木交互，不会与其它类型的方块交互。
> 在玩家背包里：无记忆时辅助玩家挖掘，有记忆时，回放记忆操作。
> 掉落物形态：与在容器里的功能逻辑类似。

---

## 1. 语义理解（当前口径，待确认）

| # | 理解 | 来源 |
|---|---|---|
| 1 | 工具活化后放在**玩家背包**里即生效，**不需要手持** | 原话 |
| 2 | 客户端在**玩家身边悬浮显示**工具模型 | 原话 |
| 3 | 玩家挖方块时，悬浮工具**自动帮忙挖**；多把 → 更快 | 原话 |
| 4 | **精准采集 / 时运**归属按**背包槽位顺序**，靠前者优先 | 原话 |

延伸推断（原文未明说，需确认）：帮忙挖 = 「加速玩家**当前正在挖**的那个方块」，
而非「悬浮工具自己去挖别的方块」。→ 见 `A3`。

---

## 2. 架构盘点

### 2.1 已有能力（可直接复用）

| 能力 | 位置 |
|---|---|
| 玩家背包**每 tick 已全量扫描**，活物品进背包即刻进入 `tick()` | `LivingItem.java:311-313`（`ServerTickEvent.Pre` → `processContainer(player.getInventory(), ...)`） |
| 从容器上下文反查 `Player` 的先例 | `ContainerLivingItemHandler.updatePlayerFeetStressOutput`（L353-363） |
| 背包槽位改动同步客户端（inventoryMenu + containerMenu 双菜单已处理） | `SimpleContainerContext.syncPlayerInventory`（L373-417） |
| 背包容器 key = `player_<uuid>`，网格宽 9 | `SimpleContainerContext`（L107-110 / L136-140） |
| **工具语义判定的正确范式**：不枚举物品，认 `canPerformAction(ItemAbility)` | `domain/farmland/Tillables` + `ItemAbilities.HOE_TILL`（2026-09-16 活锄头兼容改造） |
| 「无 tick、纯事件驱动」功能骨架 | `LivingFlintAndSteelFunction`（tick 空转）+ `LivingMapEventHandler` |
| 手持物判定范式（主手 → 副手 → null） | `LivingMapEventHandler.getHeldLivingMap`（L193-200） |
| 附魔读取 | `ItemEnchantments` DataComponent，现成 |

> **关键复用点**：镐/斧/铲照抄活锄头的 `ItemAbility` 语义判定（`PICKAXE_DIG` / `AXE_DIG` / `SHOVEL_DIG`），
> **模组工具自动兼容，无需枚举物品清单**。

### 2.2 需要从零建的三块

| 缺口 | 说明 |
|---|---|
| **① 「玩家正在挖什么」** | 全项目 **0** 相关代码。破坏进度是 `ServerPlayerGameMode` 私有状态（`destroyProgressStartTick`），无公开 getter；`BreakSpeed` / `LeftClickBlock` 事件均未订阅过。→ **核心技术难点**，见 `B` 组 |
| **② 客户端世界内悬浮渲染** | `client/render/**` 全部 14 个文件都是 `GuiGraphics`（tooltip / 槽位角标）。**全项目无任何 `RenderLevelStageEvent` / `ClientTickEvent` 订阅**，无世界空间管线。→ 见 `K` 组 |
| **③ `ContainerContext` 无 `getPlayer()`** | 现状只能 `instanceof SimpleContainerContext` 强转（活水车即如此）。建议补 `default Player getOwnerPlayer() { return null; }`，避免 domain 层 import 实现类 |
| **④ 项目没有任何 3D 模型** | `assets/living_item/models/item/` 85 个 json **全部**是 `parent: minecraft:item/generated` 的**平面精灵**；`models/block/` 与 `blockstates/` 是**空目录**。→ 直接渲染会得到一张纸片（billboard），见 `K3` |
| **⑤ 掉落物（ItemEntity）形态完全没处理** | 全项目 `ItemEntity` 仅 3 处命中且都是爆炸/掉落，**无 tick、无渲染、无生命周期**。服务端 tick 只看 `getBlockEntities()`（`processLevelContainers`），掉落物形态的活物品**不会工作** → 见 `K2` |

### 2.2.1 📦 原版 / NeoForge / 第三方模组源码（2026-09-18 整理）

**源码都在 `libs/src/`**（该目录已被 `.gitignore` 忽略，且**不会被 `gradlew clean` 删除**）：

| 目录 | 内容 |
|---|---|
| `libs/src/neoforge-21.1.249-merged/` | ⭐ **优先** —— MC + NeoForge 合并源码，版本与项目 `neo_version` 一致（2026-09-18 从 `build/moddev/artifacts/neoforge-21.1.249-sources.jar` 解压） |
| `libs/src/neoforge-21.1.230-merged/` | 旧版备用（21.1.230，含 `.class`） |
| `libs/src/Documentation-main/` | NeoForge 官方文档（`docs/` 为 1.21.x） |
| `libs/src/Create-mc1.21.1-6.0.10/`<br>`libs/src/sable-main/`<br>`libs/src/Mekanism-1.21.x/`<br>`libs/src/FarmersDelight-1.21/`<br>… | 第三方模组源码 —— **做兼容时直接查** |

直接搜索即可，无需解压：

```
search_content(path="libs/src/neoforge-21.1.249-merged/net/minecraft/...", pattern="...")
```

重建方式（若目录缺失）：

```powershell
mkdir "libs\src\neoforge-21.1.249-merged" 2>$null
tar -xf "build\moddev\artifacts\neoforge-21.1.249-sources.jar" -C "libs\src\neoforge-21.1.249-merged"
```

> ⚠️ 搜项目代码时把 `path` 指定为 `src/`，否则 `libs/` 的上万文件会淹没结果。
> ⚠️ `mc1-21-1--` / `neoforge-docs` 两个 skill 里的路径都是**相对项目根**，不是相对 skill 目录。

### 2.3 已确认的好消息（2026-09-17 调研）

| 结论 | 依据 |
|---|---|
| **掉落物形态判定不需要任何自定义包** | `IS_LIVING` 注册时带 `networkSynchronized(ByteBufCodecs.BOOL)`（`LivingItemManager.java:80-85`），`ItemEntity` 的 stack 走实体同步 → 客户端 `isLivingItem(itemEntity.getItem())` **直接可读** |
| **动画时间源有现成先例** | `mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(true)` —— `ItemRendererWaterWheelMixin:85-86`，活水车旋转即如此 |
| **动作动画可零包驱动** | 新增 **network-only** DataComponent（只有 `networkSynchronized`、无 `persistent`）即可，现成先例：`LIVING_FURNACE_BURNING`（`LivingItemManager.java:94-99`）、`LIVING_HOPPER_FILTER` |
| **声明式规格有最佳落点** | `LivingIconRegistry` / `LivingIconSpec` 是全项目唯一的「按物品类型声明式描述表现」处，已有 `rotating` / `directional` / `guiScale` 等表现开关先例，加 `.floating(...)` 语义一致 |
| **真 3D 渲染有先例（但只适用于方块）** | `mc.getBlockRenderer().renderSingleBlock(...)` —— `AbstractContainerScreenMixin:805-809`（活耕地生长槽大图）。**镐子没有对应方块，此路不通**，见 `K3` |

---

## 3. 待决问题池

**优先级**：`P0` = 决定技术路线 / 不定就写不了代码；`P1` = 玩法数值，可后调；`P2` = 锦上添花。

### A. 触发与作用范围

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| A1 | P0 | 在哪里生效？ | a) 仅玩家背包 b) 背包 + 末影箱 c) 任意容器（箱子里也能远程帮挖） | **a**——"悬浮在玩家身边"暗示绑定玩家本体 | *待定* |
| A2 | P0 | 玩家手持**普通（非活）**工具挖掘时还帮吗？ | a) 帮 b) 只在空手/非工具时帮 | **a**——原话是"无需手持镐子"，非"必须空手" | *待定* |
| A3 | P0 | 「帮忙挖」的语义 | a) 加速玩家**当前正在挖**的方块 b) 悬浮工具自己挖准星/脚下方块 c) 仅提供"正确工具"判定不加速 | **a**——最贴近原版手感。<br>⚠️ 2026-09-18：这已降级为**支线**（仅「背包 + 无记忆」时用）；主线是 `L` 组的**记忆回放** | *待定* |
| A4 | P1 | 创造 / 旁观模式 | a) 直接跳过 b) 仍渲染不生效 | **a**（创造破坏瞬时，无进度可加速）；渲染另议 | *待定* |
| A5 | P1 | 距离限制 | a) 天然受限（只加速当前目标） b) 显式半径检查 | **a**——随 A3 定 | *待定* |

### B. 挖掘进度获取（技术难点）

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| B1 | P0 | 如何拿到「正在挖的方块 + 进度」？ | a) Mixin `ServerPlayerGameMode`（读 `destroyProgressStartTick` / 拦截 `handleBlockBreakAction`）——精确<br>b) 纯事件近似：`LeftClickBlock` 记目标 + 每 tick 判持续挖掘，`BreakEvent`/超时清除——无 mixin，但精度差 | **a**——加速必须精确对齐进度条。<br>⚠️ 2026-09-18：**降级**——只服务于 `A3` 支线（`L` 组记忆回放主线不需要它），可最后做 | *待定* |
| B2 | P0 | 加速结果怎么施加？ | a) 推进原版破坏进度 b) 到阈值直接 `destroyBlock` c) 走 `BreakSpeed` 事件改速度 | 待 B1 定；倾向 **c 或 a** | *待定* |
| B3 | P1 | 中断/切换目标的处理（松手、换目标、被击退、方块已被挖） | 追踪器按 UUID 维护 `DigTarget`，逐条列清理条件 | 逐条列 | *待定* |

### C. 速度叠加（最容易做崩）

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| C1 | P1 | 多把叠加公式 | a) 线性求和 b) 取最快 + 其余衰减 c) 取最大值 d) **各算各的，不叠加** | ✅ **d（各算各的）**（2026-09-18）—— 回放场景每把挖自己的进度，**无需求叠加公式**。<br>⚠️ a/b/c 只在「无记忆 + 辅助玩家挖掘」支线需要（多人同时敲一块） | ✅ d（回放）/ 支线待定 |
| C2 | P1 | **堆叠数**参与加速吗？ | a) 参与（项目惯例：活箱子 ×27、活耕地产出 ×堆叠数、活红石粉信号上限） b) 不参与 | ✅ **b（不参与）**（2026-09-18 随 `L5` 一并定）<br>→ **原版工具 `maxStackSize=1`，根本不会堆叠**，此问在实践中不成立；<br>→ 加速只来自**多个独立槽位的多个 ItemStack**（见 `C1`），与堆叠数无关 | ✅ b |
| C3 | P1 | 是否封顶？ | a) 有上限 b) 不封顶 | **a** | *待定* |
| C4 | P1 | 与原版效率附魔、急迫效果的关系 | 乘算 / 加算 | 需对齐原版 `getDestroySpeed` 公式 | *待定* |

### D. 准入（能不能挖 / 掉不掉）

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| D1 | P0 | 材质门槛用哪把判定？（同时有木镐 + 钻石镐，能挖黑曜石吗） | a) 用**背包里最好的一把** b) 用排序最前的一把 c) **各判各的** | ✅ **c（各判各的）**（2026-09-18 随 `L6=模拟玩家` 自然推出）<br>→ 回放时用的是**那把活工具自己**的材质，门槛天然由它自己决定。<br>⚠️ 但 a 仍适用于「无记忆 + 辅助玩家挖掘」支线 → 转 `D4` | ✅ c（回放）/ 见 D4（辅助） |
| D2 | P1 | 正确工具判定（`canHarvest`）与速度用同一把吗？ | a) 同一把 b) 分开 | ✅ **a（同一把）**（2026-09-18）—— 同 `D1`，都是那把活工具自己 | ✅ a |
| D4 | P1 | 【仅辅助玩家支线】材质门槛用哪把？ | a) 背包里最好的一把 b) 排序最前的一把 | 待 `A3` 支线开工时再定 | *待定* |
| D3 | P1 | 模组方块的挖掘等级 | 走 `TierSortingRegistry` | 标准做法 | *待定* |

### E. 附魔归属

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| E1 | P0 | 时运 / 精准采集的归属 | a) 按背包排序取**第一个带其中任一**的 b) 取排序最前那把的**全部**附魔作为唯一生效集 c) **各自用各自的** | ✅ **c（各自用各自的）**（2026-09-18 随 `L6=模拟玩家` 自然推出）<br>→ 回放时用的是**那把活工具自己**的附魔，天然归属。<br>⚠️ 原始需求「按排序取靠前者」**仍适用于**辅助玩家支线 → 转 `E6` | ✅ c（回放）/ 见 E6（辅助） |
| E2 | P0 | **「背包排序」的精确定义** | a) `IItemHandler.ENTITY` 槽位号升序（0-35 主栏 / 36-39 盔甲 / 40 副手）<br>b) 快捷栏 0-8 优先，再主背包<br>c) 玩家自定义 | **a**——最简单可预期，与 `SlotEntry.slotIndex` 天然一致。<br>（仅辅助玩家支线需要） | *待定* |
| E3 | P1 | 效率附魔：多把都有效率 III | a) 取最大 b) 叠加 c) **各用各的** | ✅ **c（各用各的）**（2026-09-18）—— 回放场景每把自己的效率决定自己的速度 | ✅ c |
| E4 | P1 | 耐久 / 经验修补 | a) 各自独立生效 b) 只作用于主贡献者 c) **各扣各的** | ✅ **c（各扣各的）**（2026-09-18）—— 同 `F2`，挖的那把自己的耐久/经验修补 | ✅ c |
| E6 | P1 | 【仅辅助玩家支线】时运/精准按排序取靠前者 | a) 取第一个带其中任一的 b) 取最前那把的全部附魔 | 待 `A3` 支线开工时再定（这是**原始需求**的原文要求，不能丢） | *待定* |
| E5 | P2 | 模组挖掘类附魔 | 通用遍历 `ItemEnchantments` / 白名单 | 通用优先 | *待定* |

### F. 耐久与损耗

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| F1 | P0 | 帮忙挖**扣不扣**耐久？ | a) 扣 b) 不扣（活工具 = 永动机） c) 配置可选 | **a**——不扣则原版工具被彻底取代 | *待定* |
| F2 | P0 | 扣哪一把？ | a) 只扣主贡献者 b) 参与的分摊 c) **各扣各的**（挖的那把扣自己） | ✅ **c（各扣各的）**（2026-09-18 随 `L6=模拟玩家` 自然推出）<br>→ 模拟玩家挖掘 = 那把工具自己在挖 → 扣它自己的耐久，天经地义 | ✅ c |
| F3 | P1 | 挖爆之后 | a) **消失**<br>b) 变普通物品（失去活化）<br>c) 留 1 点耐久不再消耗 | ✅ **a（消失）**（2026-09-19 用户定）<br>→ 最原版：耐久归零即消失，玩家需自行保护活工具<br>⚠️ 实现注意：`mineBlock` 会把它变成空物品，需用 `context.setItem()` 写回触发脏槽位同步 | ✅ a |
| F4 | P2 | 经验修补的吸经验球归属 | 跟随 F2 的主贡献者 | — | *待定* |

### G. 客户端呈现（三形态）

**三种宿主形态**（2026-09-17 用户确认，G 组问题需按形态分别回答）：

| 形态 | 宿主 | 客户端可见性 | 同步成本 |
|---|---|---|---|
| ① 玩家身边 | `Player` 背包 | 本人已知自己的背包 | 低（本人 + 可广播给他人） |
| ② 容器周围 | 箱子等方块容器 | **不开 GUI 客户端不知道内容** | **高：需新增 S2C 包** |
| ③ 掉落物上方 | `ItemEntity` | ✅ 实体 stack 已同步，`IS_LIVING` 可读 | **零：无需自定义包** |

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| G1 | P1 | 悬浮显示几个？ | a) 每把都显示 b) 1 个代表 + 数量角标 c) 环绕一圈、上限 N 个 | **c**——既体现"多把"又不刷屏 | *待定* |
| G2 | P1 | 悬浮位置 | a) 绕玩家缓慢公转 b) 固定方位（如右肩后） c) 跟随视线 | 待定 | *待定* |
| G3 | P2 | 挖掘时是否有挥动动画 | a) 有 b) 无 | **a**——否则"帮忙挖"没有反馈 | *待定* |
| G4 | P1 | 其它玩家看得到吗？ | a) 只有自己 b) 全员可见 | **b** 更符合"世界里的东西"，但需 S2C 广播 + 同步包 | *待定* |
| G5 | P0 | 渲染技术路线 | 新增 `RenderLevelStageEvent` 订阅 + 复用 `ItemRenderer`；数据经 S2C 包下发（参考 `LivingItemSyncPacket` / `CarriedUpdatePacket`） | 唯一可行路线，待确认 | *待定* |

### H. 系列边界与四件套对称

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| H1 | P0 | **活锄头已存在**（GUI 里耕活土，物品层交互）。它属于"活工具系列"吗？ | a) 是，纳入统一框架 b) 否，保持独立 | **a**——统一后口径一致，但需保证不回归 | *待定* |
| H2 | P1 | 斧/铲的**非挖掘** `ItemAbility` 要不要世界层辅助？（`AXE_STRIP` 剥皮 / `AXE_SCRAPE` 刮铜 / `AXE_WAX_OFF` 脱蜡 / `SHOVEL_FLATTEN` 铺土径） | a) 要，右键世界方块时自动辅助 b) 暂不做 | 先不做，主线跑通再说 | *待定* |
| H3 | P1 | 是否给镐/斧/铲加 **GUI 层语义**（与活锄头对称） | a) 加 b) 不加 | 原倾向 b → **用户拍板 a** | ✅ **加**（2026-09-17）：活斧子去皮、活铲子铺土径、活镐子暂无右键。细节见 `J` 组 |
| H4 | P2 | 系列边界在哪？活剑 / 活剪刀 / 活钓鱼竿 / 活打火石算不算？ | — | 活打火石已是交互触发器，边界需明确 | *待定* |

### I. 工程与平衡

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| I1 | P1 | 配置项（开关 / 上限 / 衰减系数） | 注意：`Config.java` 目前**全是模板样板**，无任何真实配置项 | 建议至少加总开关 | *待定* |
| I2 | P1 | 性能：背包扫描已是每 tick 全量热路径 | 新增逻辑是否节流？ | 建议复用已有扫描结果，不新增遍历 | *待定* |
| I3 | P1 | 与「活物品隔离」规则的关系（不传输 / 不熔炼 / 不作为燃料） | 活工具继承现有规则即可 | 继承 | *待定* |
| I4 | P1 | 测试策略：FML 单测能覆盖什么？ | 进度追踪 / 叠加公式 / 附魔归属可测；mixin 与渲染难测 | 优先保证纯逻辑可测 | *待定* |

---

### ⭐ `A3` 支线定案（2026-09-20）—— 无记忆活工具「辅助玩家挖掘」

**边界（用户定）**：**有记忆的活工具不帮忙** —— 记忆成了「分工开关」：
录了记忆 → 自己干活（`L` 组）；没录 → 帮玩家挖。

**⭐⭐ 技术方案：零 mixin**（原设计里标着"唯一技术难点"的 `B1` 整个消失）

| 需求 | NeoForge 官方钩子 | 用法 |
|---|---|---|
| **加速** | `PlayerEvent.BreakSpeed`（在 `Player#getDigSpeed` 内触发） | `setNewSpeed(原速 + Σ 各活工具速度)` |
| **材质门槛** | `PlayerEvent.HarvestCheck`（在 `hasCorrectToolForDrops` 内触发） | 任意一把活工具挖得动 → `setCanHarvest(true)` |
| **时运 / 精准采集归属** | `BlockDropsEvent` | 按槽位顺序取最靠前的一把，用它重算掉落 |

→ 因此 `B1`（Mixin `ServerPlayerGameMode`）、`B2`（怎么施加）、`B3`（中断处理）**全部不需要**：
`BreakSpeed` 每 tick 由原版自动触发，"玩家正在挖哪一格"它自己会告诉我们，中断 / 换目标天然被处理。

**其余定案**

| # | 定案 |
|---|---|
| `A1` | 仅在**玩家背包**生效 |
| `A2` | 手持普通（非活）工具时**也帮** |
| `A4` | 创造 / 旁观**跳过**（破坏是瞬时的，没有进度可加速） |
| `A5` | **不需要**距离限制（只作用于玩家当前目标，天然受限） |
| `C` | 多把速度**直接相加、不设上限** —— 用户口径：*"玩家背包槽位数量就已经是上限了"* |
| `D4` | 材质门槛：**任意一把活工具挖得动即可** |
| `E2` | "背包排序" = **槽位号升序**（与 `SlotEntry.slotIndex` 一致） |
| `E6` | 时运 / 精准采集：取**槽位最靠前**的那把（**原始需求原文**，不可丢） |
| `F` | **每把参与的活工具都扣耐久**，且**耐久附魔生效** |

**顺带新增需求（用户 2026-09-20 提出）**：活工具 tooltip 显示一些信息 —— 细节待定。

---

## 3.10 J. 容器内交互语义（GUI 层）

**边界**：本组 = **容器内玩家参与的交互**（光标活工具 + 槽内活物品 → 转换）。
**不是**世界方块层（对着世界里的原木右键去皮 → 那属于 `H2`，暂缓）。

### 三件套总表（2026-09-17 用户定稿方向）

| 工具（光标，须活） | 目标（槽内，须活） | 产物 | ItemAbility | 状态 |
|---|---|---|---|---|
| 活斧子 | 活原木类 | 活去皮原木 | `AXE_STRIP` | 待细化 |
| 活铲子 | 活土类 | 活土径 | `SHOVEL_FLATTEN` | 待细化 |
| 活锄头 | 活土类 | 活耕地 / 活泥土 | `HOE_TILL` | ✅ 已实现（`Tillables` + `TillToFarmlandHandler`） |
| 活镐子 | — | — | — | **明确不做**（用户 2026-09-17） |

### 已定

| # | 结论 | 依据 |
|---|---|---|
| J0 | **目标必须活化** | 用户 2026-09-17 拍板；且是**框架硬约束**：`InteractionRegistry.findInteraction:83` 统一校验 `isLivingItem(target)`，与 `Tillables.canTillWith` 同口径 |
| J-1 | 沿用活锄头骨架：`triggerItem=null`（通配）+ `triggerFilter` 谓词 + 独立 `InteractionHandler` | `InteractionEntry` javadoc + `TillToFarmlandHandler` |

### ⭐ `J3` 答案：不用写映射表，直接调原版方法（2026-09-18 源码核实）

`IBlockExtension#getToolModifiedState`（`IBlockExtension.java:778-824`）把各动作**委托给了静态查询方法**，
而这些方法是 **`public static`**，可以直接调用：

| 动作 | 委托目标 | 签名 |
|---|---|---|
| `AXE_STRIP`（去皮） | `AxeItem` | `public static BlockState getAxeStrippingState(BlockState)`（`AxeItem.java:118`） |
| `SHOVEL_FLATTEN`（铺路） | `ShovelItem` | `public static BlockState getShovelPathingState(BlockState)`（`ShovelItem.java:82`） |
| `HOE_TILL`（耕地） | 内联 | 逻辑与项目 `Tillables` **完全一致**（已交叉验证） |
| `AXE_SCRAPE` | `WeatheringCopper.getPrevious` | — |
| `AXE_WAX_OFF`（脱蜡） | `DataMapHooks.getBlockUnwaxed` | 走 NeoForge DataMap |

**`getAxeStrippingState` 内部**（`AxeItem.java:119-122`）：

```java
var strippable = originalState.getBlock().builtInRegistryHolder()
        .getData(NeoForgeDataMaps.STRIPPABLES);            // ← 先查 NeoForge DataMap
if (strippable != null) return strippable.strippedBlock().withPropertiesOf(originalState);
Block block = STRIPPABLES.get(originalState.getBlock());   // ← 再回退原版 Map
return block != null ? block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, ...) : null;
```

→ **先查 DataMap 再回退原版表** —— 意味着**模组通过 DataMap 注册的去皮关系自动兼容**，
  比手写枚举表（原方案 a）**更好**。

**结论**：`J3` 走 **c（直接调原版方法）**，`J4`/`J5` **无需枚举目标集**，
判据就是「该方法返回非 null」。

> 附：`HOE_TILL` 分支确认了原版还有两个**世界副作用** ——
> 「上方必须是空气」与「缠根泥土掉落垂根」。项目 `Tillables` 刻意不做（物品层无世界上下文），**保持一致即可**。

### ⚠️ 抄骨架时必须遵守的两条硬约束

1. **通配条目会拦截所有右键** —— `triggerItem=null` 时客户端拦截面是「任意光标」，
   不匹配虽会静默返回，但原版拿起/放置/分堆已被吞。**必须**用 `triggerFilter` 收窄。
   （`InteractionEntry` javadoc 2026-09-13 实测反馈）
2. **谓词内必须自查 `isLivingItem(trigger)`** —— `findInteraction:91` 只在**精确条目**路径校验 trigger 活化；
   通配路径（L85-89）不校验，漏了会让**非活**斧子也被拦截并吞掉原版操作。
   （`Tillables.canTillWith` 首行即为此）

### 待决

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| J1 | P0 | **产物保留「活」标记吗？**（活原木 → 去皮原木，还活吗） | a) 保留 b) 变普通物品 | **a**——活化是状态，不该被一次交互消耗；且保留才能继续参与后续活物品交互 | *待定* |
| J2 | P1 | 一次转换**整堆**还是 1 个？ | a) 整堆 b) 1 个 | **a**——沿用 `TillToFarmlandHandler`（`new ItemStack(result, source.getCount())`） | *待定* |
| J3 | P0 | **映射表从哪来？** | a) 手写 Item→Item 表<br>b) 运行时反查私有 Map<br>c) **直接调原版 public static 方法** | ✅ **c**（2026-09-18 源码核实，见下方 `⭐ J3 答案`）<br>**不需要手写任何表** | ✅ c |
| J4 | P1 | **去皮目标集**纳入哪些？ | — | ✅ **不用枚举** —— `getAxeStrippingState(state)` 返回非 null 即可去皮 | ✅ 自动 |
| J5 | P1 | **土径目标集**纳入哪些？ | — | ✅ **不用枚举** —— `getShovelPathingState(state)` 返回非 null 即可<br>⚠️ 仍注意与锄头目标**重叠**（草方块/土/砂土/缠根泥土）→ 靠 `triggerFilter` 按光标类型区分 | ✅ 自动 |
| J6 | P1 | 耐久消耗 | 沿用活锄头：生存 `hurtAndBreak(1)`，创造不扣但**同样校验光标** | 沿用 | *待定* |
| J7 | P1 | **多合一工具**冲突：某模组工具同时能 `AXE_STRIP` + `SHOVEL_FLATTEN` | 同 target 两条通配规则 → `findInteraction:85-89` 取**首个通过 filter 的**（按注册顺序） | 需定优先级或明确接受"先注册者胜" | *待定* |
| J8 | P2 | 活斧子扩展：`AXE_SCRAPE`（刮铜）/ `AXE_WAX_OFF`（脱蜡） | a) 要 b) 暂不 | 提出待议——项目有大量铜系列活物品，**脱蜡**与红电「涂蜡 = 绝缘」设定可能联动 | *待定* |
| J9 | P1 | **「材料型活物品」定位**：活原木 / 活土 = 只有 `IS_LIVING`、**无** `LivingItemFunction`（活泥土已是先例） | 需确认活化 UI 是否对任意物品开放 | 待确认 | *待定* |

---

## 3.11 K. 世界空间悬浮模型（渲染 / 动画 / 同步）

> 目标（用户 2026-09-17）：活工具要真的「活」—— 悬浮 + **待机动作**（转圈、摇晃）+ **功能动作动画**，
> 像宝可梦精灵一样。本节只谈**渲染与动画载体**，玩法数值不在此。

### K.1 渲染管线（全项目从零建）

| 项 | 结论 |
|---|---|
| 接入点 | 订阅 `RenderLevelStageEvent`，stage = **`AFTER_ENTITIES`**（此时深度缓冲已含地形+实体，可被箱子正确遮挡） |
| 掉落物路径 | `RenderLevelStageEvent` + 遍历 `mc.level.entitiesForRendering()`，筛 `ItemEntity` 且 `isLivingItem(...)`。**不用 mixin** —— mixin `ItemEntityRenderer#render` 的 `@At("TAIL")` 时 PoseStack 已 `popPose()`，直接画会错位 |
| 容器路径 | 同上，但数据源来自新 S2C 包（客户端不开 GUI 拿不到内容） |
| PoseStack | 事件给的 PoseStack 已是**相机相对**坐标，需 `translate(pos - cameraPos)` |
| **packedLight** | ⚠️ **不能**用 `LightTexture.FULL_BRIGHT`（那是 GUI 先例 `AbstractContainerScreenMixin:808`）。世界空间用 `LevelRenderer.getLightColor(level, pos)` |
| **深度** | ⚠️ **不要** `RenderSystem.disableDepthTest()`（那是 GUI 套路 `RecipeBookComponentMixin:411`）。保持开启才能被地形遮挡 |
| **光照** | ⚠️ **不要** `RenderSystem.setShaderLights(FRONT_LIGHT_*)`（那是 GUI flat-item 套路，`ItemRendererWaterWheelMixin:66`） |

**渲染核心**（三形态共用，一次写好）：
`translate` → 待机/动作动画 → `mc.getItemRenderer().render(stack, GROUND/FIXED, ...)` 或 `renderSingleBlock`
⚠️ 复用 `ItemRenderer#render` 会触发 `ItemRendererWaterWheelMixin` 的 HEAD/RETURN 钩子（实例字段），
只要不重入即安全，但需意识到这层耦合（`isGuiContext` 含 `GROUND`，掉落物本来就命中）。

### K.2 待决问题

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| K1 | P0 | **掉落物形态要不要「工作」（tick）？** | a) 只显示模型，不 tick（掉落物 = 休眠态）<br>b) 也 tick（掉落物也能帮挖 / 自己动） | ✅ **b**（2026-09-18 用户拍板「掉落物也工作」）<br>→ 实现走 `ItemEntityContainerContext`（把掉落物包装成**单栈容器**，复用现成 `processContext` 管线）<br>✅ **已实现**（2026-09-19） | ✅ b |
| K2 | P0 | **容器形态的 S2C 同步怎么做？** | a) 新增独立包（dim + 位置 + ItemStack）<br>b) 复用/改造 `LivingItemSyncPacket`<br>c) 复用 `ContainerRuntimeCache` | ✅ **a**（2026-09-19 定案 + **已实现**）<br>b/c 是 tooltip 通道，**只发给正在开 GUI 的玩家**（`isViewingContainer`），承担不了世界渲染广播<br>→ 落地为 `LivingToolHostPacket` / `LivingToolHostSync` / `LivingToolHostClientCache`<br>📌 **三处简化**（用户拍板）：① 同步 **ItemStack** 一次满足射线+模型+动画<br>② **无节流** —— 内容去重后本就是按需发送<br>③ **状态字段先不加** —— 挖掘靠原版裂纹推断<br>④ R = **32** = 原版破坏裂纹广播半径（必须一致，否则"看得到工具却看不到裂纹"） | ✅ a |
| K3 | **P0** | **模型从哪来？**（见 `K.3` 方案对比） | a) 复用现有 flat 物品模型（一张纸片）<br>b) **新建真 3D 模型 json**（Blockbench → `elements`）<br>c) 先 a 验证玩法，再补 b | **c**，其中 b 推荐走 **方案 A（3D 物品模型 JSON + 独立 standalone 路径 + PoseStack 动画）** | *待定* |
| K4 | P1 | 容器形态显示几个 / 怎么排？ | a) 箱子上方绕圈排布 b) 固定一个位置 c) 上限 N 个 + 角标 | 待定；注意**大箱子有两个 BlockPos 需去重**（`ContainerRuntimeCache.isViewingContainer` 注释：大箱坑已踩过一次） | *待定* |
| K5 | P1 | 掉落物堆叠数 > 1 时显示几个？ | a) 1 个 b) 按堆叠数 c) 上限 N + 角标 | **a 或 c** | *待定* |
| K6 | P0 | **动作动画由什么驱动？** | a) **network-only DataComponent**（服务端写 `LIVING_ACTION{id, startTick}`，客户端读，权威、零自定义包）<br>b) 客户端本地猜（`mc.gameMode.isDestroying()`，零延迟但不权威）<br>c) 专用 S2C 包 | **a**（照抄 `LIVING_FURNACE_BURNING`）。b 可作为纯表现层补充 | *待定* |
| K7 | P1 | 待机动画相位 | 必须落到**世界轴时间** `level.getGameTime()`，否则不同客户端/重进游戏不同步 | 硬约束（项目已有此意识：熔炉/耕地都用世界轴时间戳） | *待定* |
| K8 | P1 | 性能：距离 / 视锥裁剪 | 必做。`RenderLevelStageEvent#getFrustum()` + `distanceToSqr` 半径（建议 24~32） | 全项目**无任何裁剪先例**，需新写；`Config` 需新增项（目前只有 4 个样板项） | *待定* |
| K9 | P1 | 同步频率 / 定向 | 容器包建议 4~10 tick 一次 + **按玩家距离定向**（勿学 `EnderChannelSyncPacket` 全服广播），只在集合变化时发增量 | 硬约束 | *待定* |
| K10 | P2 | 穿透显示（被箱子挡住时仍可见） | a) 不做 b) 第二遍 RenderType + disableDepthTest | **a**——先不做 | *待定* |
| K11 | P1 | 悬浮模型的声明处 | 扩展 `LivingIconSpec`（加 `FloatingSpec`：modelPath / scale / offsetY / spin / bob / actionAnim），`LivingIconRegistry.getFloatingSpec(Item)` 查表 | **推荐**——与 `rotating`/`directional`/`guiScale` 语义一致 | *待定* |

### ⚠️ K3 详解：这可能是最大的一块工作量

- 项目 **85 个 item json 全是 `parent: minecraft:item/generated` 平面精灵**，`models/block/` 和 `blockstates/` 是**空目录**。
- 直接拿物品模型做世界渲染 → **一张纸片**（billboard），"转圈"会看到纸片侧过来消失。
- 活耕地那套 `renderSingleBlock`（真 3D）**用不了**：它是渲染**方块**，而**镐子/斧子/铲子没有对应方块**。
- 原版物品中只有三叉戟/盾/弓/床等少数有真 3D 模型，**工具没有**。
- → **必须手工建 3D 模型 json**（镐头 + 手柄的 box elements），每个工具一套；若四种工具 × 各材质 = 工作量可观。
- 建议口径：**先只做「活木镐子」一种**跑通全链路（模型 + 动画 + 三形态），确认手感后再批量。

### K.3 3D 模型实现方案对比（2026-09-17 调研）

**现状确认**：`build.gradle` 依赖里**没有 GeckoLib**（只有 Create / Sable 两个软依赖）；
`build.gradle:25` 已 `exclude("**/*.bbmodel")` → Blockbench 是可预期的工作流；
项目已有 `BakedModel` 包装机制（`RotatingWaterWheelModel` + `LivingIconRegistry.onModifyBakingResult`）。

#### ⚠️ 模型本来就有，而且**有厚度**（2026-09-17 修正）

原版工具（镐/斧/铲/锄）走 `minecraft:item/handheld` → `item/generated`。
~~它是零厚度纸片~~ —— **错**。原版 `item/generated` 会做 **extrusion（挤出）**：
把 sprite 沿法线方向挤成一个**薄板**（有正面 + 背面 + 四个侧面）。

证据：Modrinth 资源包 *Flatter Items: 2D Held Sprites*（支持 1.20.1–1.21.11）的描述即
*"flattens the models of held items, **removing the vanilla extrusion**"* —— 反证原版确有挤出厚度。
（旁证：原版掉落物、手持工具从侧面看都能看到一条有厚度的边。）

**但它仍然不是"真 3D 镐子"**：挤出的是**整张 sprite 的轮廓**，
镐柄与镐头之间没有立体结构关系——本质是一块**带厚度的薄板**，不是由 box 拼出的立体模型。

#### 因此分叉点被大幅放宽 —— 建模的必要性比我上一轮说的小得多

| 待机动作 | flat（带挤出）能否胜任 |
|---|---|
| 上下浮动（bob） | ✅ |
| 左右摇晃（±15° 摆动） | ✅ 有体积感，且**很可爱** |
| 公转（绕玩家转圈，**始终面向玩家** = billboard） | ✅ |
| 呼吸 / 缩放 / 挤压拉伸 | ✅ |
| **绕 Y 轴 360° 自转** | ⚠️ **可行**——有厚度，侧面不会消失，只会变薄（像一张薄卡片） |
| **从上方 / 侧面看** | ⚠️ 能看到，但薄 |

→ **方案 0（零建模）的适用范围比我上一轮判断的大**：连 360° 自转都能做，只是侧面薄。
   建议先用这条跑通，再决定是否值得为真 3D 建模。
   ⚠️ 挤出厚度的具体量级未实测，建议进游戏扔一把镐子绕看一圈确认观感。

| 方案 | 做法 | 依赖 | 能做骨骼动画 | 评价 |
|---|---|---|---|---|
| **0. Billboard flat（零建模）** | 直接用现有物品模型；渲染时抵消相机 yaw 使其始终面向玩家 + 浮动 + 小幅摇摆 | 无 | ❌ | ⭐ **建议先试这个**，成本最低 |
| **A. 3D 物品模型 JSON** | Blockbench 建模 → 导出 Java Item Model（带 `elements`）→ `models/item/<tool>_floating.json`；`ModelEvent.RegisterAdditional` 注册 standalone | 无 | ❌ | 需要 360° 自转 / 多角度观看时的正解。标准、零依赖、性能好、可视化编辑 |
| **B. 程序化 BakedModel** | Java 手写 `BakedQuad` 顶点 | 无 | △（可运行时改几何） | 适合**补充层**（如呼吸/挤压），不适合主形状——手写坐标太痛苦 |
| **C. OBJ / 外部格式** | Blockbench 导出 `.obj` + 贴图，NeoForge `OBJLoader` 加载 | 无 | ❌ | 不推荐：A 能做的它都能做，但接线更麻烦、性能更差 |
| **D. GeckoLib** | 加依赖 + Blockbench 骨骼动画 + 动画状态机 | **新增重依赖** | ✅ | 最强，但**大概率不必要**——见下方「PoseStack 能做什么」 |
| **E. Display Entity** | 生成 `item_display` / `block_display` 实体承载 | 无 | ❌ | ❌ **不解决 3D 问题**（物品模型仍是 flat），且引入实体开销 |
| **F. 伪 3D（交叉面）** | 像花草那样两个交叉 quad | 无 | ❌ | 仅够临时验证，转圈会露馅 |

#### ⭐ 关键洞察：PoseStack 能做的比想象中多（很可能不需要 GeckoLib）

用户要的**待机动作与功能动作几乎全是刚体变换**：

| 动作 | PoseStack 实现 | 需要骨骼？ |
|---|---|---|
| 转圈（自转 / 公转） | `mulPose(Axis.YN.rotationDegrees(yaw))` | ❌ |
| 上下浮动（bob） | `translate(0, sin(t)*amp, 0)` | ❌ |
| 左右摇晃 | `mulPose(Axis.ZP.rotationDegrees(sin(t)*ang))` | ❌ |
| 呼吸 / 缩放 | `scale(s, s, s)` | ❌ |
| 挤压拉伸（squash & stretch） | 非均匀 `scale(sx, sy, sz)` | ❌ |
| 挖掘挥动 | 绕手柄末端旋转 + 回弹缓动 | ❌ |
| **部件相对运动**（镐头甩动） | **分组模型**：头/柄拆成两个模型，分别 render + 各自变换 | ⚠️ 可近似 |
| 真正的形变（弯曲、软体） | — | ✅ 需要骨骼 |

→ **结论：先走方案 A + PoseStack**。只有做到某个具体动作发现「PoseStack 表达不了」时，
再回头评估 GeckoLib。分组模型（头/柄分离）是介于两者之间的实用中间档。

#### 方案 A 的两个关键实现细节

1. **不要替换物品本身的模型** —— 否则物品栏图标会变成 3D 镐子，破坏现有图标/装饰器体系。
   正确做法：3D 模型用**独立的 standalone 模型路径**，世界渲染时
   `mc.getModelManager().getModel(ModelResourceLocation.standalone(loc))` 取 BakedModel，
   再 `mc.getItemRenderer().render(stack, GROUND, false, pose, buffer, light, overlay, model)`
   —— **`model` 参数可以传自定义模型**。物品栏仍走原 flat 图标 + 活物品角标装饰器。
2. **`block/block` 父级会带方块 display 变换** —— 3D 物品模型建议写 `elements` +
   **显式 `display` 段**（自定义第一/第三人称、GUI、GROUND 的变换），不要直接 `parent: minecraft:block/block`。

### K.11 参考：御剑模组 `YujianCraft`（2026-09-18 研读）

> 📁 `libs/src/YujianCraft-main/`（⚠️ 它是 **Forge**，包名 `net.minecraftforge.*`；
> 我们是 NeoForge `net.neoforged.*`，抄的时候要换包名，API 名基本一致）

**① 架构：两层，主体用实体，特效用事件**

| 层 | 实现 | 文件 |
|---|---|---|
| **主体**（飞行剑、剑阵） | **实体 + `EntityRenderer`**，经 `EntityRenderersEvent.RegisterRenderers` 注册 | `ClientModEvents.java:214-216` |
| **特效**（冲击、残影、后处理） | `RenderLevelStageEvent` | `ClientImpactEffects:94`（`AFTER_PARTICLES`）<br>`ClientSwordArrayPostEffect:40`（`AFTER_LEVEL`） |

→ 即：**需要跟随实体移动/持久存在的 → 实体；纯视觉叠加的 → 渲染事件。**

**② `FormationGeometry` —— 阵型排布，可直接借鉴**（`formation/FormationGeometry.java`）

```java
// 环形：6 个槽位绕玩家，垂直用 sin 形成倾斜环
double angle = Math.PI * 2 * slot / FORMATION_SIZE + RING_ANGLE_OFFSET;
return ownerPosition
        .add(0, RING_CENTER_HEIGHT + Math.sin(angle) * RING_VERTICAL_RADIUS, 0)
        .add(basis.forward.scale(-RING_BACK_OFFSET))
        .add(basis.right.scale(Math.cos(angle) * RING_HORIZONTAL_RADIUS));

// 扇形：横排，两端略高形成弧线
double horizontal = (slot - 2.5) * 0.56;
double vertical   = 1.08 + Math.abs(slot - 2.5) * 0.18;
```

- 用玩家 yaw 构造 `Basis(forward, right)`：`Vec3.directionFromRotation(0, yaw)`，right = `(-forward.z, 0, forward.x)`
- 朝向：环形 → 统一朝玩家正前方；扇形 → **径向朝外**（从肩部中心指向停靠位）
- 还有**返航集结点**放在玩家后上方（注释：`prevents returning swords from cutting through the owner`）—— 避免穿模，细节值得学

⭐ **对我们要的「可爱待机动作」直接可用**：环形的 `angle` 加上 `tickCount` 就是**绕玩家转圈**，
配合 `sin` 的垂直分量就是**上下起伏** —— 正是用户描述的"转圈 + 摇晃"。

**③ 剑阵本体是「纯自定义顶点」，不是物品模型**

`SwordArrayFieldRenderer`（62KB）里**没有任何** `ItemStack` / `BakedModel` / `ItemRenderer` 调用 ——
它的"炫酷"来自手写顶点的能量场/光效（`SwordArrayQiRenderer` 也是手写 `VertexConsumer`）。

→ 若我们只要"显示工具本体"，用 `ItemRenderer.renderStatic` 更省事；
→ 若想要"光效/拖尾/气刃"这类表现，才需要学它的自定义顶点。

**④ 由此产生的新选项：悬浮渲染用不用实体？**（→ `K30`）

| | 事件方案（原计划） | 实体方案（御剑做法） |
|---|---|---|
| 位置插值 / 视锥裁剪 / 距离剔除 | 全部自己写 | **原版提供** |
| 客户端同步 | 需自写 S2C 包（容器形态尤其麻烦，`K2`） | **原版提供** |
| 生命周期管理 | 无 | 需处理区块卸载 / `/killall` / 维度切换 |
| 开销 | 轻 | 每个悬浮物一个实体 |

⚠️ 对**容器形态**尤其重要：实体方案能省掉 `K2` 那套自写的按距离定向 S2C 同步。

### ✅ `K30` 定案：**不用实体**，纯客户端渲染（2026-09-18 用户拍板）

📌 **用户口径**：

> "一个活工具就需要维护一个实体，代价有点高。实际上活工具与世界的交互逻辑**就一根射线**，
> 渲染物品模型也**只是在视觉上有个照应**。这个模型甚至可以**只在客户端渲染**。
> 比如一个活斧子挖木头，**服务端把这个行为报告给客户端，客户端就播放这个斧头模型挖木头的动画**。
> 我认为模型渲染方面的实现是由简单优雅的实现方式的。"

#### 核心架构：服务端只管逻辑，客户端只管表现

```
【服务端 · 权威逻辑】
    记忆（DataComponent）→ clip 射线 → 推进进度 → gameMode.destroyBlock
    └─ 顺带写一个 network-only 组件 LIVING_TOOL_ACTION
       { 动作类型, targetPos, startTick }    ← "把行为报告给客户端"

【客户端 · 纯表现】
    读记忆（已 networkSynchronized）→ 本地 clip 算射线（与服务端同结果）
    读 LIVING_TOOL_ACTION → 播放对应动画（挥动 / 去皮 / 待机）
    渲染：RenderLevelStageEvent + ItemRenderer.renderStatic
```

#### 为什么这个方案特别优雅 —— 四个已有条件全部就绪

| 条件 | 说明 |
|---|---|
| ① 记忆已在客户端 | DataComponent 标 `networkSynchronized` 即可，`L-a` 就要做 |
| ② **客户端能自己算射线** | `L19` 已确认：射线检测客户端也能做，服务端**无需同步命中结果** |
| ③ **破坏裂纹由原版同步** | `level.destroyBlockProgress()` 广播的裂纹客户端可见 → 天然的"挖掘中"视觉反馈 |
| ④ 动作状态有现成套路 | network-only 组件，照抄 `LIVING_FURNACE_BURNING`（`LivingItemManager:94-99`） |

→ **两端各自算同一条射线**（记忆相同 + 世界数据相同 → 结果一致），
   服务端只多同步一个"当前在做什么动作"的状态位。

#### 收益

- ❌ 无实体 → 无生命周期管理、无区块卸载/`killall` 顾虑、无实体开销
- ❌ 不需要为动画新写 S2C 包（走 DataComponent）
- ✅ 服务端/客户端彻底解耦，渲染怎么写都不影响玩法逻辑
- ✅ 容器形态仍需 `K2` 的 S2C 包（告诉客户端"哪个容器有活工具"），但**只同步位置清单，不同步动画**

| K30 | P0 | 悬浮渲染载体 | a) 实体 + `EntityRenderer`<br>b) **`RenderLevelStageEvent` 纯客户端渲染** | ✅ **b**（2026-09-18 用户拍板）<br>理由：一个活工具一个实体代价过高；渲染只是视觉照应，不该引入实体生命周期 | ✅ b |
| K31 | P1 | 动画驱动源 | a) network-only 组件<br>b) 客户端自行推断<br>c) 复用原版破坏裂纹档位 | ✅ **实装口径（2026-09-20）**<br>挖掘：「忙/闲」= `LIVING_TOOL_PROGRESS`（**现成** —— 本就 networkSynchronized 且含目标格子）+ `LIVING_TOOL_DIG_TICKS`（新增，转圈速度）<br>交互：`LIVING_TOOL_LAST_ACTION`（新增，tick + 目标格子）<br>⚠️ **最终没采用 c**：裂纹只能推断"在挖"，<b>推不出转速</b>（挖多快转多快），<br>更推不出"刚交互了"（交互是瞬时的，没有持续状态可查） | ✅ 实装 |

### ⭐ 悬浮位置（2026-09-18 用户提出，重要改动）

📌 **用户口径**：*"我觉得可以悬浮在射线目标点"*

**原始需求**（`§0`）是"悬浮在**宿主周围**"（玩家身边 / 容器周围 / 掉落物上方）。
现改为**两段式**：

| 状态 | 悬浮位置 | 说明 |
|---|---|---|
| **WORKING**（有目标） | **射线目标点旁** | 工具"飞过去干活"—— 视觉上直接告诉玩家"它在挖这里" |
| **IDLE**（无目标 / MISS） | **宿主周围** | 回到宿主旁待机（转圈 + 起伏） |

**好处**

1. **更直观** —— 不用看线就知道它在挖哪（甚至可能省掉 `L19` 那条线）
2. **更"活"** —— 工具真的跑过去干活了，正是想要的"精灵感"
3. **御剑模组有完整先例** —— 它的剑就是"飞出去 → 攻击 → 返航"，
   且 `FormationGeometry` 里专门有 `returnRallyPoint()`（返航集结点放在玩家后上方，
   注释 `prevents returning swords from cutting through the owner`）避免穿模

**需要补充的规则**（→ `K32`-`K34`）

| 编号 | 问题 | 倾向 |
|---|---|---|
| `K32` | IDLE ↔ WORKING 切换时怎么移动？ | **插值飞行**（缓动），而不是瞬移 —— 这才是"活"的关键 |
| `K33` | 多把活工具挖**同一个**目标点 → 模型挤在一点？ | 围绕**目标点**做环形排布（御剑 `FormationGeometry` 环形） |
| `K34` | 目标消失（挖完/方块没了）→ 飞回还是原地等？ | 飞回 IDLE 位（若有新目标再飞出去） |
| `K35` | 背包形态：目标点在玩家前方几格，还算"身边"吗？ | 算；玩家移动时目标点跟着移动，观感是"工具在前面引路" |

⚠️ **注意**：这与原始需求"悬浮在玩家身边"**不冲突** ——
无记忆 / 无目标时仍在宿主身边；有活干时才飞过去。

### K.12 最小验证路径（建议先做这个）

```
1. 掉落物形态 + flat 模型 + **billboard（面向玩家）** + 浮动 / ±15° 摇晃
   → 零同步成本（IS_LIVING 已可见）、零服务端改动、**零建模**、纯客户端新增
   → 一次验证：RenderLevelStageEvent 接入、PoseStack 变换、时间源动画、光照、裁剪
   ⚠️ flat 模型做 360° 自转**可行**（原版有 extrusion 厚度，侧面不会消失），只是侧面偏薄
2. 若第 1 步「纸片感」太重 → 升级真 3D 模型（只做活木镐子一种），解锁 360° 自转
3. 容器形态（新增 S2C 包）
4. 玩家身边形态 + 动作动画（LIVING_ACTION 组件）
```

---

## 3.12 L. 记忆与回放（核心玩法，2026-09-18 新增）

**模型**：`录制（手持时，事件驱动）` → `存储（DataComponent）` → `回放（以宿主位置为原点）`

### 三宿主行为矩阵（已定）

| 宿主 | **无记忆** | **有记忆** |
|---|---|---|
| 玩家背包 | **辅助玩家挖掘**（需 `B1` mixin） | **回放记忆** |
| 容器 | 暂不做（未来同其它活物品影响周围槽位） | 以**容器 pos** 为原点回放 |
| 掉落物 | — | 以**掉落物 pos** 为原点回放（与容器同） |

> 用户明确：**掉落物也工作** → `K1` 结论 = **要 tick**，需 `K.2` 的服务端掉落物 tick 扩展。

### ⭐ 重大利好：整个「记忆 + 回放」主线**不需要任何 mixin**

| 环节 | 接入点 | 需要 mixin？ |
|---|---|---|
| 录制**挖掘记忆** | `BlockEvent.BreakEvent`（自带 `getPos()` / `getPlayer()` / `getState()`） | ❌ |
| 录制**交互记忆** | **`BlockEvent.BlockToolModificationEvent`** | ❌ |
| **回放** | 自算目标 pos，直接执行 | ❌ |

→ **`B1` 的 `ServerPlayerGameMode` mixin 只服务于「背包无记忆时辅助玩家挖掘」这一条支线**，
   可以放到最后做。这是本轮最大的路线简化。

### 射线（raycast）技术说明（2026-09-18 定稿 `L1=c` 后补充）

**录制侧**（玩家手持活工具时，事件已给出 `pos`）：

```java
Vec3 eye    = player.getEyePosition();              // 射线起点（眼高 ≈1.62）
Vec3 target = Vec3.atCenterOf(pos);                 // 目标点 = 被挖/被交互方块中心
Vec3 dir    = target.subtract(eye).normalize();     // ← 存：方向向量
double dist = eye.distanceTo(target);               // ← 存：距离（+余量，见 L15）
```

**回放侧**（容器 / 玩家 / 掉落物通用）：

```java
Vec3 origin = <宿主射线起点>;                        // ← L14 待定
Vec3 end    = origin.add(dir.scale(dist));
BlockHitResult hit = level.clip(new ClipContext(
        origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, null));
if (hit.getType() != HitResult.Type.BLOCK) return;   // ← L7「没方块就停」
// 命中 → 操作 hit.getBlockPos()
```

**关键 API（1.21.1）**

| API | 说明 |
|---|---|
| `Player#getEyePosition()` | 射线起点（眼睛位置） |
| `Entity#getViewVector(float)` | 单位视线向量（与 `getLookAngle()` 同类） |
| `Player#pick(double reach, float partialTick, boolean hitFluids)` | 一键射线，返回 `BlockHitResult` |
| `ClipContext(Vec3 from, Vec3 to, Block, Fluid, Entity)` | 射线上下文 |
| `Level#clip(ClipContext)` | 执行射线 |
| `BlockHitResult#getBlockPos()` / `getLocation()` / `getDirection()` / `getType()` | 结果读取 |
| `ClipContext.Block.OUTLINE` + `ClipContext.Fluid.NONE` | 推荐参数（只打方块外形、忽略流体） |
| `Attributes.BLOCK_INTERACTION_RANGE` | 玩家交互距离（默认 **4.5**，创造更高）→ 可作为记忆距离的**上限钳制** |

> 录制时事件已给 `pos`，反推方向/距离即可，不必再调一次 `player.pick()`（更省且无 partialTick 问题）。

### 射线命中位置 & `L15` 余量（2026-09-18 定稿）

**玩家指针选中方块时，射线命中在哪？** → **方块的表面上**（不是中心）。

```
BlockHitResult.getBlockPos()    → 方块的整数坐标
BlockHitResult.getLocation()    → 射线与方块碰撞箱【表面】的交点（精确 Vec3）
BlockHitResult.getDirection()   → 命中的那个面（NORTH / SOUTH / UP ...）
```

⚠️ `getLocation()` **不是**方块中心 —— 它可能在离中心 **0.5 格**的表面上。

**`L15` 决策：录制「纯净射线」—— 到表面就到表面，不做任何加工。**

| 录制基准 | 回放终点 | 评价 |
|---|---|---|
| **到表面 `hit.getLocation()`** ← ✅ **采用** | 方块表面 | **与原版射线逐位一致** |
| 到方块中心 `Vec3.atCenterOf(pos)` | 方块内部 | ❌ 不采用：与玩家实际看到的那条线不一致 |

📌 **用户理由**：*"游戏内 F3 可以查看实体碰撞箱和射线，玩家录制时发现记忆里的射线和实际的
射线不一致会很苦恼的。"*
→ **忠实于原版 > 代码上更好算**。可视化（`L19`）画的必须是**玩家看到的那条线**。

⭐ **实现建议：直接存完整偏移向量，而不是「方向 + 距离」**

```java
// 录制：存这一个 Vec3 就够了
Vec3 offset = hit.getLocation().subtract(player.getEyePosition());

// 回放：
Vec3 end = origin.add(offset);     // ← 与原版射线逐位等价
BlockHitResult h = level.clip(new ClipContext(origin, end, OUTLINE, NONE, null));
```

数学上 `offset == dir * dist`，但省掉「归一化 → 存储 → 乘回来」两步，**无精度漂移**，
且保证**可视化与服务端判定用的是同一条线**。

**余量：不加**（`L15=a`）。擦边导致的 MISS 由可视化暴露，玩家重录即可。

→ 极端擦边导致的 MISS，由 **射线可视化（`L19`）** 暴露给玩家，**玩家自己重录**即可。
→ ⭐ **提炼为本项目设计原则：优先给玩家「信息与手段」，而不是在代码里兜底。**

### `L19` 详解 —— 射线可视化（用户提出，用于替代 `L15` 兜底）

**为什么可行**：射线检测在**客户端也能做**（客户端有完整方块数据）。
只需把「记忆 = 方向 + 距离」同步到客户端，客户端自己 `level.clip(...)` 算命中点，
**服务端不需要同步命中结果**。

```
记忆 DataComponent（须 networkSynchronized）
   ↓
客户端取宿主位置（容器 ← `K2` 的 S2C 包；背包 / 掉落物 ← 客户端已知）
   ↓
本地 clip 算命中点 → 渲染
```

**渲染方式**（复用 `K` 组正在建的 `RenderLevelStageEvent` 管线）：

| 方式 | 做法 | 评价 |
|---|---|---|
| a) 画线 | 宿主 → 命中点，`RenderType.LINES` | 简洁 |
| b) **高亮目标方块** | 在命中方块上画描边（类似原版选中框） | **最直观**：一眼看到"它要挖这块" |
| c) 两者都做 | 线 + 框 | 信息最全 |

**显示时机**（`L20`）：建议 **b（玩家看着宿主时才显示）**，避免满屏都是线。

### `L19` 详解补充 —— 先分清两个层次（2026-09-18）

**① 正在挖的时候：不用额外画，复用原版破坏裂纹**

`ServerLevel#destroyBlockProgress(int breakerId, BlockPos pos, int progress)` 会广播
`ClientboundBlockDestructionPacket`，客户端**自动显示裂纹**（0-9 档）。

Create 正是这么做的（每 tick `level.destroyBlockProgress(player.getId(), pos, (int)(progress * 10))`）。

→ 我们推进进度时顺手调它，**裂纹天然可见，零额外渲染代码**。
→ 这也是 `L27`（自己推进进度）的附带收益：能精确控制裂纹档位。

**② 还没开始挖 / 待机时：才需要"目标指示"** ← 这才是 `L19` 的真正范围

即"让还没动手的玩家知道这把活工具打算挖哪"。

**③ `L20` 详解 —— 显示时机的五种选择**

| 方案 | 行为 | 特点 |
|---|---|---|
| a) 一直显示 | 渲染半径内常驻 | 信息量最大，但箱子多了会满屏线 |
| b) 看着宿主时 | 准星指向容器/掉落物才显示 | 不刷屏；符合"我想知道它在干嘛"的时机 |
| c) 潜行时 | 按住 Shift 才显示 | 与「蹲下记类型」的操作语义呼应 |
| d) 手持同类工具时 | 手持斧子才显示活斧子的目标 | 最专注，但看不到其它工具 |
| e) 极淡常驻 | 半透明细线，靠近才变清晰 | 兼顾，实现稍复杂 |

> 可组合，例如 **b + c**：看着宿主时显示，潜行时额外显示全部。

### ✅ `L20` 定案：挂在 F3+B（显示碰撞箱）开关上（2026-09-18 用户定）

**源码依据**（`KeyboardHandler.java:143-146`）：

```java
case 66:   // F3 + B
    boolean flag = !this.minecraft.getEntityRenderDispatcher().shouldRenderHitBoxes();
    this.minecraft.getEntityRenderDispatcher().setRenderHitBoxes(flag);
    this.debugFeedbackTranslated(flag ? "debug.show_hitboxes.on" : "debug.show_hitboxes.off");
```

**我们的渲染守卫**：

```java
if (!mc.getEntityRenderDispatcher().shouldRenderHitBoxes()) return;
```

- ✅ 纯客户端状态，**无需任何 S2C 同步**
- ✅ 复用玩家熟悉的原版开关，零新增按键
- ✅ 与「F3 能看到真实射线」的 `L15` 定案**一脉相承** —— 都是"给玩家调试手段，而非代码兜底"

⚠️ **已知取舍**：F3+B 会同时显示**所有实体的碰撞箱**（满屏绿框）。
玩家为了看活工具的目标线，得一并忍受碰撞箱。
→ 若日后觉得碍事，可再叠加「看着宿主时也显示」（`L20` 方案 b），两者取或。
→ 已记为 `L29` 备用。

### `L19` 实现提示（画线）

- 用 `MultiBufferSource.getBuffer(RenderType.lines())`（或 `linesNoDepth()` 做穿透显示）
- 顶点需带法线（`normal`），否则线可能不可见
- 或复用原版 `LevelRenderer#renderLineBox`（画框，非画线）—— 若日后想升级到"线+框"可直接用
- 颜色建议与活物品图标体系一致，见 `docs/system-design/icon-system.md`

| L29 | P2 | 日后若嫌 F3+B 碍事，是否叠加「看着宿主时也显示」 | a) 不加 b) 叠加 | 暂缓，等实装后看观感 | *暂缓* |

### `L16` 详解 —— "每 tick 操作几次"是什么意思

回放逻辑挂在服务端**每 game tick**（1/20 秒）执行：

| 每 tick 挖 | 相当于 |
|---|---|
| 1 个 | **每秒 20 个方块** |
| 每 20 tick 挖 1 个 | 每秒 1 个 |

若目标位置一直有方块（例如旁边有**刷石机**不断造石头），不节流就是**每秒 20 个**。

⚠️ **它与 `L6` 强耦合**：

- 若 `L6=a`（回放直接 `destroyBlock`，瞬间完成）→ **必须靠 `L16` 节流**
- 若 `L6=b`（模拟玩家真实挖掘，有破坏进度）→ **天然限速**（挖一格要几十 tick），`L16` 可省

→ 所以 **`L6` 先定，`L16` 才有意义**。`L6` 目前仍待定。

**跨维度：不是问题（我上一轮多虑了）**

记忆存的是**相对方向 + 相对距离**（射线向量），不是绝对坐标 ——
在任何维度回放都是「沿这个方向 N 格」，语义本来就与维度无关。只有存**绝对坐标**或
**绝对朝向**时维度才有意义，选了 `L1=c` 后该问题自动消失。

唯一残留点：若**蹲下记了方块类型**（`L4`），而该类型只在特定维度存在（如下界石英矿），
则在其他维度永远匹配不上 → **自然停止**，属自然行为，无需特殊处理。

### `BlockToolModificationEvent` 详解（NeoForge 1.21.1 已确认存在）

```
字段/方法：getPlayer()  getHeldItemStack()  getItemAbility()
          getPos()  getState()  getFinalState()  getContext()  isSimulated()
```

- 覆盖 `AXE_STRIP` / `HOE_TILL` / `SHOVEL_FLATTEN` / `AXE_SCRAPE` / `AXE_WAX_OFF`
  → **四件套一次实现全部支持**，且模组自定义 `ItemAbility` 也走此事件（自动兼容）。
- ⚠️ **必须过滤 `isSimulated()`** —— 原版会先跑一趟 simulate，不过滤会**重复录制**。
- 录制条件：`getHeldItemStack()` 是活工具 且 `getPlayer()` 非 null 且 `!isSimulated()`。

### 待决问题

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| L1 | **P0** | **方向怎么表示？** | a) `Direction` 枚举（6 面）+ 距离<br>b) yaw + pitch + distance<br>c) **射线向量 (dx,dy,dz) + 距离**<br>d) 量化整数偏移 (0,0,2) | ✅ **c（射线向量）**（2026-09-18 用户定）<br>📌 **2026-09-18 追加：录制「纯净射线」** —— 基准用 `hit.getLocation()`（**表面命中点**），<br>**不要**改用 `Vec3.atCenterOf(pos)`。理由见 `L15` 详解 | ✅ c（表面基准） |
| L2 | P0 | 距离怎么量化？ | a) 保留浮点 b) 量化到 1 格 c) 量化到 0.5 格 | ✅ **a（保留浮点）**（配合 L1=c）<br>✅ **不加余量**（`L15=a`）<br>⭐ **建议改存完整偏移向量** `Vec3 offset = location - eye`（等价于 dir×dist，但**无归一化/还原的精度损失**，与原版射线**逐位一致**）—— 见 `L15` 详解 | ✅ a |
| L3 | **P0** | **原点**（射线起点）是眼睛还是脚下方块？ | a) 眼睛（对齐"视角射线"） b) 脚下方块（对齐格子） | ✅ **a（眼睛 / 射线起点）**（2026-09-18 用户定）<br>⚠️ 但**容器与掉落物没有眼睛** → 必须为它们各自定义射线起点，见 `L14` | ✅ a |
| L4 | P1 | 蹲下记的类型：记 `Block` 还是 `BlockState`？记**交互前**还是**交互后**？ | a) **`Block` + 交互前** b) `BlockState` + 交互前 | ✅ **a（`Block` + 交互前）**（2026-09-18 用户采纳倾向）<br>① 符合直觉（记的是"橡木原木"不是"朝上的橡木原木"）<br>② **去皮/耕地后 Block 改变 → 匹配不上 → 天然停止**（正好实现 `L8`）<br>⚠️ 代价：作物 `age` 这类关键状态区分不了，v1 接受 | ✅ a |
| L5 | P0 | 记忆存哪？**堆叠时共享吗？** | a) 新 DataComponent `LivingToolMemory`，**整堆叠共享**<br>b) 每把独立 | ✅ **a（整堆叠共享）**（2026-09-18 用户定）<br>📌 **口径**：*"活工具一般是无法堆叠的，没有堆叠的功能逻辑语义。以原版为基准。其它模组能堆叠，我们保证没 bug 就好。"*<br>→ 原版工具 `maxStackSize=1`，实际不会堆叠；模组强行堆叠时**共享同一份记忆**、不崩即可，**不做**"每把独立"或"按堆叠数倍增" | ✅ a |
| L6 | **P0** | **回放怎么"挖"？** | a) `level.destroyBlock(pos, true)`<br>b) 手写模拟<br>c) **`FakePlayer` + 它的 `gameMode`** | ✅ **c（FakePlayer）**（2026-09-18 定，参考 Create `Deployer`，详见 `L6 详解`）<br>📌 **必须保留完整原版挖掘语义**（用户明确）：<br>① **有破坏进度** ② **效率附魔生效** ③ **材质门槛生效**（活铁镐挖黑曜石**不掉东西**）<br>④ 必须触发 `BlockEvent.BreakEvent`（保护插件可取消）<br>→ 走 `fakePlayer.gameMode.destroyBlock(pos)` 则 ①②③④ **全自动** | ✅ c |
| L7 | **P0** | **回放频率与终止**：挖完目标后怎么办？ | a) 只挖一次就停<br>b) **持续：有方块就挖，没方块就停**<br>c) 有冷却（N tick 一次） | ✅ **b（有方块就挖，没方块就停）**（2026-09-18 用户定）<br>→ 语义 = 沿记忆射线做射线检测，**命中方块就操作，MISS 就停**<br>⚠️ 平衡性：这等于永动挖矿机，但用户明确接受。仍建议加**每 tick 操作上限**与 `Config` 开关，见 `L16` | ✅ b |
| L8 | P0 | **交互记忆**的终止条件 | a) 沿用 `L7` b) 失败 N 次停止 | ✅ **a**（2026-09-18 用户采纳倾向）—— 与挖掘记忆同构：<br>命中方块 **且** 匹配记忆类型（若蹲下记过）→ 交互；否则停 | ✅ a |
| L9 | P1 | 背包里**有记忆**时：只回放，还是**也辅助玩家挖掘**？ | a) 只回放 b) 回放 + 辅助 c) **智能切换**（玩家在挖→辅助，闲着→回放） | ✅ **v1 做 a（只回放）**（2026-09-18 用户采纳倾向）<br>🔮 未来升级 **c** 更符合直觉（录了"朝南挖"后自己去挖西边时，镐子不该自顾自挖南边），<br>但 c 需要判断"玩家是否在挖" → **依赖 `B1` 挖掘进度追踪（轨道四）**，故延后 | ✅ a（v1） |
| L10 | P1 | **掉落物会移动**（水流冲 / 漏斗吸）→ 回放目标一直在变 | a) **跟随**（每 tick 重算）<br>b) 静止时才工作 | ✅ **a（跟随）**（2026-09-18 用户采纳倾向）<br>`L7`（没方块就停）天然处理"被冲到空处"；掉落物被捡起 → 消失 → 自然停止<br>副作用（可视为涌现玩法）：水里漂的活镐子会沿路乱挖 | ✅ a |
| L11 | P1 | 权限 / 保护插件兼容 | 掉落物归属？被 `BreakEvent` 取消怎么办？ | ✅ **已定**（2026-09-18 用户采纳倾向）：<br>① 掉落物**掉在方块位置**（原版行为，玩家可用活漏斗收集）—— 不直接进容器<br>② 被保护插件取消时 → **保留记忆 + 重置进度，等待**（不清记忆） | ✅ |
| L12 | P1 | 回放消耗耐久吗？扣哪把？ | 与 `F` 组联动 | 待 `F2` | *待定* |
| L13 | **P0** | **如何清除记忆？** | — | ✅ **已定**（2026-09-18 用户定）：<br>① **左键空气** → 清除**挖掘记忆**<br>② **右键空气** → 清除**交互记忆**<br>③ **取消活化** → `IS_LIVING` 与全部功能组件被清（现有 `clearLivingData` 已覆盖）<br>⚠️ 待定细节见 `L17` | ✅ 见 L17 |
| L14 | **P0** | **容器 / 掉落物的射线起点在哪？**（它们没有眼睛） | a) 容器方块中心 `pos+0.5`<br>b) 容器顶部 `pos+(0.5,1,0.5)`<br>c) 对齐玩家眼高<br>d) 掉落物：**碰撞箱中心** | ✅ **a（容器方块中心）** / **d（掉落物碰撞箱中心）**<br>📌 **用户口径**：*"录制的结果是一条有长度有方向的射线，原点在玩家哪个部位不重要，重要的是这条线本身"*<br>→ 记忆 = **一条线段**（方向 + 长度），回放 = **把这条线平移到宿主身上**<br>🔁 **d 于 2026-09-19 修订**：初版用 `Entity#position()`，但那是碰撞箱**底部**、<br>正好贴着脚下方块的上表面 ⇒ 逐格扫描 `t=0` 就命中**所站的那块方块**，<br>表现为"射线只指向底面"。改取**碰撞箱中心**（`getBoundingBox().getCenter()`） | ✅ a/d |
| L15 | P1 | 射线**命中余量** | a) 不加余量<br>b) `dist += 0.5` 保险 | ✅ **a（不加余量）**（2026-09-18 用户定）<br>📌 **口径**：*"射线要是落在缝隙里，玩家重新录一个记忆就好了，没必要兜那么多底"*<br>→ 改用 **射线可视化**（`L19`）让玩家自己看见，而非代码兜底 | ✅ a |
| L16 | P1 | **回放速度**：每 tick 能挖几个？ | a) 不限制<br>b) 每 N tick 一次<br>c) 天然限速 | ✅ **c（不加节流）**（2026-09-18 用户采纳倾向）<br>`L6=c`（FakePlayer 有破坏进度）天然限速（几十 tick 挖一格）。<br>刷石机下的持续产出是 `L7=b` 的既定玩法，已接受。<br>→ 真有性能问题再加 `Config` 开关（`I1`） | ✅ c |
| L17 | P1 | 「左/右键空气」的判定细节 | — | ✅ **已定**（2026-09-18）：**玩家手持活工具** + **射线未命中实体**<br>→ 待确认：是否应为 `hit.getType()==MISS`（既无方块也无实体）？见 `L18` | ✅ 见 L18 |
| L18 | P1 | `L17` 的精确判定 | a) `hit.getType()==MISS`（方块和实体都没命中）<br>b) 只要没命中**实体**（命中方块也算"空气"） | ✅ **a（`MISS`）**（2026-09-18 用户定）<br>理由：左键命中方块时是正常挖掘（会录新记忆），不该同时清记忆 | ✅ a |
| L19 | **P1** | **待机时的目标指示**：让玩家看见活工具打算挖哪 | a) **一条线**<br>b) 高亮目标方块<br>c) 线 + 框<br>d) 工具虚影 / 光晕 | ✅ **a（简单一条线）**（2026-09-18 用户定）<br>📌 **前提澄清**：挖的过程中**不必画** —— 复用原版破坏裂纹（`destroyBlockProgress`）即可。<br>本条只管「待机 / 未开始挖」时，画**宿主 → 命中点**一条线 | ✅ a<br>✅ **已实现**（2026-09-19）：`client/render/LivingToolRayRenderer`；<br>颜色区分挖掘（橙红）/ 交互（青蓝），**落空时变半透明**（"擦缝过去了"的信号）<br>📌 **实现修正**：原定的"一条线"改成了**四边形光带** —— `RenderType.lines()` 的线宽在 OpenGL core profile 下不受驱动保证（实测偏细），光带宽度自控、跨驱动一致，半宽随距离增长 ⇒ 屏幕粗细恒定<br>⚠️ v1 只覆盖**玩家（背包/手持）+ 掉落物**；**方块容器待 `K2` 的 S2C 包** |
| L20 | P1 | 可视化的**显示时机** | a) 一直显示<br>b) 看着宿主<br>c) 潜行<br>d) 手持同类工具<br>e) 极淡常驻<br>**f) 按 F3+B 时** | ✅ **f**（2026-09-18）<br>读取：`mc.getEntityRenderDispatcher().shouldRenderHitBoxes()`<br>纯客户端状态，无需同步 | ✅ f<br>✅ **已实现**（2026-09-19）<br>🔁 **2026-09-19 用户修订**：**手持的活工具 → 无视 F3+B，始终显示**；<br>其余宿主仍只在 F3+B 时显示。<br>📌 理由：手持正是"玩家正在操作、最需要确认记忆方向"的时刻，不该被开关挡住 |
| L21 | **P0** | **破坏进度存在哪？**（`L6=b` 必须维护进度状态） | a) **活工具的 DataComponent**（跟随物品跨容器迁移）<br>b) 容器级缓存<br>c) 纯内存 Map | ✅ **a（存 DataComponent）**（2026-09-18 用户拍板）<br>📌 用户口径：*"进度只在挖掘时才有意义，存 NBT 里也行，毕竟有个掉落物形态"*<br>→ 存 `{startTick, targetPos}`（原版重算式，写入量小）<br>⚠️ **必须连 `targetPos` 一起存**：宿主迁移（容器→背包→掉落物）后位置变了，<br>比对 `targetPos` 与记忆射线当前命中点，**不一致则重置进度** | ✅ a |
| L27 | P0 | ⚠️ **FakePlayer 的 `tick()` 是空实现** —— 破坏进度**不会自动推进** | — | 硬约束：必须自己**手动推进进度**，到 `>= 1` 时再调 `gameMode.destroyBlock(pos)`。<br>这正是 Create 自己维护 `blockBreakingProgress` 的原因。<br>⭐ **时间源用 `level.getGameTime()`（世界轴）而非 `gameMode.gameTicks`** ——<br>后者是玩家私有计数器且靠 `gameMode.tick()` 自增，而它不会跑（见 `L27 详解`） | ✅ 已知 |
| L36 | **P0** | 回放是「模拟**完整操作**」还是「直接破坏」？ | a) 直接 `gameMode.destroyBlock`<br>b) **模拟完整左键/右键** | ✅ **b（模拟完整操作）**（2026-09-18 用户提出）<br>📌 用户口径：*"挖掘记忆可记忆左键操作这个行为逻辑而非挖掘逻辑，交互记忆记忆右键操作 ——<br>这样模组里工具的奇奇怪怪效果也能触发"*<br>→ 补上被跳过的 `onLeftClickBlock` / `state.attack()` / `EnchantmentHelper.onHitBlock()` | ✅ b |
| L37 | P1 | 左键记忆的射线**命中实体**时怎么办？ | a) 只处理方块，命中实体则停<br>b) **命中实体 → 攻击它** | ✅ **b（攻击）**（2026-09-18 用户定）<br>📌 用户：*"本来想等做活武器时再考虑攻击逻辑"* → 现在做可为**活剑**铺路 | ✅ b |
| L38 | P1 | 事件触发频率（不能每 tick 触发挥击事件） | a) 每 tick 都触发<br>b) **首次命中触发一次，之后只推进进度** | **b** —— 否则音效/粒子/模组回调会疯狂触发 | *待定* |
| L39 | **P0** | ⚠️ **录制必须排除假玩家**（否则活工具会自己录自己） | — | ✅ **已修**（2026-09-19 实装踩坑）<br>症状：回放后记忆被自己覆盖，方向越飘越偏，最终变成「垂直向下挖」<br>原因：`FakePlayer` **是 `ServerPlayer` 子类**，`instanceof ServerPlayer` 挡不住<br>修法：`player instanceof ServerPlayer && !player.isFakePlayer()` | ✅ 已修 |
| L40 | **P0** | 回放射线如何找目标方块？ | a) `level.clip()` 路径第一个<br>b) 终点那一格<br>c) **沿射线逐格扫描 + 黑名单** | ✅ **c（逐格扫描 + 黑名单）**（2026-09-19 用户提出，实装）<br>① 仍是沿真实路径，**不会隔空挖**（优于 b）<br>② **黑名单直接无视** —— 宿主方块（大箱子两格）放黑名单，天然不会挖自己的家<br>③ 顺带解决 a 的致命缺陷：容器形态射线起点在方块内部，clip 必然先命中宿主，<br>　 需要"移出宿主/命中后小步前进"等补丁，且补丁还容易失效<br>④ 删掉了 `clip` / `clipSkipping` / `exitHost` 三个方法，代码大幅简化<br>⚠️ 代价：0.1 格步进，精度略低于 clip（对"挖哪一格"完全够用） | ✅ c |
| L41 | P1 | 黑名单未来可扩展成什么？ | — | 目前 = 宿主自身方块。未来可扩：玩家配置保护方块、模组标记方块等。<br>接口已按 `Set<BlockPos> blacklist` 设计，直接塞即可 | *待定* |
| L44 | **P0** | **记忆写入后的保护期** | a) 不加保护<br>b) **写入后 1 秒内不可被清除** | ✅ **b**（2026-09-19 用户定）<br>📌 起因：玩家挖完一个方块后**来不及松开左键**，准星顺势落到下一个方块上，<br>按 `L43` 就会把刚录好的记忆清掉（实测 bug）<br>→ 写入记忆（挖完 / 交互成功）后 **20 tick 内**，所有清除入口一律跳过<br>→ 用 `Map<UUID, Long>` 实现，登出 / 停服清理 | ✅ b |
| L47 | **P0** | **破坏裂纹为什么会永久残留？** | — | ✅ **已修**（2026-09-19 用户实测发现 + 源码定位）<br>症状：工具挖到一半、方块被移除后，**裂纹一直留在原地**<br>根因：原版靠 `ServerPlayerGameMode#tick()` 里"正在挖但方块已变空气 → 清裂纹"收尾，<br>而 **`FakePlayer#tick()` 是空实现**（同 `L46`/`L28`/`L27` 一脉）<br>⚠️ **被放大**：客户端裂纹是 **10 张固定纹理**（不检查该位置还是不是方块），<br>且按 **`breakerId`（实体 id）** 索引 —— 而 FakePlayer 按「维度+主人」**共享**，<br>同一主人的活工具共用同一个 id ⇒ **没人覆盖，永久残留**<br>修法：`replayDig` 的**每条「停」的路径**都走 `stopDigging()`（无记忆 / 没方块 / 类型不匹配 /<br>换目标 / 被取消 / 挖不动）<br>另修：完成破坏时 `held` 也须清进度，否则写回槽位的是「带残留进度」的副本 → 下一 tick 瞬间破坏 | ✅ 已修 |
| L48 | **P0** | **渲染要不要复刻服务端的命中判据？** | a) 复刻（客户端自己算）<br>b) **不自算，由服务端给结论** | ✅ **b**（2026-09-19 用户拍板）<br>📌 用户原话：*"渲染的射线有必要和具体逻辑一样，跳过这截断哪的嘛。根本不需要呀。"*<br>→ 服务端 `resolveDigTarget`/`resolveUseTarget` 判定，**只同步一个 boolean**（`ToolRay.digLanded/useLanded`）<br>→ 客户端只画 `origin → origin + offset`（**记忆射线本身，恒定**），透明度按 boolean 选<br>→ ⚠️ **中途走过弯路**：曾同步 `BlockPos` 并把终点画到目标方块中心 → **射线跟着目标跳**<br>　 （挖完一格跳下一格），背离 `L19` 初衷；用户连追两次才拆干净<br>→ ⭐ **只改容器形态**：容器**静止**→同步零成本；玩家/掉落物**高速运动**→本地重算才零延迟，<br>　 且起点在空气中 `clip` 本就正确 | ✅ b |
| L47 | **P0** | **容器形态的射线被截成一个点** | — | ✅ **已修**（2026-09-19 用户实测发现）<br>症状：容器里的活工具<b>挖掘功能正常</b>，但 F3+B 下射线只有穿进箱子内部才看得到<b>一个小点</b><br>根因：容器起点 = <b>方块中心</b>（`L14=a`），`level.clip` 一出发就<b>命中宿主自己的 OUTLINE</b> → `tip ≈ origin` → 光带长度为 0<br>⭐ <b>为什么服务端没这个问题</b>：`scanForTarget` 用「黑名单 + 逐格扫描」，天然跳过宿主 —— 所以只有渲染被自己的家卡住<br>初版修法：客户端<b>复刻</b>服务端判据（`scanOutOfHost` 步进扫描，<b>穿出宿主后才起笔</b>）<br>⚠️ <b>已被 `L48` 取代</b> —— 复刻会引出「宿主范围（大箱子两格）/ 流体 / 形状求交」三处新分歧，<br>　 且判据要维护两份、必然走偏 → 改为<b>服务端直接给 target</b>，客户端只画 | ✅ 已修（见 `L48`） |
| L46 | **P0** | **效率附魔为什么不生效？** | — | ✅ **已修**（2026-09-19 实测发现 + 源码定位）<br>症状很有指向性：**效率失效，但材质门槛 / 精准采集都正常**<br>根因：效率在 1.21 是**属性效果**（`MINING_EFFICIENCY`，值 = 等级²），<br>`Player#getDigSpeed` 里 `if (f > 1.0F) f += getAttributeValue(MINING_EFFICIENCY)`；<br>而该属性由 `collectEquipmentChanges()` 在 **`LivingEntity#tick()`** 里刷新，<br>**`FakePlayer#tick()` 是空实现** → 属性恒为 0<br>修法：`LivingToolFakePlayer#equipTool()` 复刻原版摘除 / 装配逻辑（用公开的 `ItemStack#forEachModifier`）<br>⚠️ 必须成对摘除 —— FakePlayer 实例跨工具共享（`L26`），只加不摘会叠加 | ✅ 已修 |
| L45 | P1 | **右键回放的节流还有必要吗？** | a) 保留（防可重复效果的模组工具）<br>b) **去掉**（靠天然终止） | ✅ **b（去掉）**（2026-09-19 用户定）<br>📌 原限流「每 10 tick 至多一次」的 `now % N == 0` **取模对齐**会让首次交互最多等 0.5 秒，手感迟钝<br>→ 改为每 tick 尝试，靠**天然终止**限速：原版 `ItemAbility` 都会改变方块，<br>　 下次扫描到的已不是同一个方块（记类型时 `matches` 失败 / 未记类型时 `useOn` 返回 PASS）→ 自动停 | ✅ b |
| L43 | **P0** | **记忆的「形成 / 清除」判据** | a) 只有明确的清除操作才清除<br>b) **完整操作才形成，半途而废就清除** | ✅ **b（难得易忘）**（2026-09-19 用户定）<br>📌 口径：*"挖掘记忆只有在挖完一个方块时才会记忆，如果只是左键点击一个方块则清除记忆。<br>把记忆逻辑弄成容易遗忘，形成记忆需要完整操作的方式。"*<br>→ **左右键对称**：完整操作 → 记录；不完整 → 清除<br>→ 挖掘：`LeftClickBlock(START)` 清除 / `BreakEvent` 记录<br>→ 交互：`RightClickItem`（没交互成）清除 / `BlockToolModificationEvent` 记录 | ✅ b |
| L42 | P1 | **射线终点落在宿主内部**时（极短记忆）要不要挖宿主？ | a) 不挖（宿主绝对安全）<br>b) **挖** | ✅ **b（挖）**（2026-09-19 用户定，理由是"这很有趣"）<br>→ 黑名单只在**存在外部目标**时生效；若整条射线都在宿主体内，则允许挖自己<br>📌 **真实可触发**：玩家挖<b>头顶</b>方块时，眼睛到方块仅约 0.4 格，<br>　 回放时终点仍在宿主方块内 → 活工具会把容器挖掉<br>⚠️ 后果：容器消失、内容物（含活工具自己）掉落 —— 玩家主动为之，不做兜底 | ✅ b |
| L24 | **P0** | **挖掘速度靠谁算？**（`getDestroyProgress` 需要 `Player`） | a) **`FakePlayer`**<br>b) 自己算（跳过 `HarvestCheck` hook）<br>c) 借用附近真实玩家 | ✅ **a（FakePlayer）**（2026-09-18 定，Create `Deployer` 同款成熟方案）<br>→ FakePlayer **自带 `gameMode`**，把活工具放它主手后 `getDestroyProgress` 自动正确<br>→ 项目内 0 处 FakePlayer 基建；NeoForge 提供 `net.neoforged.neoforge.common.util.FakePlayer` | ✅ a |
| L25 | P1 | FakePlayer 用**谁的 UUID**（能否过领地/保护插件） | a) 固定 UUID<br>b) **主人 UUID**<br>c) 混合 | ✅ **c（混合）**（2026-09-18 用户定）<br>📌 口径：*"玩家手动活化的活工具持有玩家 UUID；考虑到后续会有自动活化物品的活物品，留个自定义 UUID"*<br>→ 有主人 → 主人 UUID；无主人（未来自动活化）→ 自定义 UUID<br>→ 与 Create 同构：owner 为 null 时回退 `fallbackID` | ✅ c |
| L26 | P1 | FakePlayer **实例怎么管**（多个活工具共享？每个一个？） | a) 每个活工具一个<br>b) 每个宿主一个<br>c) 全局单例<br>d) **按 (维度, 主人UUID) 缓存，共享实例** | ✅ **d**（2026-09-18 用户采纳推荐）<br>核心论据：FakePlayer 是**无状态执行器**（进度在 DataComponent、tick 串行、<br>每次只需 `setPos` + `setOnGround(true)` + `setItemInHand` 三项配置）。详见 `L26 详解` | ✅ d |
| L28 | P1 | ⚠️ **FakePlayer 的 `onGround` 默认 false** → 挖掘速度会被 `/5` | 每 tick 使用前 `setOnGround(true)` | 坑：原版 `getDigSpeed` 有 `if (!onGround()) f /= 5.0F;`<br>FakePlayer 不在世界里，该字段默认 false | 待实现注意 |
| L22 | P1 | 多把活工具**同时挖同一格** | a) 各算各的进度（先到先挖，后者进度作废）<br>b) 共享进度（叠加） | ✅ **a（各算各的）**（2026-09-18 用户采纳倾向）—— 契合 `C1=d`；代价：快挖完时可能浪费一点进度 | ✅ a |
| L23 | P1 | 目标方块**中途变了**（被别人挖掉 / 被替换） | a) 进度重置 b) 保留进度继续挖新方块 | ✅ **a（重置）**（2026-09-18 用户采纳倾向）—— 与原版 `tick()` 语义一致（`isAir()` → 停）；新方块就该重挖 | ✅ a |

### `L6` 实现参考 —— 原版方块破坏机制（源码逐行核对，neoforge-21.1.249）

> 来源：`net/minecraft/server/level/ServerPlayerGameMode.java`（从 `build/moddev/artifacts/neoforge-21.1.249-sources.jar` 提取）

**① 状态字段**（L39-46）

```java
private boolean isDestroyingBlock;   // 正在挖
private int destroyProgressStart;    // 开始挖时的 gameTicks
private BlockPos destroyPos;
private int gameTicks;               // 每 tick 递增的计数器（不是世界时间！）
private boolean hasDelayedDestroy;   // "延迟破坏"模式（客户端停手、服务端补完）
private BlockPos delayedDestroyPos;
private int delayedTickStart;
private int lastSentState = -1;      // 上次下发的进度档位 0-9
```

**② 进度怎么算**（`incrementDestroyProgress`，L123-133）—— ⭐ **是"重算"不是"累加"**

```java
int i = this.gameTicks - startTick;
float f = state.getDestroyProgress(this.player, this.player.level(), pos) * (float)(i + 1);
```

即：**当前进度 = 单 tick 进度 × (已过 tick 数 + 1)**。
→ 只要记住"开始 tick"，每 tick **重算**即可，**不需要累加状态**。
→ 副作用：中途换工具 / 吃药水，进度会**立刻按新速度重算**（可能倒退）。

**③ 每 tick 推进**（`tick()`，L98-121）

```java
this.gameTicks++;
if (this.hasDelayedDestroy) {
    BlockState st = level.getBlockState(delayedDestroyPos);
    if (st.isAir()) { hasDelayedDestroy = false; }        // ← 目标没了就停（印证 L23）
    else {
        float f = incrementDestroyProgress(st, delayedDestroyPos, delayedTickStart);
        if (f >= 1.0F) { hasDelayedDestroy = false; destroyBlock(delayedDestroyPos); }
    }
} else if (this.isDestroyingBlock) { ... 同上 ... }
```

⭐ **目标方块变空气 → 自动停止**。`L23` 可以直接照抄这个语义。

**④ 开始 / 停止 / 取消**（`handleBlockBreakAction`，L138-236）

| 分支 | 行为 |
|---|---|
| `START_DESTROY_BLOCK` | ① `mayInteract` 权限检查<br>② **创造模式 → 直接 `destroyAndAck` 返回**（L156-159，瞬间破坏）<br>③ 记录 `destroyProgressStart = gameTicks`（L167）<br>④ `EnchantmentHelper.onHitBlock`（L171）<br>⑤ `f = getDestroyProgress(...)`（L183）<br>⑥ **`f >= 1.0F` → 立刻破坏（"insta mine"，L186-187）**<br>⑦ 否则进入持续挖掘（L194-199） |
| `STOP_DESTROY_BLOCK` | ⚠️ **`f1 >= 0.7F` 就算挖完**（L207）—— 不是 1.0！为容忍网络延迟<br>否则转入 `hasDelayedDestroy` 继续挖 |
| `ABORT_DESTROY_BLOCK` | 清除进度（L224-234） |

> 🔑 **`STOP_DESTROY_BLOCK` 的阈值是 `0.7` 而不是 `1.0`** —— 这是个反直觉但必须知道的原版细节。

**⑤ 真正破坏**（`destroyBlock`，L250-289）—— `L6` 要复刻的就是这段

```java
var event = CommonHooks.fireBlockBreak(level, gameModeForPlayer, player, pos, blockstate1); // ← BlockEvent.BreakEvent
if (event.isCanceled()) return false;
...
if (this.isCreative()) { removeBlock(pos, blockstate, false); return true; }   // 创造不掉
ItemStack itemstack = this.player.getMainHandItem();
ItemStack itemstack1 = itemstack.copy();
boolean flag1 = blockstate.canHarvestBlock(this.level, pos, this.player);  // ← 正确工具？决定掉不掉
itemstack.mineBlock(this.level, blockstate, pos, this.player);             // ← 扣耐久（在这里！）
boolean flag  = removeBlock(pos, blockstate, flag1);                       // ← 移除方块
if (flag1 && flag) block.playerDestroy(level, player, pos, blockstate, blockentity, itemstack1);  // ← 生成掉落
```

**⑥ 单 tick 进度公式**（`BlockBehaviour#getDestroyProgress`，已核对）

```java
float f = state.getDestroySpeed(level, pos);        // 方块硬度
if (f == -1.0F) return 0.0F;                        // 基岩等不可破坏
int i = EventHooks.doPlayerHarvestCheck(player, state, level, pos) ? 30 : 100;   // ← NeoForge hook
return player.getDigSpeed(state, pos) / f / (float)i;
```

→ **正确工具快 3.33 倍**（/30 vs /100）。

**⑦ 已验证的 public API**（`L6` 自己算速度时用得上，**不需要 Player**）

| API | 位置 |
|---|---|
| `ItemStack#getDestroySpeed(BlockState)` | `ItemStack.java:392` ✅ public |
| `ItemStack#isCorrectToolForDrops(BlockState)` | `ItemStack.java:562` ✅ public |
| `BlockState#getDestroySpeed(BlockGetter, BlockPos)` | `BlockBehaviour.java:686` ✅ public（取硬度） |

⚠️ **但 `BlockBehaviour#getDestroyProgress` 是 `protected`**（L393），
且内部的 `EventHooks.doPlayerHarvestCheck(player, ...)` **需要 Player**。
→ 若绕开 Player 自己算，会**跳过这个 NeoForge 钩子**，可能丢失模组对"正确工具"的覆写。
→ 见 `L24`。

**⑧ 与需求对应的三个点**（用户 2026-09-18 提的要求，全部能对上）

| 用户要求 | 源码位置 |
|---|---|
| 有破坏进度 | ② ③ |
| 效率附魔挖得快 | `player.getDigSpeed(state, pos)`（内部 `f += i*i+1`）→ 参与 ② 的 `getDestroyProgress` |
| **活铁镐挖黑曜石不掉落** | ⑤ `blockstate.canHarvestBlock(...)` → `flag1=false` → 不调 `playerDestroy` |

### `L6` 详解 —— 参考 Create `Deployer`（2026-09-18 源码研读）

> 📁 `libs/src/Create-mc1.21.1-6.0.10/src/main/java/com/simibubi/create/content/kinetics/deployer/`
> ⚠️ 注意：**Mechanical Arm（机械手）只搬物品**（DEPOSIT / TAKE，走 `IItemHandler`），
> 真正「模拟玩家用工具」的是 **Deployer（部署器）**。

**① 左键挖掘**（`DeployerHandler.java:242-284`，精简）

```java
if (mode == Mode.PUNCH) {
    if (!level.mayInteract(player, clickedPos)) return;              // 权限
    LeftClickBlock event = CommonHooks.onLeftClickBlock(player, clickedPos, face,
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);  // ← 让模组可拦截
    if (event.isCanceled()) return;
    clickedState.attack(level, clickedPos, player);                  // 方块被攻击

    float progress = clickedState.getDestroyProgress(player, level, clickedPos) * 16;
    float before = player.blockBreakingProgress == null ? 0 : player.blockBreakingProgress.getValue();
    progress += before;                                              // ← 累加（非重算）

    if (progress >= 1) {
        tryHarvestBlock(player, player.gameMode, clickedPos);        // ← 真正破坏
        level.destroyBlockProgress(player.getId(), clickedPos, -1);  // 清动画
        player.blockBreakingProgress = null;
        return;
    }
    level.destroyBlockProgress(player.getId(), clickedPos, (int)(progress * 10));  // 破坏动画
    player.blockBreakingProgress = Pair.of(clickedPos, progress);    // 存进度
}
```

- ⭐ `× 16`：部署器**不是每 tick 动作**（有冷却 timer），故一次补 16 倍，用于**抵消冷却**。
- 进度存在 `DeployerFakePlayer.blockBreakingProgress`（`Pair<BlockPos, Float>`，内存态）。

**② 右键使用**（`DeployerHandler.java:286-...`）

```java
UseOnContext ctx = new UseOnContext(player, hand, result);
RightClickBlock event = CommonHooks.onRightClickBlock(player, hand, clickedPos, result);
... stack.onItemUseFirst(ctx) / stack.useOn(ctx)
```
→ 与项目已有的「活耕地放置用 `ItemStack.useOn` 模拟」**思路一致**。

**③ 真正破坏：`tryHarvestBlock`**（`DeployerHandler.java:375-...`）

Create **复刻**了 `ServerPlayerGameMode#destroyBlock`（注释 `// <> PlayerInteractionManager#tryHarvestBlock`）：

```java
if (CommonHooks.fireBlockBreak(world, gameType, player, pos, blockstate).isCanceled()) return false;  // BreakEvent
if (player.blockActionRestricted(world, pos, gameType)) return false;
boolean canHarvest = blockstate.canHarvestBlock(world, pos, player);   // ← 材质门槛（决定掉不掉）
prevHeldItem.mineBlock(world, blockstate, pos, player);                // ← 扣耐久
... // removeBlock + block.playerDestroy()
```

⭐ **对我们的启示**：既然 `FakePlayer` **自带 `gameMode`**，而
`ServerPlayerGameMode#destroyBlock(BlockPos)` 是 **public** ——
**直接调 `fakePlayer.gameMode.destroyBlock(pos)` 即可**，不必复刻这一大段。
（Create 复刻是为了塞 `DoublePlantBlock` 之类的自定义 hack，我们不需要。）

**④ FakePlayer 实现要点**（`DeployerFakePlayer.java`）

```java
public class DeployerFakePlayer extends FakePlayer {
    Pair<BlockPos, Float> blockBreakingProgress;    // 破坏进度
    private UUID owner;
    // 各种覆写：不能吃、不受药水、不掉经验、不开菜单、eyeHeight=0 …
}
```

🔑 **过领地保护的关键技巧**（L165-204，注释注明 *Credit to Mekanism*）：
**覆写 `GameProfile#getId()` 返回「容器主人的真实 UUID」**，
`SpawnEntityEvent`/保护插件按 UUID 判权限时就会认成真实玩家。

```java
private static class DeployerGameProfile extends GameProfile {
    public UUID getId() { return owner == null ? super.getId() : owner; }
    public String getName() { ... UsernameCache.getLastKnownUsername(owner) ... }
}
```

**⑤ `L25` 详解 —— FakePlayer 必须有 UUID 吗？它真是"完整玩家"吗？**

> 源码：`libs/src/neoforge-21.1.249-merged/net/neoforged/neoforge/common/util/FakePlayer.java`

**UUID：必须，但值可以随便给**

```java
public class FakePlayer extends ServerPlayer {          // L97 —— 就是 ServerPlayer 的子类
    public FakePlayer(ServerLevel level, GameProfile name) {    // L98
        super(level.getServer(), level, name, ClientInformation.createDefault());
        this.connection = new FakePlayerNetHandler(level.getServer(), this);   // L100
    }
```

- 构造只接受 `GameProfile`，而 `GameProfile` **必须有 id**。
- UUID 的**取值**随意：Create 用常量 `9e2faded-cafe-4ec2-c314-dad129ae971d`。
- UUID 的**用途**：破坏动画 `destroyBlockProgress(entityId,...)`、保护插件按 UUID 判权限、各种 Map 索引。
- → `L25` 的实质不是"要不要 UUID"，而是**用谁的**：固定值（简单但可能被领地拦）
  还是**主人 UUID**（Create 技巧，能过保护，见 `L6 详解` ③）。

**不是"完全模拟"的玩家 —— NeoForge 刻意阉割了一批能力**

| 能力 | FakePlayer 的实际行为 | 源码 |
|---|---|---|
| 挖掘 / 使用物品 / 背包 / 装备 | ✅ **完整**（这就是我们要的） | 继承自 ServerPlayer |
| **自己 tick** | ❌ `tick()` **空实现** | L123 |
| 受伤 / 死亡 | ❌ `isInvulnerableTo` 恒 true、`die()` 空 | L110 / L120 |
| 收发网络包 | ❌ `send()` 空实现（不会污染网络） | L296-299 |
| 统计 / 进度 | ❌ 全部空 | L107 / L152-192 |
| 开菜单 / 骑马 / 伤害玩家 | ❌ 空 / false | L129 / L137 / L115 |
| 被模组识别 | ✅ `isFakePlayer()` 恒 true —— **模组可据此特殊处理** | L148 |

**其他关键点**

- 它有 **`FakePlayerNetHandler`（L195）** 而非 `connection == null`，所以调 `player.connection.send(...)` **不会 NPE**（只是什么都不做）。
- 它**不在世界的实体列表里**（除非手动 spawn），只是个内存中的 `ServerPlayer` 实例。
- ⚠️ 因为 `tick()` 是空的，**`gameMode.tick()` 不会跑** → 原版的破坏进度推进逻辑不生效
  → **我们必须自己推进进度**（见 `L27`）。这也正是 Create 自己维护 `blockBreakingProgress` 的原因。

**⑥ `L26` 详解 —— 多个活工具怎么共享 FakePlayer**

先明确：**FakePlayer 可以当"无状态执行器"用**，因为：

1. **进度不在它身上** —— 我们存在活工具的 DataComponent（`L21`）
2. 服务端 tick 是**单线程串行**的 —— 不会并发冲突
3. 每次使用前的必要配置只有三项：`setPos` / `setOnGround` / `setItemInHand`

| 方案 | 实例数（容器里 N 把活工具） | 评价 |
|---|---|---|
| a) 每个活工具一个 | N | ❌ 太重。FakePlayer 构造要初始化 inventory、装备栏、advancements…<br>大箱子塞满 = 27 个 ServerPlayer |
| b) 每个宿主一个 | 1（同宿主） | ⭕ 可行，但同宿主内**主人不同**时不成立（UUID 由 GameProfile 决定，不好改） |
| c) 全局单例 | 1 | ⭕ 可行但需频繁切 UUID → 实际上仍需按 UUID 分 |
| **d) 按 (维度, 主人UUID) 缓存** | 主人数量 | ⭐ **推荐** —— 兼顾开销与语义 |

**推荐 d 的使用流程**：

```
每 tick（对宿主 H）：
    for (活工具 T : H 里的活工具) {
        FakePlayer fp = FakePlayerCache.get(H.level.dimension(), ownerUuidOf(T));
        fp.setPos(H 的射线起点);
        fp.setOnGround(true);                    // ← 关键，否则速度 /5（见 L28）
        fp.setItemInHand(MAIN_HAND, T);          // ← 每次切换
        ...推进 T 自己的进度（读写 T 的 DataComponent）...
        if (progress >= 1) fp.gameMode.destroyBlock(targetPos);
    }
```

**配套要点**

- **销毁时机**：维度卸载 / 服务端关闭时清理缓存，并在 `remove()` 前调
  `level.destroyBlockProgress(id, pos, -1)` 清残留破坏动画（Create `DeployerFakePlayer:132` 同款）。
- **不要用 `FakePlayerFactory`？** 它按 `GameProfile` 缓存，可以用；但我们需要自定义
  GameProfile（主人 UUID 可变），所以更可能**自己实现一个 `LivingToolFakePlayer extends FakePlayer`**
  + 自己的缓存 Map。
- 若同宿主内活工具主人不同 → 会取到不同实例，**各自 setPos 即可**，无冲突。

### `L27` 详解 —— 进度推进公式（明确参考原版，非 Create）

**是的，我们用原版那一套**：

```java
// 原版 ServerPlayerGameMode#incrementDestroyProgress（L123-133）
int i = this.gameTicks - startTick;
float f = state.getDestroyProgress(player, level, pos) * (float)(i + 1);
```

即 **重算式**：`当前进度 = 单 tick 进度 × (已过 tick 数 + 1)`

**对比 Create 的累加式**（`DeployerHandler:260-265`）：

```java
float progress = clickedState.getDestroyProgress(player, level, clickedPos) * 16;
progress += before;                      // ← 累加
```

| | 原版（我们用） | Create（不用） |
|---|---|---|
| 算法 | 重算 `perTick × (t+1)` | 累加 `progress += perTick × 16` |
| 要存什么 | **只存 startTick** | 要存累加的浮点进度 |
| 为什么 | — | 部署器**有冷却**，不是每 tick 动作，故 ×16 补偿 |

→ **我们每 tick 推进，不需要 ×16 补偿**，所以选原版重算式，
   正好契合 `L21`（存 `{startTick, targetPos}`，写入量极小）。

**⚠️ 时间源的关键差异**

原版用 `gameMode.gameTicks`（**玩家私有计数器**，在 `gameMode.tick()` 里 `++`）。
但 FakePlayer 的 `tick()` 是空的 → `gameMode.tick()` 不跑 → `gameTicks` **不会自增**。

→ 我们必须用 **世界轴 `level.getGameTime()`** 代替：

```java
long now = level.getGameTime();          // 世界轴，每 tick +1
float progress = perTick * (now - startTick + 1);
```

这也与项目既有惯例一致（活熔炉、活耕地都用 `level.getGameTime()` 作世界轴时间戳）。

**⑦ 其他细节**

- `remove()` 时调 `level.destroyBlockProgress(id, pos, -1)` 清除残留破坏动画（`DeployerFakePlayer:132`）
- 监听 `LivingDropsEvent` 把击杀掉落收进自己背包（`:115`）
- 监听 `LivingExperienceDropEvent` 取消经验掉落（`:139`）
- 监听 `LivingChangeTargetEvent` 让生物不反击（`:145`，可配置）

### ⭐ `L6 = 模拟玩家` 带来的连锁简化（2026-09-18 整理）

定下「模拟玩家挖掘」后，原先一大批**需要人为定规则**的问题变成了**天然行为**：

| 原问题 | 原选项（要人为定） | 模拟玩家语境下的自然答案 |
|---|---|---|
| `D1` 材质门槛用哪把 | 最好的 / 最前的 / 任一 | ✅ **那把活工具自己** |
| `D2` canHarvest 与速度同把？ | 同 / 分 | ✅ **同一把（它自己）** |
| `E1` 时运/精准归谁 | 按排序取 | ✅ **它自己的附魔** |
| `E3` 效率叠加？ | 取最大 / 叠加 | ✅ **各用各的** |
| `E4` 耐久/经验修补 | 主贡献者 / 独立 | ✅ **各扣各的** |
| `F2` 扣哪把耐久 | 主贡献 / 分摊 / 各扣 1 | ✅ **挖的那把扣自己** |
| `C1` 多把速度叠加公式 | 求和 / 衰减 / 取最大 | ✅ **各算各的，不需要公式** |
| `L16` 节流 | 每 tick 几次 | ✅ **天然限速**（有破坏进度） |

**原因**：模拟玩家 = 那把活工具"自己"在挖 —— 材质、附魔、耐久、速度全部由它自身决定，
不需要任何"多把之间如何仲裁"的规则。

⚠️ **但这些规则并没有消失**，它们只是**转移到了另一条支线**：
「**无记忆 + 在背包里 + 辅助玩家挖掘**」时，仍是**多人同时敲一块**，
`D4` / `E6` / `C1(支线)` 的仲裁规则在那里**依然需要**（`E6` 还是原始需求的原文要求，不能丢）。

### ⚠️ 需要特别提醒的平衡性风险

`L7 = b`（持续挖）会让「容器 + 活镐 + 挖掘记忆」变成一台**永动挖矿机** ——
且因为活物品在容器内自动 tick，玩家挂机就能产出矿石。
这与现有活物品（活耕地需要种子与生长节拍、活熔炉需要燃料）的**"有成本"基调**不同。
建议在 `L7` 明确前不要动手写回放逻辑。

---

## 4. 落地顺序（草案）

> 2026-09-18 更新：**记忆回放（L 组）已取代"辅助挖掘"成为主线**，设计已收敛，可开工。

### ⭐ 轨道一 · 记忆回放主线（L 组）—— 设计已定稿，建议先做

| 步骤 | 内容 | 依赖的定案 |
|---|---|---|
| **L-a** | `LivingToolMemory` 组件（挖掘记忆 / 交互记忆，各存 `Vec3 offset` + 可选 `Block`）<br>persistent + networkSynchronized | L1 L2 L4 L5 |
| **L-b** | 录制：监听 `BlockEvent.BreakEvent` + `BlockToolModificationEvent`<br>⚠️ 过滤 `isSimulated()`；条件 = 手持活工具；蹲下额外记 `Block` | L1 L3 L15 |
| **L-c** | `LivingToolFakePlayer extends FakePlayer` + 按 (维度, 主人UUID) 缓存<br>每次使用前 `setPos` + `setOnGround(true)` + `setItemInHand` | L24 L25 L26 L28 |
| **L-d** | 回放：每 tick 从宿主射线起点 `clip`，命中则推进进度（存 `{startTick, targetPos}`）<br>`progress >= 1` → `fakePlayer.gameMode.destroyBlock(pos)` | L6 L7 L14 L21 L27 |
| **L-e** | 清除：左键/右键 `MISS` 清对应记忆；取消活化走 `clearLivingData` | L13 L17 L18 |
| **L-f** | 三宿主接入：容器（现有管线）/ 背包（现有）/ **掉落物（需新增 tick 通道）** | K1 L23 |
| **L-g** | 射线可视化（高亮目标方块） | L19 L20 |

> ⚠️ **L-d 的 `L7=b`（有方块就挖）配合刷石机会变成自动挖矿机** —— 这是用户明确接受的玩法，
> 但 `L16` 节流仍需确认（因有破坏进度，天然限速，可省）。

### 轨道二 · 容器内 GUI 交互（J 组）—— 复用现成基础设施，风险最低

```
J-a  StripLogHandler / FlattenToPathHandler
     → 判据直接调 AxeItem.getAxeStrippingState / ShovelItem.getShovelPathingState（J3=c）
J-b  commonSetup 注册（通配条目 + triggerFilter，注意两条硬约束）
```

### 轨道三 · 世界空间悬浮渲染（K 组）—— 全项目从零建

```
K-a  RenderLevelStageEvent 管线 + 掉落物形态（零同步、零服务端改动）★ 建议最先做
K-b  真 3D 模型（先只做活木镐子一种）
K-c  容器形态 S2C 同步包（LivingFloatSyncPacket，按距离定向）
K-d  玩家身边形态 + 动作动画（LIVING_ACTION network-only 组件）
```

### 轨道四 · 无记忆辅助挖掘（A3 支线）—— 需要 mixin，可最后做

```
阶段0  ContainerContext 补 getOwnerPlayer()
阶段1  Mixin ServerPlayerGameMode 拿破坏进度（B1=a）★ 唯一技术难点
阶段2  仲裁规则：D4（材质）/ E6（时运精准按排序）/ C1 支线（速度叠加）
```

**前置依赖**
- 轨道一：`L8` / `L19` / `L20` / `L22` / `L23` 收尾后即可开工（其余 P0 已定）
- 轨道二：`J1`（产物保活）待定
- 轨道四：`A1` / `A2` / `C1` 支线 / `D4` / `E6` 待定

---

## 5. 探讨日志

### 2026-09-17（第 1 轮）

- 通读 `idea.md` 原始需求 + 全项目架构，**建立本文与问题池**。
- 确认三条好消息：玩家背包每 tick 已扫描（无需新增扫描通道）、`ItemAbility` 语义判定范式可复用（模组工具自动兼容）、背包槽位同步客户端已就绪。
- 确认三个缺口：挖掘进度获取（0 代码）、世界内渲染（0 先例）、`ContainerContext` 无 `getPlayer()`。
- 达成共识的工作方式：**先探讨、逐步补细节、问题逐个拍板、最后才写代码**。
- 下一步建议：先定 `A` + `B` 组（决定技术路线），`C`/`D`/`E` 的数值可后续慢慢调。

### 2026-09-17（第 2 轮）

- **拍板 H3**：GUI 层语义要做，且**修正**了我上一轮举的例子（我举"原木 → 木板"是错的）：
  - 活斧子 → 右键容器内**活原木** → **去皮原木**（不是木板）
  - 活铲子 → 右键容器内**活土** → **土径**
  - 活镐子 → **暂无右键逻辑**
  - 这是**容器内玩家参与的交互逻辑**（区别于世界方块层）
- **拍板 J0**：目标必须活化。核对后发现这**同时是框架硬约束**
  （`InteractionRegistry.findInteraction:83`），与活锄头口径一致。
- 通读 `InteractionRegistry` / `InteractionEntry` / `TillToFarmlandHandler` / `Tillables`，
  提炼出抄骨架时的**两条硬约束**（通配条目吞右键、谓词须自查 trigger 活化），已写入 `J` 组。
- 发现「材料型活物品」（只有 `IS_LIVING`、无 `LivingItemFunction`）是已有先例
  （活泥土），活原木 / 活土可沿用 → 记为 `J9`。
- 新增 `J` 组（10 个待决）并把落地顺序拆成**两条独立轨道**。
- 下一步：建议先吃 `J1`/`J3`（产物保活 + 映射表来源），轨道一即可开工；
  轨道二仍需先定 `A3`/`B1`。

### 2026-09-17（第 3 轮）

- 用户补充两大需求：**容器形态悬浮** + **掉落物形态悬浮**，并说明动机 —— 想让活工具
  真的"活"（待机动作：转圈/摇晃，像宝可梦；功能动作也要有动画）。这是首次做此类功能。
- 完成一轮渲染/同步/动画可行性调研，结论写入 §2.2/§2.3 与新增 `K` 组。
- **最大利好**：掉落物形态**不需要任何自定义包** —— `IS_LIVING` 已 `networkSynchronized`，
  客户端直接可读（`K` 组、§2.3）。
- **最大坑（可能是本需求最大工作量）**：项目**没有任何 3D 模型**，85 个 item json 全是
  平面精灵；活耕地那套 `renderSingleBlock` 真 3D 先例**对工具无效**（工具没有对应方块）
  → 必须手工建 3D 模型 json。已记为 `K3` 并给了「先只做活木镐子一种」的收窄建议。
- **第二个坑**：容器形态客户端不开 GUI 就拿不到内容，**必须新增 S2C 包**，
  且不能复用 `LivingItemSyncPacket`（那是 tooltip 通道，只发给开 GUI 的人）→ `K2`。
- 提炼三条**不可照抄的 GUI 套路**（世界空间会翻车）：`FULL_BRIGHT` 光照、`disableDepthTest`、
  `setShaderLights` 正面打光 —— 已写入 `K.1`。
- 新增轨道三（渲染），并给出**最小验证路径**：掉落物 + flat 模型 + 待机动画起步
  （零同步、零服务端改动，一次验证渲染管线全部关键决策）。
- 下一步：定 `K3`（模型路线）+ `K1`（掉落物要不要 tick）+ `K6`（动画驱动方式）。

### 2026-09-17（第 4 轮）

- 用户问「3D 模型的实现方式有哪些」→ 调研并写入 **`K.3` 方案对比表**（A~F 六条路线）。
- 确认依赖现状：**无 GeckoLib**（只有 Create / Sable 软依赖）；`build.gradle:25` 已排除
  `*.bbmodel` → Blockbench 本就是可预期工作流；已有 `RotatingWaterWheelModel` 的
  `BakedModel` 包装机制可复用。
- **核心结论**：待机/功能动作（转圈、摇晃、浮动、挥动、挤压）**几乎全是刚体变换，
  PoseStack 就够**，大概率**不需要上 GeckoLib**。只有真正的形变（弯曲/软体）才要骨骼。
  → 推荐 **方案 A（3D 物品模型 JSON）+ 独立 standalone 模型路径 + PoseStack 动画**。
- **两个易踩的实现细节**已写入：① 不要替换物品本身模型（会破坏物品栏图标/装饰器），
  走独立 standalone 路径 + `ItemRenderer.render(..., model)` 传自定义 BakedModel；
  ② 3D 物品模型要写显式 `display` 段，别直接 `parent: minecraft:block/block`。
- 提出**分组模型**（镐头/柄拆两个模型分别变换）作为「刚体动画 ⇄ 骨骼动画」之间的中间档。
- 下一步：定 `K3`（选 A 还是直接上 GeckoLib）+ 工种确认（谁来做 Blockbench 建模）。

### 2026-09-17（第 5 轮）

- 用户质疑「为什么还要建模，不是有模型嘛」→ **问得好，暴露了我上一轮的自相矛盾**
  （我写了"flat 模型 + 待机转圈"，但 flat 模型转圈会变成一条线）。
- 澄清：模型确实有，问题不是"有没有"而是"**2D 还是 3D**"。原版工具走
  `item/handheld` → `item/generated`，本质是**把 PNG 贴到平面上**的一张纸。
  「建模」= 给已有的镐子外观**做出厚度和体积**，不是重造外观。
- **新增「方案 0：Billboard flat（零建模）」** 并写入 `K.3`：
  始终面向玩家 + 上下浮动 + ±15° 摇晃 → 任意角度都是完整贴图，摆动还提供立体感。
  **分叉点澄清**：`360° 自转` / `被从上方侧面看到` 才必须 3D；`浮动` / `小幅摇晃` /
  `绕玩家公转（billboard）` flat 完全够用。
- 修正 `K.12` 第 1 步：由「flat + 转圈」改为「flat + billboard + 浮动/摇摆」，
  并显式标注 flat 不能做 360° 自转。
- 下一步：先跑方案 0 看效果，再决定是否值得建模。

### 2026-09-17（第 6 轮）

- **纠正上一轮的错误判断**（用户指出"原版工具的掉落物形态是有厚度的"→ 用户是对的）。
  原版 `item/generated` 会做 **extrusion（挤出）**，物品是**带厚度的薄板**而非零厚度纸片。
  证据：Modrinth 资源包 *Flatter Items: 2D Held Sprites*（1.20.1–1.21.11）描述
  "removing the **vanilla extrusion**"。
- **结论修正**：建模的**必要性大幅降低** —— flat 模型连 360° 自转都能做
  （侧面不会消失，只是薄）。分叉点从「❌ 必须 3D」放宽为「⚠️ 可行但侧面薄」。
- **仍需注意**：挤出的是 sprite 轮廓，镐柄/镐头之间没有立体结构关系，
  本质是"薄板"而非"由 box 拼出的立体模型"。真 3D 的收益是**立体结构**，不只是厚度。
- 同步修正 `K.12` 第 1 步的注解。
- 待实测：挤出厚度的量级（进游戏扔一把镐子绕看一圈）。

### 2026-09-18（第 7 轮）—— 玩法大改：记忆 + 回放

- 用户在文件顶部写下完整玩法（已归档至 `§0.5`）：**活工具会"记忆"玩家的挖掘/交互动作，
  之后在容器里按记忆自动回放**。核心是「**挖掘记忆**」+「**交互记忆**」，各记一个、新的覆盖旧的，**蹲下额外记方块类型**。
- **拍板 `K1`**：掉落物**也工作**（推翻我上一轮的 a 倾向）→ 需要服务端掉落物 tick 扩展。
- **⭐ 最重要的发现：整个「记忆 + 回放」主线不需要任何 mixin。**
  - 录制挖掘记忆 → `BlockEvent.BreakEvent`（自带 pos/player/state）
  - 录制交互记忆 → **`BlockEvent.BlockToolModificationEvent`**（NeoForge 官方事件，已核实 1.21.1 存在：
    `getPlayer()` / `getHeldItemStack()` / `getItemAbility()` / `getPos()` / `isSimulated()`）
  - 覆盖 `AXE_STRIP` / `HOE_TILL` / `SHOVEL_FLATTEN` / `AXE_SCRAPE` / `AXE_WAX_OFF`
    → **四件套一次实现全支持**，且模组自定义 ItemAbility 自动兼容
  - ⚠️ 必须过滤 `isSimulated()`，否则原版 simulate 那一趟会重复录制
- **路线简化**：`B1` 的 `ServerPlayerGameMode` mixin **降级为支线**
  （只服务「背包 + 无记忆 + 辅助玩家挖掘」），可最后做。
- 新增 `§3.12 L 组`（12 个待决），并在其中标注 **L7（回放频率/终止）** 是平衡性关键：
  「持续挖」= 永动挖矿机，与现有活物品「有成本」基调冲突，未定前不建议动手。
- 下一步：定 `L1`（方向表示）/ `L3`（原点）/ `L7`（终止条件）。

### 2026-09-18（第 8 轮）—— 记忆模型细化

- **拍板**：`L1=c`（**射线向量**，非量化格子）、`L2=a`（距离保留浮点）、`L3=a`（原点 = 眼睛/射线起点）、
  `L7=b`（**有方块就挖，没方块就停**）。
- **拍板 `L13` 清除机制**：**左键空气 → 清挖掘记忆**；**右键空气 → 清交互记忆**；
  **取消活化 → 清 NBT 组件**（现有 `clearLivingData` 已覆盖）。细节转 `L17`。
- 补充**射线技术说明**（录制/回放两侧伪码 + 1.21.1 API 表 + `BLOCK_INTERACTION_RANGE` 上限）。
- **承认上一轮多虑**：「跨维度」问题在 `L1=c`（相对向量）下**自动消失**
  —— 只有存绝对坐标/绝对朝向时维度才有意义。已写入文档。
- **新增 5 个待决**：`L14`（容器/掉落物的射线起点 —— **必须定**，否则与玩家眼高差约 1 格）、
  `L15`（命中余量，否则"明明记得却挖不到"）、`L16`（永动挖矿机节流）、`L17`（空气判定细节）。
- 下一步：定 `L14`（最关键）+ `L16`。

### 2026-09-18（第 9 轮）

- **拍板 `L14` = a**（容器方块中心）/ d（掉落物实体位置）。用户补充的关键口径：
  **"重要的是这条线本身，而不是原点在哪"** → 记忆 = 一条**线段**（方向 + 长度），
  回放 = 把这条线**平移**到宿主身上，不追求复现玩家当时的相对高度。已写入 `L14` 结论列。
- **拍板 `L17`**：清除记忆 = **玩家手持活工具 + 射线未命中实体**。
  派生 `L18` 待确认精确判定（我倾向 `hit.getType()==MISS`，即方块和实体都没命中）。
- 补写 **`L15` 详解**（余量在防什么 —— 结论：用"到方块中心"时理论上不必需，属廉价保险）
  与 **`L16` 详解**（"每 tick 几次"的含义 —— 结论：与 `L6` 强耦合，`L6` 先定）。
- 下一步：确认 `L18`，然后回到 **`L6`**（回放怎么挖）—— 它是 `L16` 的前置，
  也是记忆回放主线最后一个 P0。

### 2026-09-18（第 10 轮）

- **拍板 `L6` = b（模拟玩家）**，且用户明确要求保留**完整原版挖掘语义**：
  ① 有破坏进度 ② 效率附魔生效 ③ **材质门槛生效**（活铁镐挖黑曜石不掉东西）
  ④ 必须触发 `BlockEvent.BreakEvent`（保护插件可取消）。
  → 因自带进度，`L16` 节流可省（天然限速）。
- **拍板 `L15` = a（不加余量）**。用户反对过度兜底：
  *"射线落在缝隙里，玩家重新录一个就好，没必要兜那么多底"*。
- ⭐ **提炼设计原则**：**优先给玩家「信息与手段」，而不是在代码里兜底**。
  已写入 §3.12 与本文档顶部理念区（见下）。
- **拍板 `L18` = a（`hit.getType()==MISS`）**。
- **新增 `L19`（射线可视化）+ `L20`（显示时机）** 作为 `L15` 的替代方案 ——
  关键可行性：射线检测**客户端也能做**，记忆同步到客户端后本地 `clip` 即可，
  服务端**无需同步命中结果**。
- 解答用户提问：玩家指针选中方块时，射线命中在**方块表面**（`getLocation()`），
  不是中心；我们录制时主动改用 `Vec3.atCenterOf(pos)` 作基准。
- 下一步：定 `L19`（可视化方式）+ `L20`（时机）；之后主线 P0 全齐，可开工。

### 2026-09-18（第 11 轮）

- **推翻我上一轮的"改用方块中心"建议** —— 用户要求**录制纯净射线**：
  *"到表面那就到表面，不要做多余的操作"*。
  📌 理由（很硬）：**F3 能看到真实射线与碰撞箱**，若记忆射线与玩家看到的不一致会困惑。
  → 原则：**忠实于原版 > 代码上更好算**。
- ⭐ **实现建议**：直接存 **完整偏移向量** `offset = location - eye`（一个 `Vec3`），
  而非「归一化方向 + 距离」。数学等价，但**无归一化/还原的精度漂移**，
  且保证**可视化与服务端判定用同一条线**。
- **拍板 `L5` = a（整堆叠共享一份记忆）**。用户口径：
  *"活工具一般是无法堆叠的，没有堆叠的功能逻辑语义，以原版为基准"*
  → 原版工具 `maxStackSize=1`，不做"每把独立"或"按堆叠数倍增"；模组强行堆叠时共享、不崩即可。
- **连带拍板 `C2` = b（堆叠数不参与加速）** —— 工具根本不堆叠，此问实践上不成立；
  加速只来自**多个独立槽位的多个 ItemStack**（`C1`）。
- **至此 `L` 组 P0 全部收敛**（L1/L2/L3/L5/L6/L7/L13/L14 已定），
  剩 `L19`+`L20`（可视化，P1）与 `L8`（建议沿用 `L7` 语义，待确认）。

### 2026-09-18（第 12 轮）—— 模拟玩家的连锁反应

- ⭐ **核心发现：`L6 = 模拟玩家` 让一大批玩法规则"自动消解"**。
  模拟玩家 = 那把活工具**自己**在挖 → 材质门槛（`D1`）、附魔归属（`E1`/`E3`/`E4`）、
  耐久（`F2`）、速度叠加（`C1`）、节流（`L16`）**全部由它自身决定**，
  不需要任何"多把之间如何仲裁"的规则。已写入新的 `⭐ 连锁简化` 小节。
- ⚠️ **但这些规则没消失，只是转移了**：「无记忆 + 背包 + 辅助玩家挖掘」时仍是
  **多人同时敲一块**，`D4`（材质）/ `E6`（时运精准排序）/ `C1` 支线的仲裁规则依然需要。
  `E6` 是**原始需求原文**（"按背包排序取靠前者"），**不能丢**，已单独建条目守着。
- 新增 3 个待决：`L21`（**破坏进度存在哪** —— P0，建议存活工具的 DataComponent，
  契合"状态跟随物品跨容器迁移"，先例：活熔炉 `progress`）/ `L22`（多把挖同一格）/
  `L23`（目标方块中途变了）。
- 下一步：定 `L21`（最后一个真 P0）+ `L8` + `L19`/`L20`。

### 2026-09-18（第 13 轮）—— 研读 Create 的「模拟玩家」实现

- 用户提示「机械手的机械手可以模拟玩家使用工具」→ 研读 Create 源码，
  发现 **Mechanical Arm（机械手）其实只搬物品**（DEPOSIT/TAKE 走 `IItemHandler`），
  真正模拟玩家用工具的是 **Deployer（部署器）**。
- **`L24` 定案：用 `FakePlayer`**（Create/Mekanism 同款成熟方案）。
  FakePlayer **自带 `gameMode`**，把活工具放它主手，`getDestroyProgress` 自动正确
  （效率附魔、材质门槛全自动），无需自己算。
- **`L6` 定案：走 `fakePlayer.gameMode.destroyBlock(pos)`** ——
  `ServerPlayerGameMode#destroyBlock` 是 **public**，内部已包含
  `fireBlockBreak` / `canHarvestBlock` / `mineBlock` / `playerDestroy`，
  **不必复刻 Create 的 `tryHarvestBlock`**（Create 复刻是为塞自定义 hack）。
- 学到 **×16 补偿系数**：Create 因部署器有冷却，一次加 16 倍单 tick 进度。
  我们若每 tick 动作则不需要；若有冷却需按冷却倍数补偿。
- 🔑 学到 **过领地保护的关键技巧**：**覆写 `GameProfile#getId()` 返回主人真实 UUID**
  （Create 注明 Credit to Mekanism）→ 记为 `L25`。
- 新增 `L25`（FakePlayer 身份/权限）、`L26`（实例管理）。
- 下一步：`L21`（进度存哪，倾向存 `startTick`+`targetPos` 于 DataComponent）+ `L25`/`L26`。

### 2026-09-18（第 14 轮）

- **拍板 `L25` = c（混合）**：玩家手动活化 → 主人 UUID；未来「自动活化物品的活物品」
  → 自定义 UUID（用户主动留扩展口）。与 Create 的 `owner == null ? fallbackID : owner` 同构。
- **`L21` 补充**：用户同意存 NBT，但提醒**必须连 `targetPos` 一起存** ——
  宿主迁移（容器→背包→掉落物）后位置变了，比对不上就重置进度，否则会"瞬间挖穿"。
- **`L26` 给出推荐 d（按维度+主人UUID 缓存，共享实例）** 并写入详解：
  核心论据是 **FakePlayer 可当"无状态执行器"** —— 进度在 DataComponent 里、服务端 tick 串行、
  每次只需 `setPos` + `setOnGround` + `setItemInHand` 三项配置。
- ⚠️ 新发现坑 **`L28`**：FakePlayer 不在世界里，`onGround` 默认 false，
  而原版 `getDigSpeed` 有 `if (!onGround()) f /= 5.0F` → **不处理会慢 5 倍**。
- **拍板 `L26` = d（按维度+主人UUID 缓存，共享实例）**（用户采纳推荐）。
- 🎉 **记忆回放主线的技术设计至此收敛**（L1-L7 / L13-L15 / L17-L18 / L21 / L24-L28 全部已定）。
- 剩余均为收尾小项：`L8` / `L19` / `L20` / `L22` / `L23`。

### 2026-09-18（第 15 轮）—— 收尾小项定案

- **拍板 `L8`=a（沿用 L7）、`L22`=a（各算各的）、`L23`=a（进度重置）**（用户采纳倾向）。
- **拍板 `L19`=a（简单一条线）**、**`L20`=f（F3+B 显示碰撞箱时才显示）**。
  - `L20` 源码依据 `KeyboardHandler.java:143-146`，读取 `mc.getEntityRenderDispatcher().shouldRenderHitBoxes()`，
    **纯客户端、无需同步**，与 `L15`（F3 看真实射线）的设计理念一脉相承。
  - ⚠️ 已知取舍：F3+B 会连带显示所有实体碰撞箱；若日后碍事可再叠加"看着宿主时显示"→ 记 `L29` 备用。
- **补充 `L19` 的关键澄清**：**正在挖的时候不用画** ——
  `ServerLevel#destroyBlockProgress()` 会让客户端自动显示裂纹（Create 同款），零渲染代码。
  `L19` 只管"待机/未开始挖"时的目标线。
- 🎉 **`L` 组全部 P0 收敛，且 L19/L20 定案后主线的可视化也定了。**
- 剩余少量 P1：`L4`（记 Block 还是 BlockState）/ `L9`（背包有记忆时是否仍辅助）/
  `L10`（掉落物移动时跟随）/ `L11`（保护插件兼容细节）/ `L16`（节流，可省）。

### 2026-09-18（第 16 轮）—— L 组封版 + 研读御剑模组

- **5 个 P1 全部按推荐定案**：`L4`=a（记 Block，去皮后天然停止）/ `L9`=a（v1 只回放，
  未来升级"智能切换"依赖 `B1`）/ `L10`=a（掉落物移动则跟随）/ `L11`（掉地上、被拒则等待）/
  `L16`=c（不加节流，天然限速）。→ 🎉 **`L` 组全部封版**。
- 研读 `libs/src/YujianCraft-main`（御剑，⚠️ Forge 非 NeoForge），三个收获写入 `K.11`：
  ① 架构分层（主体用实体+Renderer，特效用 RenderLevelStageEvent）
  ② **`FormationGeometry` 阵型算法** —— 环形 `angle = 2π*slot/6 + tickCount` **直接实现"绕圈+起伏"的待机动作**
  ③ 它的剑阵是纯自定义顶点，不用物品模型（我们显示工具本体用 `renderStatic` 即可）
- **拍板 `K30` = b（不用实体，纯客户端 `RenderLevelStageEvent` 渲染）**。
  📌 用户口径：*"一个活工具维护一个实体代价高；交互逻辑就一根射线，渲染只是视觉照应"*。
- 据此梳理出**优雅架构**：服务端管逻辑 + 写一个 network-only 的 `LIVING_TOOL_ACTION` 报告行为，
  客户端读记忆自己 clip + 播动画。四个已有条件全部就绪（记忆同步 / 客户端能 clip /
  破坏裂纹原版同步 / network-only 组件有先例）。派生 `K31`（动画驱动源）待定。
- 下一步：定 `K31`，或开工 `L-a`。

### 2026-09-19（第 17 轮）—— 记忆回放实装完成 + 「难得易忘」规则打磨

**实装完成**（`L-a` ~ `L-f` 前段），实测通过：
录制（`BreakEvent` / `BlockToolModificationEvent`，零 mixin）→ 存储（`LivingToolMemory` DataComponent）
→ 回放（三宿主：背包 / 容器 / 待做掉落物）→ 清除。技术细节见 `docs/tech/living-tool-tech.md`。

实装中踩到并修复的坑（已记入 tech 文档 §9）：
- ⚠️ **`L39` 活工具自己录自己**：`FakePlayer` **是** `ServerPlayer` 子类，
  `instanceof ServerPlayer` 挡不住 → 记忆被自己覆盖，方向越飘越偏（症状：变成垂直向下挖）。
  修法：`instanceof ServerPlayer && !player.isFakePlayer()`。
- ⚠️ **`L40` clip 起点在方块内 → 挖掉容器**：改用**逐格扫描 + 黑名单**，
  黑名单直接无视，天然解决"挖自己的家"，还删掉了三个补丁方法。
- ⚠️ **`L28` FakePlayer 不在世界里**，`onGround` 默认 false，原版 `getDigSpeed` 会 `/5` → 慢 5 倍。

**玩法规则打磨（本轮重点）**：

- **拍板 `L43`（难得易忘）** —— 用户口径：*"挖掘记忆只有在挖完一个方块时才会记忆，
  如果只是左键点击一个方块则清除记忆。把记忆逻辑弄成容易遗忘，形成记忆需要完整操作的方式。"*
  → **左右键完全对称**：完整操作 → 记录；半途而废 → 清除。
- 🔧 **修正一个我引入的错误**：清除曾要求"射线 `MISS` 才生效"，
  但**狭小空间里玩家根本无法对着空气右键** → 交互记忆**永远清不掉**。
  用户指出后改为「这次右键没产生任何有效交互」即清除。
- **拍板 `L44`（记忆保护期）** —— 实装 `L43` 后暴露新 bug：
  玩家挖完一个方块后**来不及松开左键**，准星顺势落到下一个方块上 → 刚录的记忆被清掉。
  解法（用户提出）：**写入记忆后 1 秒（20 tick）内，所有清除入口一律跳过**。
- **拍板 `L45`（去掉右键回放限流）** —— 用户反馈"限流的体验不太好"。
  分析确认：原限流 `now % 10 == 0` 的**取模对齐会让首次交互最多等 0.5 秒**，主动制造了延迟；
  而它想防的东西**天然就被防住了**（原版 `ItemAbility` 都会改变方块 → 下次扫描不匹配 → 自动停）。
  → 去掉限流，改为每 tick 尝试。残余风险（模组工具原地重复成功）留作观察。

**第五项验证暴露的最后一个 bug（`L46`）**：

- 用户实测：「5 项里只有效率附魔不生效，精准采集生效」。
- ⭐ **这个"一半好一半坏"的组合就是最强线索** —— 说明**走 `getMainHandItem()` 的都对，
  只有走属性表的失效了**。源码定位：
  - 效率在 1.21 是 **`MINING_EFFICIENCY` 属性**（值 = 等级²，效率 III = +9）
  - `Player#getDigSpeed`：`if (f > 1.0F) f += getAttributeValue(MINING_EFFICIENCY)`
  - 该属性由 `collectEquipmentChanges()` 在 **`LivingEntity#tick()`** 里刷新
  - **`FakePlayer#tick()` 是空实现** → 属性恒为 0 → 效率无效
- 修法：`LivingToolFakePlayer#equipTool()` 复刻原版摘除 / 装配逻辑
  （原版 `detectEquipmentUpdates()` 是 private）。⚠️ 必须成对摘除 ——
  FakePlayer 实例跨工具共享（`L26`），只加不摘会让多把工具的修饰符叠加。
- 教训：**FakePlayer 的"空 tick"是个持续性的坑源** —— 凡是依赖 `LivingEntity#tick()`
  刷新的状态（装备属性、`L28` 的 onGround、`L27` 的进度）都得手工补。已写入 tech 文档 §9.4。

- 🎉 **活工具「记忆 + 回放」主线（`L` 组）功能全部实装并实测通过**（掉落物形态除外）。
- 下一步候选：`L-f` 掉落物形态 tick / `K` 组悬浮渲染 / `J` 组容器内 GUI 交互 / `L19` 射线可视化。

### 2026-09-19（第 18 轮）—— 射线可视化落地（`L19` / `L20`）

- **实现** `client/render/LivingToolRayRenderer` —— 订阅 `RenderLevelStageEvent` @ `AFTER_ENTITIES`，
  仅在 **F3+B** 时绘制活工具的记忆射线。**纯客户端、零同步、零服务端改动**
  （记忆组件本来就 `networkSynchronized`，客户端本地重算射线即可）。
- 三个设计点：
  1. **命中与否用透明度分**：本地 `clip` 命中 → 画到命中点（不透明）；
     落空 → 画到终点（**半透明**）。"变暗"本身就是信号：这条线擦着缝过去了。
  2. **正在挖时不画** —— 原版 `destroyBlockProgress` 的破坏裂纹已经在显示，重复画是噪音。
  3. **两条记忆两个颜色**：挖掘（橙红）/ 交互（青蓝），一眼分得清。
- ⚠️ **v1 的宿主覆盖边界（重要）**：

  | 宿主 | 状态 |
  |---|---|
  | 玩家（背包 / 主手 / 副手） | ✅ |
  | 掉落物 | ✅ |
  | **方块容器** | ❌ **待 `K2` 的 S2C 包** —— 不开 GUI 时客户端拿不到箱子内容 |

  也就是说：**射线可视化最想服务的"箱子里的工具"暂时看不到**，要等 `K2` 打通容器内容同步。
- ⭐ **为 `K` 组铺好了地基**：本轮建立的正是悬浮渲染要用的**全套底层能力**
  （`RenderLevelStageEvent` 接入、相机相对坐标、世界深度/光照处理、距离 + 视锥裁剪）。
  `K` 组此后只需加「模型 + 动画」，不必再趟渲染管线的坑 —— 这正是先做 `L19` 的理由。
- 下一步：`K` 组悬浮渲染（地基已通），或先补 `K2`（容器同步，顺带解锁射线的容器形态）。

### 2026-09-19（第 19 轮）—— 掉落物形态落地（`L-f`）+ 效率附魔复测通过

- ✅ **效率附魔复测通过**（`L46`）—— 五项验证全部收敛。
- ✅ **`L-f` 掉落物形态实装**：三宿主（背包 / 容器 / 掉落物）**功能全部打通**。
- ⭐ **实现思路的关键决策：把掉落物包装成「容器」，而不是另写一套回放逻辑。**

  ```
  ContainerLivingItemHandler.processContext（同一条管线）
        ├── SimpleContainerContext       ← 方块容器 / 玩家背包
        └── ItemEntityContainerContext   ← 掉落物（新增，单栈）
  ```
  之所以能这么做：`processContext` **完全不依赖"方块"**，
  凡 `instanceof SimpleContainerContext` 的分支（红石 / 流体 / 应力 / 相位快照 / tooltip 同步）
  对掉落物**自动跳过** —— 正是想要的结果。

  代价只是实现 `ContainerContext` 的 6 个方法（其余全有 default）。
  → **回放逻辑零改动**，`LivingToolFunction` / `LivingToolReplay` 一行没动
  （只在 `resolveOrigin` 加了一个 `L14=d` 的分支）。
- ⚠️ **踩到并解决：掉落物的物品同步不走容器包**
  `ItemEntity` 的物品走 `SynchedEntityData`（按 `equals` 判重），
  而自定义 DataComponent 的变更不一定被检出 → 必须
  **"先置空再写回"保证标脏**，再 `packDirty()` 广播 `ClientboundSetEntityDataPacket`。
- ⚠️ **有意收窄**：掉落物通道**只处理活工具**，不处理全部活物品 ——
  其它功能类都假定自己有方块坐标（`getBlockPos()`），扔进掉落物上下文会拿到 `null`。
  要扩展需先给 `LivingItemFunction` 加**宿主能力声明**（记为后续话题）。
- ⚠️ **另一项有意的取舍**：不建实体索引，直接遍历 `getAllEntities()`；
  掉落物会移动 / 合并 / 卸载，索引失效成本高于收益。
- 🎉 **至此 `K1`（掉落物也工作）完全落地，三宿主功能闭环。**
- 下一步：`K2`（容器内容 S2C 同步）—— 它一次解锁两件事：
  射线可视化的**容器形态** + `K` 组悬浮渲染的前提。

### 2026-09-19（第 20 轮）—— 修「破坏裂纹永久残留」（`L47`）

- 报告 bug 时我以为是"残留进度组件导致瞬间破坏"，用户指出真实现象是
  **"破坏裂纹会一直都在"** —— 顺着这条线索才挖到真正的根因。
- 根因：原版 `ServerPlayerGameMode#tick()` 有一段「正在挖但方块已变空气 → 清裂纹」的收尾，
  而 **`FakePlayer#tick()` 是空实现** ⇒ 这段永远不跑。
  → **这是 §9.5「FakePlayer 不 tick」的第 4 个受害者**（前三个：`L46`/`L28`/`L27`）。
- ⭐ **问题被两个细节放大**（读 `LevelRenderer` 源码才看清）：
  1. 客户端裂纹是 **10 张固定纹理**，渲染时**不检查那个位置还是不是方块** → 方块没了照样画
  2. 裂纹按 **`breakerId`（实体 id）** 索引，而 FakePlayer 按「维度 + 主人」**共享** ⇒
     同一主人的活工具共用同一个 id，**一个不清就再没人覆盖**，永久残留
- 修法：`replayDig` 的**每一条「停」的路径**统一走 `stopDigging()`：
  无记忆 / 没方块（`L7`）/ 类型不匹配（`L8`）/ **换目标**（`L23`）/ 被取消 / 挖不动（硬度 -1）。
  其中「换目标」那条是新增的 —— 之前从没清过，只是没被发现。
- 顺带修掉写回侧的坑：`held` 是**写完进度之后**才 `copy()` 的副本，
  完成破坏时只清了 `tool` ⇒ 写回槽位的那份**带着残留进度**，
  下一 tick 会误判「已经挖了很久」而瞬间破坏（无裂纹动画）。
  → 已一并 `setToolProgress(held, null)`。
- 教训沉淀：**凡是原版由 `Player`/`LivingEntity` 的 tick 维护、而我们借 FakePlayer 的路径，
  都要逐条核对"这段收尾谁来做"**。

### 2026-09-19（第 21 轮）—— `L20` 修订：手持时始终显示射线

- 用户要求：**玩家手持该活工具时显示它的射线，不管是否 F3+B**。
- 于是 `F3+B` 从"总开关"降级为"**只看非手持宿主**的开关"：

  | 宿主 | 显示条件 |
  |---|---|
  | **玩家手持**（主手 / 副手） | **始终显示** |
  | 背包其余槽位 | 仅 F3+B |
  | 掉落物 | 仅 F3+B |

- 📌 理由：手持正是"玩家正在操作、最需要确认记忆方向对不对"的时刻 ——
  录完一条记忆，抬头一看就能对比「我记下的方向」与「我现在的准星」，不该被开关挡住。
- 实现细节：手持用**引用相等**判定（`Player#getMainHandItem()` 最终就是
  `Inventory#getItem(selected)`，同一个 `ItemStack` 对象），比反查槽位索引可靠。

### 2026-09-19（第 22 轮）—— 修「掉落物射线只指向底面」（`L14=d` 修订）

- 用户实测：掉落物形态的射线**显示在物品碰撞箱的底部**，导致**只挖底面方块**。
- 根因（一个很容易踩的坐标语义坑）：
  `Entity#position()` 返回的是碰撞箱的**底部** ——
  `EntityDimensions#makeBoundingBox` 构造的是 `new AABB(x-f, y, z-f, x+f, y+height, z+f)`，
  其中 `y` 就是**底边**。掉落物落地后这个点**正好贴着脚下方块的上表面**，
  而回放的**逐格扫描从 `t=0` 开始** ⇒ 第一个格子就命中"自己站的那块方块"。
- 修法：改取**碰撞箱中心** `getBoundingBox().getCenter()`（抬高 0.125 格），
  并提取为 `ItemEntityContainerContext#rayOrigin(entity)` 作为**单一真源** ——
  **服务端回放与客户端渲染共用**，杜绝"画的是一条线、挖的是另一条线"。
- 教训沉淀：**`Entity#position()` 是包围盒底部，不是中心。**
  凡"从实体出发"的位置都要先想清楚取哪个点（眼睛 / 中心 / 底部），
  且**两端必须共用同一个取法** —— 否则会出现"可视化对、挖掘错"这种最难查的不一致。

### 2026-09-19（第 23 轮）—— 修「站在半砖上 / 泡在水里不挖」

- 用户实测两个现象：活工具掉在**半砖**上、掉在**水里**，都不会挖掘。
- **两个现象、同一类根因：射线在 `t=0` 就命中了"不该算目标"的东西。**

  **① 半砖 —— 掉落物没有黑名单**

  半砖只有 0.5 格高，物品站上去后**碰撞箱中心（`+0.625`）仍落在半砖方块内部**。
  而"跳过宿主方块"靠的是**黑名单**（`hostBlocks`）——
  容器形态有 `getBlockPos()`，**掉落物的 `getBlockPos()` 是 `null` ⇒ 黑名单是空的**。
  → 射线 `t=0` 命中脚下那块。
  📌 普通满高地面不会暴露这个问题（中心落在上方那格空气里），
  是半砖 / 台阶把"非满高方块"这个边界情况翻了出来。

  **② 水 —— `isEmptyBlock()` 判的是 `isAir()`**

  **水方块不是空气**。于是射线立刻命中水；穿水时也会被水体挡住。

- **修法一**：非方块宿主时，把**起点所在的格子**也当宿主跳过。
- **修法二**：扫描判据从「非空气」升级为「**非空气、且非纯流体**」——
  `isOpenSpace()` = `isAir()` 或（`有流体` **且** `无碰撞箱`）。
  ⚠️ 用「有流体**且**无碰撞箱」而不是「有流体」，是为了**不误伤充水方块**
  （waterlogged 台阶 / 楼梯），它们应当是可挖目标。
- `replayDig` / `replayUse` 共用 `scanForTarget`，一处改动两边生效；
  `L42`（允许挖宿主自己）的判据也一并换成 `isOpenSpace`。

### 2026-09-19（第 24 轮）—— 半砖问题**诊断修正**：判据粒度不一致

- ⚠️ 用户反馈：*"在半砖上，我看射线没有在半砖内部呀"* —— 这句话**推翻了我上一轮的诊断**。
- 重新分析后定位到真正根因：**客户端渲染与服务端判定用了不同粒度的判据**。

  | | 判据 | 结果 |
  |---|---|---|
  | **客户端渲染**（`level.clip`） | 射线 × **方块形状**求交 | 半砖形状只占下半 ⇒ 射线从**上半的空气部分**穿过 ⇒ **穿过** ✅ |
  | **服务端回放**（`scanForTarget`） | 格子是否 `isEmptyBlock` | 半砖那格**不是空气** ⇒ **命中** ❌ |

  ⇒ 表现就是"**画的线是对的，挖的时候却卡在半砖上**"。用户看到的"射线没有在半砖内部"
  完全正确 —— 视觉上起点确实在半砖上方的空气里，只是它在**格子层面**属于半砖那一格。
- **修法**：`scanForTarget` 补上**形状求交**，与客户端 `clip` **同源**：
  ```java
  if (level.getBlockState(current).getShape(level, current).clip(from, to, current) == null) continue;
  ```
- **撤销上一轮的补丁**：「把起点所在格当宿主跳过」是错的修法 ——
  跳过整格会漏掉那一格里真正的目标；形状求交既精确、又与客户端一致。
- ⭐ **教训（最重要的一条，已写进 tech 文档）**：
  **「可视化」与「判定」必须共用同一套判据。**
  客户端用形状级 `clip`、服务端用格子级 `isEmptyBlock`，两者天然分叉 ——
  这类 bug 的表现永远是"看得见、做不出"，且极难猜。
- 水的那一半（`isEmptyBlock` 判 `isAir`、水不是空气）诊断无误，保留原修法。

### 2026-09-19（第 25 轮）—— `K2` 落地：三宿主射线可视化全部打通

- **实装** `LivingToolHostPacket` / `LivingToolHostSync` / `LivingToolHostClientCache`（3 个新文件），
  外加 `LivingToolFunction`（登记宿主）、`LivingItem`（注册 + flush + 清缓存）、
  `LivingToolRayRenderer`（容器形态分支）、`LivingItemClient`（登录清缓存）。
- **方案在讨论中被用户砍掉两处**（都是"不为假想需求设计"）：
  - ❌ **节流间隔** —— 用户问"这是真实需求嘛"。重新分析：内容去重之后本就是按需发送，
    节流唯一的价值是防"状态抖动"，而状态抖动源自我们**自己加的状态字段**。
  - ❌ **状态字段（忙/闲）** —— 顺着用户上一轮"客户端自己推断"的思路再走一步发现：
    **"忙"基本等价于"有破坏裂纹"**，而裂纹是原版广播、零同步 ⇒ 状态根本不用传。
- ⭐ **半径 R=32 不是随便取的**：原版 `destroyBlockProgress` 广播半径就是 32
  （源码 `d² < 1024.0`）。必须一致 —— 否则"看得到活工具却看不到裂纹"，
  而我们的忙/闲推断**依赖裂纹**。顺带与渲染裁剪 `MAX_DISTANCE`（32）天然一致。
- **两个残留防护**：
  - 包里**带维度** → 客户端维度不符返回空（挡住同存档换维度）
  - 客户端**登录时清缓存** → 挡住"退出存档→进另一个存档"（维度可能相同）
- 🎉 **至此三宿主（背包 / 容器 / 掉落物）的射线可视化全部打通**，
  `K` 组悬浮渲染的**数据同步与渲染管线两个前置也都已就位**，只剩「模型 + 动画」。

### 2026-09-20（第 26 轮）—— 悬浮模型 + 动画实装完成（`K` 组）

🎉 **活工具真正"活起来"了** —— 三形态（背包 / 掉落物 / 容器）都会浮现模型：
朝向记忆方向、挖掘时转圈、交互时脉冲一下、干完飞回。实测通过。

**设计（一整套由用户拍板）**：

- **模型**：用**物品原模型**（镐就是镐、斧就是斧），`ItemDisplayContext.FIXED`。
- **位置：一切挂在记忆射线上** —— 待机位 `d = 1.5·L/(L+3)`（**双曲饱和**：越远越远、
  增长越来越慢、严格有上界，射线很长时也不会跑到天边）；交互位 = 射线命中的**表面点**。
- **动画**：待机**不动**、挖掘**风车式转圈**（挖越快转越快）、交互**瞬现 + 缩放脉冲**、干完**飞回**。
- ⭐ **去程「瞬现」**（用户提出）：交互是瞬时的，飞过去必然"方块都挖完了模型还在半路"；
  回程**恒定 8 tick** ⇒ 距离越远自然飞得越快（**自动满足**，不需要速度函数）。
- ⭐ **转圈绕「垂直于射线的水平轴」而非柄轴**：T 形（镐）绕自己柄轴自转 =
  对称图形绕对称轴转，**从侧面看几乎看不出来**；风车式则任何视角都一眼看出。
  由于先把 `+Y` 对齐射线，局部 X 轴正好就是那个轴 ⇒ `pitch` 与自转**同轴可合并**。
- ⚠️ **转圈最快定 4 tick/圈**（不是用户最初说的 1）：60fps 下 1 tick 只有 3 帧，
  1 tick/圈 = 每帧 120°，**远超 Nyquist 极限 ⇒ 会看成倒转/抖动**（车轮效应）。

**同步：只新增 2 个 network-only 组件**

| 组件 | 何时写 | 用途 |
|---|---|---|
| `LIVING_TOOL_DIG_TICKS` | 开始挖新目标时**一次** | 转圈速度 |
| `LIVING_TOOL_LAST_ACTION` | 交互成功时 | 触发脉冲 + 定位瞬现点 |

⭐ **挖掘的「忙/闲 + 挖哪一格」不用新增** —— 发现 `LIVING_TOOL_PROGRESS` **本来就是
`networkSynchronized` 且含目标格子**，客户端直接读即可（顺带把 `K31` 的原方案减了一半）。

**实装中踩到的两个坑**：

- ⚠️ **动画状态同步不到「掉落物」**（用户实测发现）：
  `writeBack` 开头的 `ItemStack.matches` 短路，而 `PatchedDataComponentMap.equals()`
  **检测不到自定义组件的变化** ⇒ 只改 `LIVING_TOOL_PROGRESS` 会被判为"无变化"、直接 return。
  容器靠 `K2` 每 tick 全量同步、背包靠原版 `broadcastChanges` —— **两种侥幸掩盖了问题，只有掉落物暴露**。
  修法：`syncStateFlip` 在 progress / action **翻转**时显式同步。
  📌 教训：**"侥幸能工作"的路径最容易藏 bug**；定位时先问"三种形态的差异在哪"。
- ⚠️ **模型朝向**：把 `+Y` 对齐射线后还要绕射线滚转（实测 **90° + 180°**），抽成常量 `MODEL_ROLL_FIX`。

- 下一步候选：`J` 组容器内 GUI 交互 / `L37` 实体攻击 / 模型手感微调（缩放、飞行曲线）。

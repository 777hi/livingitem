# 项目长期记忆（Living Item Mod）

> 本文件是**工具侧**记忆（只对当前 AI 工具生效）。用户用**多个 AI 工具**开发 ⇒
> **项目级知识必须写在仓库 `docs/` 里**，本文件只保留「红线速记 + 指针」，避免超限被注入截断。
> 📌 文档系统规约见 [`docs/README.md`](../../docs/README.md)；改规约改那份文档。

## 文档系统（定位与红线，2026-09-16）

- ⭐ **文档的主要读者是 AI**（用户原话）—— 第一性原理：写**约束**不写叙述、**只写代码里推不出来的**、
  断言必须**可复算**、**索引与正文分离**、**翻转必须留痕**。
- **人 ↔ AI ↔ 代码 靠文档联系**；目标是「新对话只加载 `AGENTS.md` 就能理解项目」。
- **改代码必须同步文档**；新能力至少要有：技术文档小节 + AGENTS.md 进展条目 + 子系统索引概述。
- 配套入口：术语表 [`glossary.md`](../../docs/glossary.md)（43 条）·
  决策索引 [`decisions.md`](../../docs/decisions.md)（18 条 + supersedes）·
  校验脚本 `tools/doc_check.py`（**改完必跑**，6 项检查）。
- ⚠️ 搬运文档时**搬走不删掉**，且**守恒校验必须逐段判定**（只验第一块曾误删整段）。

## 纹理 / 环境

- 全文见 [`icon-system.md`「纹理约定」](../../docs/system-design/icon-system.md)：
  涂蜡铜灯 = 未涂蜡 + **外圈 60px 黄框 `(232,160,62,255)`**，四锈蚀级掩码**完全一致**；
  改图标**只改内部**，掩码不一致是已发生过的 bug。
- 图片处理用隔离 venv：`C:/Users/AI-777hi/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`（Pillow 12.3.0）。

## 配方书材料表（stackedContents）Mixin

- 重建 `stackedContents` 只有两条路径：`initVisuals()`、`updateStackedContents()`。
  **两条都要注入**（漏 `initVisuals` ⇒「刚开界面材料不识别，动一下才恢复」）；
  其余 `updateCollections` 调用点只复用不重建。
- **内容变化后的刷新靠原版链路，别加轮询/指纹/缓存**（2026-09-05 加过又被移除）：
  服务端改 CONTAINER → `triggerSlotListeners`（`ItemStack.matches`）→ 发包 → 客户端 `Inventory.setItem`
  → `timesChanged++` → 客户端 tick，1–3 tick。前提：`ItemStackMixin` **没动 `matches`**。
- **无状态是这个 Mixin 正确的原因** —— 要引状态字段的优化，先质疑是否必要。
- 详见 [`guides/recipe-book-style.md`](../../docs/guides/recipe-book-style.md)。

## 相位圆盘（v19.2）不变量

- 合因子 = **标量和** `Σ√|Δᵢ|`（`eff_δ_sum`），再取 `(eff_δ_sum)^(1+u)`；
  相位只经 n（去重计数）与调谐效率参与，**不做矢量相加** ⇒ 画**辐条**不画箭头。
- **相位分布不影响收益**（均匀 vs 挤一坨结果相同）；圆盘价值是**诊断**，不是优化目标。
- 相位顺序一律 `PhaseDomain.phasesSorted()`；`deltaByOffset()` 是 HashMap 值视图（哈希序）且**不含 offset**。
- 最佳域按 `period == detectedPeriod` 定位（period 在域集合内唯一，无需 best 标记字段）。

## 电力层（v3 铜块网络）

- `tickContainerData` 按 `(TopoKey, rep, channelIdx)` 组件遍历：**每组件仅锚点（最小铜块槽位 rep）跑一次
  `runBfs`**，其余 `ChannelState.copyFrom`；`accountEnergy` 仍逐机调用。
- **不变量**：`ChannelState.onPhaseEvent(event, pref)` 中 **pref 完全不被使用**（域只按 `event.period()` 分桶）
  ⇒ 同网络多机共享同一 ChannelState 实例安全。
- `ChannelState.copyFrom` 是**深拷贝** —— 改 ChannelState 字段时同步改 copyFrom。
- 跨 tick 持久化：`SimpleContainerContext` 存进 `ContainerLivingItemHandler` 静态缓存（按 containerKey）；
  `FakeContainerContext` **不持久化** ⇒ 驱动多 tick 逻辑必须用 SimpleContainerContext。
- 测试 seam：`ContainerRedstoneData.setEdgeForTest/setPrevEdgeForTest` 注入边信号驱动 `tickContainerData`。

## 相位圆盘渲染（像素对齐）

- 全文（三条规约 + 展开条 / φ=0 / 环厚 / 留白 / 验证工具）见
  [`tooltip-system.md`「相位圆盘渲染像素对齐规约」](../../docs/system-design/tooltip-system.md)。
- 速记：**点块以 (px,py) 为几何中心** · **步数取偶数** · **用 `symRound` 不用 `Math.round`**。
- 改渲染前先跑 `tools/_sim_tooltip.py` 量化 before/after。

## 性能判读：`PerfMetrics` 的覆盖盲区

- `PerfMetrics` 只插桩 `processContext` 内部；**外部 mod 直调我们能力接口**的路径
  （Flux Networks → `ContainerEnergyStorage.receiveEnergy`）**完全不在计时区间内**。
- ⇒ 「PerfMetrics 说只占 3%」与「spark 说 98.79%」**不矛盾**；
  **交叉验证必须看 spark 的绝对毫秒数**。**先问场景，再读火焰图**。
- 全文见 [`living-item-infrastructure.md` §10.1](../../docs/system-design/living-item-infrastructure.md)。

## 容器对外能量接口（ContainerEnergyStorage）热路径

> 详见 [`power-invariants.md` §2.G](../../docs/system-design/power-invariants.md)；以下是红线。

- `receive()`：**只扫一遍 `getStackInSlot`**，比例分配与零头回收走 `stacks[]` 数组。
  `isBulb()` 短路顺序固定 `!isEmpty → isWaxedBulb(Item) → isLivingItem(stack)`（顺序反了白跑组件查询）。
  **保持无状态**（ThreadLocal 暂存池因跨调用状态 + 重入风险否决）。
- **语义红线**（`RoundTripConservationIT` 守着）：整 FE 量化 `accept -= accept % 1000`；
  完整步进保护（`count > leftover` 跳过）；「宁损勿造」。
- **long 溢出红线（真凶）**：mFE 定点制（1 FE = 1000 mFE）+ 每盏 `BULB_UNIT_CAPACITY_MFE = 1e9`
  ⇒ `accept * remaining[i]` 可到 1e23 ≫ Long.MAX。**凡 `a * b / c` 先转 double 再夹 `remaining[i]`**。
  溢出后零头回收 `while` 退化成 ~10 亿轮（服务端冻结）；**27 槽铜灯 > 约 16 盏即触发**。
  同款第二处已修：`LivingWaxedCopperFunction.distributeToBulbs`（后果是**凭空造电**）。
- 外部 mod 会传 `Integer.MAX_VALUE`（Flux「绕过限制」模式）⇒ 必须安全吃下。
  `MAX_LEFTOVER_PASSES = 256` 是防御上限，**别删**。`extract()` 无同类溢出。
- 常量 `BULB_UNIT_CAPACITY_FE = 1_000_000`；`WaxedCopperStorageTest` 里「16 × 100_000」注释已过时。

## 物品 ↔ 世界：独立于传输层的第三条链路

- `SlotAccessor`/`SlotInteractions` 只管**容器内**；**物品 ↔ 世界**入口是 Mixin，别往传输层挂。
- **优先调原版入口方法**：模拟玩家右键就调 `ItemStack.useOn`，**别自己 `setBlock`**
  （会静默绕过模组在 `canSurvive`/覆写 `useOn` 里的校验）。
- **读物品栈必须锚在 `consume` 之前**（`@At("RETURN")` 时单块放置已 count=0、`getComponents()` 返 `EMPTY`
  ⇒ 单块静默失效、堆叠却正常）。**软逻辑必须 try/catch**（否则「方块已放、物品未扣」+ 炸 tick）。
- `Level.isClientSide` 是 **`public final` 字段** ⇒ Mockito 不可 mock，单测统一用**方法式**。
- **单测 JVM 里 Mixin 是被应用的** ⇒ 可写端到端用例证明接线（不必等游戏启动）。
- 详见 [`infrastructure` §9.8](../../docs/system-design/living-item-infrastructure.md) /
  [`farmland-tech` §3.5 / §11.16](../../docs/tech/living-farmland-tech.md)。

## 槽位叠加层：优先走 `IItemDecorator`

- **能进 `IItemDecorator` 就别写 `AbstractContainerScreenMixin`** —— 后者依赖 `leftPos/topPos`，而
  **HUD 快捷栏走 `Gui.renderHotbar` → `renderItemDecorations`，从不经过 `AbstractContainerScreen`**。
- 三契约：① 坐标 =「当前 pose 内 `translate(xOffset, yOffset, z)`」（容器传 `slot.x`，**未加** `leftPos`）；
  ② 容器内 z 有 `+100` 基准 ⇒ z=200 → 净 300；③ `ItemDecoratorHandler` 会 `resetRenderState()`（开深度）
  ⇒ **别写 `RenderSystem`**。
- 拿不到容器槽表的能力（如「上方一格生长槽大图」）留在 Mixin。
- 详见 [`icon-system.md`](../../docs/system-design/icon-system.md)。

## 跨容器传输：方向对称性必须逐条点名验证

- `pushToNeighbor` 与 `pullFromNeighbor` 是**两份独立实现**；「一处内嵌两路径共用」**不覆盖拉取方向**。
- **`SlotAccessorFactory.create` 把非箱类活物品挡掉（null）**，`createForNeighbor` 不查
  ⇒ **拉取方向**通用传输对活物品目标槽**必然失败**，特殊能力必须显式前置分支。
- **货物准入唯一定义点 = `SlotInteractions.isEligibleCargo`**（`!isLivingItem || 活箱子 || 活末影箱`）；
  规则收在共享入口，**调用点顺序变更绕不过它**。**活骨粉不给漏斗施肥**（属**传输语义**）。
- **判定「某能力是否覆盖」不能靠结构推断**：要么 grep 到三处分支，要么写方向化单测。
- 详见 [`living-hopper-tech.md` §6.2.1 / §6.2.2](../../docs/tech/living-hopper-tech.md)。

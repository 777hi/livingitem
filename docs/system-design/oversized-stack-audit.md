# 超大堆叠表现审计（活物品 × 模组容器堆叠上限 > 64）

> **状态**: 定稿（2026-09-08 增补：活箱子取消活化缺陷 #6，原版背包可触达）
> **日期**: 2026-09-07
> **方法**: 全库扫描 `getCount()` / `getMaxStackSize()` / `getSlotLimit()` / `grow` / `shrink` / `setCount`
> 共 202 处调用点，逐领域源码分析 + 原版语义对照（NeoForge 21.1.230 merged source）。

---

## 0. 结论速览

| 评级 | 数量 | 含义 |
|------|------|------|
| ✅ 设计内缩放 | 14 | 数量作为强度参数，超大堆叠 = 更强/更快，符合「堆叠涌现」设计 |
| 🟡 行为偏离（无害） | 3 | 与原版/直觉有出入但不崩不丢，可接受或后续打磨 |
| 🟠 潜在溢出（理论） | 2 | int 算术在极端超大堆叠下溢出，正常模组容器（≤几万）不会触发 |
| 🔴 真实缺陷 | 1 | 模组容器超大堆叠作为燃料/被消耗时物品凭空消失 |

> **2026-09-08 审计外发现**：活箱子「堆叠 N 份内容」不变量在**取消活化掉落**路径上
> 同样被违反（只返还 1/N）——但触发条件是**内容相同的活箱子堆叠**（原版背包即可，
> 不需要模组超大容器），属审计范围外的原版堆叠场景，已当日修复
> （`collectDeactivationDrops` 按 count 份计算 + 6 项回归测试，
> 详见 [living-chest-tech.md §8.4](../tech/living-chest-tech.md)）。

**关键架构事实**：活物品本体存放在原版容器（箱子/背包，上限 64）中运行；
模组容器的超大堆叠只通过两条路径影响活物品——
① 活漏斗/活末影箱**跨容器读写邻居模组容器**；
② 活物品被放进模组容器当宿主（`ContainerChunkCache` 会发现它）。
本模组**自身**的虚拟存储（活箱子/活末影箱）槽位上限是硬编码 64，不受影响。

---

## 1. 基础设施层 —— ✅ 干净

所有传输/读写入口统一取 `Math.min(slotLimit, maxStackSize)`，尊重模组容器自己的上限：

| 位置 | 行为 |
|------|------|
| `PlainSlotAccessor.insert/simulateInsert` | `min(slotLimit, maxStackSize)`，抽屉 slotLimit=1024 时能塞满 1024 |
| `NeighborSlotAccessor` | 直接委托邻居 `IItemHandler`（抽屉等自己实现），无假设 |
| `SimpleContainerContext.getSlotLimit` | 透传 `handler.getSlotLimit(slot)` |
| `SimpleContainerContext.setItem` | 抽干再插，插入量 = handler 自报；插不进记 WARN 不吞物品 |
| `SimpleContainerContext.simulateInsertItem` | Container 分支同样取 min；IItemHandler 分支直接模拟 |
| `SlotAccessor.transfer`（统一传输） | 模拟优先 + rollback 兜底，数量来自两侧 Accessor 真实上报 |

> 结论：物品进出的**通道层**对超大堆叠完全透明，不丢不崩，吞吐按模组容器实际上限走。

---

## 2. 逐领域分析

### 2.1 活熔炉 LivingFurnace —— 🔴 燃料消耗不感知超大堆叠（真实缺陷）

- **燃料点燃**（`consumeFuel`，本次刚修）：带残留物的燃料**整槽替换为残留物**（对齐原版）。
  岩浆桶在模组容器里若被 mod 超堆叠（×N），点燃后 N−1 个岩浆桶凭空消失。
  原版同样吞，但原版容器里根本攒不出岩浆桶堆——模组容器（如抽屉）攒得出，问题就显形了。
- **燃料 tick 加速**：`fuel.tick(stackCount)`，燃烧时长 = 20000/count——**设计内**。
- **进度加速**：`step = 1 + count/8`，clamp 在 `advanceBy` 内 `min(total, progress+step)`——安全。
- **产出空间**：`calculateOutputSpace` 用 `min(slotLimit, maxStackSize)`——对超大堆叠**输出槽**
  （若输出槽是模组容器跨容器目标）正确扩容，`canAcceptOutput` 同口径。
- **单次产出上限**：`transformCount = min(stackCount, inputCount, maxByOutput)`——count 大时
  受输入数量约束，不溢出。

**修复方向**（备选，未实施）：带残留物的燃料若 `getCount() > 1`，改为 `shrink(1)` + 残留物
找空位插入，只在 count==1 时整槽替换——这是对原版的**有意偏离**，理由是活熔炉的宿主
容器可能是模组容器，原版语义在这里会造成真实物品损失。待用户决策。

### 2.2 活TNT —— 🟠 int 累加溢出（理论）

- `ExplosionComponent.ignite`：`totalTntCount += stack.getCount()`（int），半径 = `baseRadius × √count`。
- **溢出条件**：单容器活 TNT 总数 > ~21 亿。抽屉类容器理论上可达（但活 TNT 需逐个活化，
  `/give` 超堆叠或模组管道批量活化才可能凑到）。
- **表现**：溢出为负 → `totalTntCount <= 0` → 拒绝引爆（安全失败，不爆炸不崩）；
  或绕过 `<=64`/`SUPER_EXPLOSION_THRESHOLD` 分层导致半径计算异常。
- **评级 🟠 而非 🔴**：失败模式是「不炸」（fail-safe），不是刷物品或崩溃。
- √缩放 + 64/大当量阈值：**设计内**。

### 2.3 活漏斗 —— ✅ 设计内（吞吐固定 64/t 次）

- `transferAmount = min(stackSize, DEFAULT_MAX_TRANSFER=64)`：漏斗堆叠再多，单次传输上限 64。
- 冷却加速：`cooldown = max(1, 8 − count/8)` tick——设计内，超大堆叠时冷却贴地 1 tick，
  等效吞吐 = 64 × 20/tick = **1280 物品/秒/只漏斗**，性能可控。
- 插入端尊重 `min(slotLimit, maxStackSize)`（见基础设施层）。
- 过滤模式 `normalizeMode(count)` 用堆叠数做幂等键，超大值安全（switch 语义）。
- **注意**：目标为模组超大槽位时，单次仍只填 64，填满一个 1024 抽屉需 16 次传输——
  行为正确但**吞吐不随目标容量缩放**，列为 🟡 观察项（模组玩家可能觉得「抽屉怎么吸得慢」）。

### 2.4 活箱子 —— ✅ 虚拟存储硬编码 64，不受外部影响

- `LivingChestItemHandler.getSlotLimit` = 硬编码 64；内部槽位读写全部
  `min(slot.getMaxStackSize() − count, ...)`。
- `chestStack.getCount() > 1` 时全部操作返回空/拒绝——活箱子堆叠 > 1 = 关闭存取，
  防止一个物品 ID 多份虚拟存储。超大堆叠下语义稳定（锁定），无风险。
- 磁盘持久化 `MAX_STORAGE_BYTES=16384` 与堆叠数无关。
- **取消活化掉落**（2026-09-08 修复）：`dropAllItems` 曾只掉一份 `CONTAINER` 内容，
  堆叠 N 份只返还 1/N。该不变量违反在**原版堆叠**（两个内容相同的活箱子，
  上限 64 内）即可触达，非本审计「模组超大容器」场景；已改
  `collectDeactivationDrops` 按 `count × 每槽数量` 计算、超堆叠上限拆满堆返还
  （`LivingChestFunctionTest` 6 项回归）。

### 2.5 活末影箱 —— ✅ 堆叠数即路由模式开关

- `EnderChannelKey(owner, count)`：count==1 直连玩家末影箱；count>=2 私有频道。
  超大堆叠（如 count=999）落 `isPrivateChannel`，路由表按 key 匹配——**数值本身不参与
  运算**，只是身份键，任意大小安全。
- 路由表条目变化触发 `validateRoutes`，count 突变只切模式，无算术。

### 2.6 活红石 —— ✅ 信号上限 = count²，边界饱和 15

- `getSignalCap(count) = count==1 ? 15 : count²`：64 堆 = 4096 内部信号——**设计内**
  （红电高频档位的基础）。
- **边界出口**（`BlockStateBaseMixin.getSignal`）：`Math.max(vanillaSignal, boundarySignal)`
  **未 clamp 到 15**，4096 直接注入原版 `getSignal` 返回值。
- **为什么不会崩**：原版消费端自带钳制——`SignalGetter.getBestNeighborSignal` 遇
  `>= 15` 立即 `return 15`；`RedStoneWireBlock.POWER` 属性 IntegerProperty[0,15]；
  `Math.max` 链天然饱和。故 4096 在原版侧表现为「满信号 15」，**等效设计内**。
- 内部 `edgeGrid` 是 int 数组，4096 << Integer.MAX_VALUE，无溢出。
- 比较器输出 `getSignalCap(comparatorStack.getCount())`（:792）经 `min(maxInput−1, cap)` 同样安全。

### 2.7 活水桶/活水车 —— ✅ 设计内

- 水流推动物品（`ContainerFluidData:227`）：`space = maxStackSize − count`，尊重原版
  物品自身上限（水流目标槽是活物品所在原版容器），正确。
- 水车应力：`weightedTorque = round(torque × strength × stackSize)`，`netCW/netCCWStress`
  int 累加。溢出需 torque×count > 21 亿——count > 5 亿才可能，现实中抽屉堆不到，
  且 Create 侧消费有自己上限。**✅ 无实际风险**（若要绝对稳妥可改 long，非必要）。

### 2.8 红电发电（涂蜡铜） —— ✅ long 定点，量级安全

- 铜灯电量：`LivingWaxedBulbData.chargeMilliFe` 是 **long**，每盏 1,000,000 FE = 1,000,000,000 mFE
  （2026-09-09 标定 1M，标定史 1k→10k→1M，见 living-power-tech.md §4）；
  `BULB_UNIT_CAPACITY_MFE × count` 全程 long（`receiveEnergy:63`），64 堆满电
  64,000,000,000 mFE << long 上限。✅
- **🟢 int 收窄点（2026-09-09 已修复：clamp）**：
  `getEnergyStored/getMaxEnergyStored` 返回 `(int)(mFE × count / 1000)`——
  `IEnergyStorage` 接口本身是 int FE（上限 21.4 亿 FE）。溢出需
  count × 1,000,000 FE > 21 亿 FE，即 **count ≥ 2,148 盏**（2³¹/1M = 2,147.48）——
  原版上限 64 安全（一堆 64M FE），超大堆叠容器（抽屉类，单槽上限可达数千）可触达。
  修复：两处读数出口（`ContainerEnergyStorage` 容器面 + `BulbItemEnergyStorage`
  物品面）`Math.min(真值, Integer.MAX_VALUE)` clamp——语义「至少 21.4 亿 FE」，
  杜绝回绕负数导致外部 mod 容量缺口判定错乱；内部 long 账本全程无损。
  回归测试 `BulbItemEnergyStorageTest.oversizedStack_readsClampToIntMax`
  （2,148 盏满堆断言双读数 = Integer.MAX_VALUE）。
- 发电数学 `PowerMath.combinedFactor`：`effDeltaSum^(1+u)` 是 double，count 只进
  `n`（分组计数的组内个数），超大 n 使 factor 大——但 `eventEnergyRe` 用 long 承接，
  EMA 定点 long，账本层安全。
- `GeneratorState.preferredPeriod = min(64, count)`：主动 clamp，超大堆叠下周期
  封顶 64——**设计内**（周期不受堆叠无限增长）。

### 2.9 活地图传送 —— ✅ 距离显示平方，消耗精确

- 传送半径 `128 × count²`（tooltip 展示 + 实际判定口径一致），double 乘法无溢出。
- 珍珠消耗 `consumeFromInventory`：`min(remaining, stack.getCount())` 逐堆精确扣费，
  超大堆叠堆能一次付清 16 颗——行为优于原版背包，正确。
- `countInInventory` int 累加同 TNT（理论溢出，fail-safe 拒绝传送）。

### 2.10 同步/持久化层 —— ✅ 无 64 假设

- 网络包（`ServerPacketHandler` 活箱子存取）：金额计算全部
  `Math.min(amount, target.getMaxStackSize())`——按物品自身上限，不硬编码 64。
- `ClientboundContainerSetSlotPacket` 传 ItemStack 副本，count 编码 VAR_INT，无上限假设。
- DataComponent 序列化（CONTAINER/FURNACE_DATA 等）：count 由 ItemStack 自身 codec 处理。
- 磁盘持久化（活箱子 UUID 文件）：与堆叠无关。
- `ContainerMonitor`（物品复制/丢失检测）：`droppedCount += item.getCount()` 只是日志统计。

### 2.11 客户端 —— ✅

- `RecipeBookComponentMixin:476`：`invStack.getCount() > 1 → continue`——配方书只把
  count==1 的活箱子当有效容器，超大堆叠的活箱子被跳过（此时存取本来就关闭），一致。
- `carried.getCount()` 光标数量逻辑按实际值计算，无 64 假设。

---

## 3. 风险清单（按优先级）

| # | 位置 | 评级 | 问题 | 触发条件 | 建议 |
|---|------|------|------|----------|------|
| 1 | `LivingFurnaceFunction.consumeFuel` | 🔴 | 超堆叠岩浆桶点燃 → N−1 个凭空消失 | 模组容器超堆叠桶类燃料 | count>1 时 shrink(1)+残留找位插入（有意偏离原版，防真实物品损失） |
| 2 | `BulbItemEnergyStorage.getEnergyStored/getMaxEnergyStored` | 🟠 | int FE 收窄溢出（返回负数） | count > 214 万盏 | 保持现状；若未来放开堆叠上限，先改 clamp 或 long 化 |
| 3 | `ExplosionComponent.ignite` / `countInInventory` | 🟠 | int 累加溢出 | 总数 > 21 亿 | fail-safe 拒绝执行，可不动 |
| 4 | 漏斗吞吐 `DEFAULT_MAX_TRANSFER=64` | 🟡 | 抽屉类目标容器填充慢 | 目标 slotLimit >> 64 | 观察；可考虑 transferAmount = max(64, 目标 slotLimit) 之类的自适应 |
| 5 | 熔炉 `canAcceptOutput` 用 `<` 判满 | 🟡 | slotLimit 巨大时第 N 件产物要等下一 tick | 输出槽是超大容量容器 | 语义正确（保守），每 tick 重新检查，无损失 |

---

## 4. 设计内缩放行为汇总（超大堆叠 = 更强）

| 活物品 | 缩放公式 | 64 堆数值 | 上限保护 |
|--------|----------|-----------|----------|
| 活TNT | 半径 ∝ √count | 8× 基础半径 | 大当量模式分档 |
| 活熔炉 | 进度 1+count/8，燃料 count 倍耗 | 9× 速度 / 64× 燃耗 | advanceBy min(total,·) |
| 活漏斗 | 冷却 max(1, 8−count/8) | 1 tick 冷却 | 吞吐仍限 64/次 |
| 活红石 | 信号上限 = count²（1 堆=15） | 4096 | 原版消费端饱和 15 |
| 活水车 | 力矩 × count | 64× 应力 | — |
| 活地图 | 传送半径 128×count² | 52 万格 | double |
| 红电发电 | 合因子 n^(1+解锁度)，周期 min(64,count) | — | 周期 clamp、账本 long |
| 铜灯 | 容量 = count × 1000 FE | 64,000 FE | mFE 定点 long |
| 活箱子/末影箱 | count>1 = 关闭存取 / 切路由模式 | — | 语义开关，无数值运算 |

---

## 5. 探查中已验证的关键源码事实

- `Item.getCraftingRemainingItem()`/`hasCraftingRemainingItem` 为空堆返回
  `Items.AIR`/false（ItemStack.isEmpty 三条件：`==EMPTY || item==AIR || count<=0`）。
- 原版 `AbstractFurnaceBlockEntity#serverTick` 点燃分支：先判 `hasCraftingRemainingItem()`
  整槽替换，再 `shrink(1)`；**超堆叠桶在原版同样被吞**（原版仅 stacksTo(1) 使该路径不可达）。
- `SignalGetter.getBestNeighborSignal`：`j >= 15 → return 15` 早退钳制；
  `RedStoneWireBlock` POWER 为 IntegerProperty[0,15]。
- `SimpleContainerContext.setItem` = 抽干（`extractItem(MAX_VALUE)`）+ 重插 +
  盔甲槽特判 + 剩余 WARN——不吞物品。
- `SlotAccessor.transfer` 模拟优先：`simulateExtract → simulateInsert → extract → insert`，
  不一致时 rollback + WARN 日志。
- `LivingChestItemHandler.getSlotLimit` 硬编码 64（虚拟存储不受模组容器影响）。
- `LivingWaxedBulbData.chargeMilliFe` 为 long（mFE 定点），全链路仅对外接口处收窄 int。

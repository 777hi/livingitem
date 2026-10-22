<!-- markdownlint-disable -->

# 活背包（精妙特化的活物品功能）

> **状态：已实施（2026-10-22，待真机验证）** —— 由「物品形态的背包里流体不渲染」引出。
> **定位**：这不是通用基础设施，而是**一个活物品功能**（精妙背包特化）；
> 它与 [活潜影箱实现细节](活潜影箱实现细节.md) 共享 UUID + SavedData 地基，
> 但驱动力完全复用现有 tick 管线 —— **框架零改动**（除 §3.4 的一处持久化钩子）。
>
> ⚠️ 本稿前两轮（viewer 驱动 / 框架扫描）**全部作废**，纠偏过程见 §5。
> 按早期结论动手会白做三轮。
>
> **落地分四步**（提交 d452997 → 7a3724d → 38bb544 → 本步）：
> ① persist 钩子 + key 加 CODEC；② UUID 组件/附件 + cuuid_ 键形 + SavedData；
> ③ 身份优先的形态迁移 + SavedData 回填；④ LivingBackpackFunction + 精妙联动。

---

## 0. 原点检查（铁律：新增机制前先找对应物）

| 问题 | 答案 |
|---|---|
| 这件事在现有架构里的对应物是什么？ | **活物品功能**（`LivingItemFunction`：`canApply` + `tick`）。活背包 = 精妙背包物品 + `IS_LIVING`，和一个活熔炉没有本质区别 |
| 如果不用新机制，现有抽象缺什么？ | 只缺两样：① 物品形态容器的**内容 handler**（没有 `IItemHandler` 能力面）；② 容器级数据的**持久身份**（位置键在物品形态不存在）|
| 答不上来就退回 —— 退了吗？ | 退了。前两轮没答这两问，直接设计了 viewer 驱动与框架扫描，§5 是学费 |

---

## 1. 问题

**现象**：精妙背包拿在手上打开 GUI（或潜影盒打开 GUI），里面流体层不渲染。

**根因不是数据丢失** —— 数据在物品组件上（2026-10-21 双向交接已保证跨形态存活）。
根因是**这个东西根本不活**：没有人在 tick 它，所以没有 `ctx`、没有容器级数据、
渲染/tooltip/倒汲/演化全线没有输入。

⇒ 所以正确的问题不是「怎么让渲染链路拿到数据」，而是「**怎么让活背包活起来**」。
渲染只是症状之一。

**这不是精妙独有**：原版潜影盒、旅行者背包、任何 mod 背包同样缺。
本文先做精妙（用户整合包里的实际场景），通用版等活潜影箱。

---

## 2. 形状：一个活物品功能

```
LivingBackpackFunction extends LivingItemFunction
  canApply(stack) → 精妙背包物品 && IS_LIVING（活化）

  tick(entries, ctx, tick) →
      for (entry : entries) {                    // 宿主容器里的每个活背包
          1. entry.stack 读/建 UUID（懒创建，见 §3.2）
          2. 构建内部 ctx（cuuid 键 + 内容 handler）  ← 联动模块提供，见 §3.1
          3. processContext(innerCtx)             ← 复用！里面活熔炉/活桶照常 tick
          4. 容器级数据标 dirty
      }
```

**框架零改动。** `scanAndGroupLivingItems` 已经把它当活物品扫出来、按功能分组、调 `tick`
—— 和活熔炉、活漏斗走同一条路。不需要 tag 判定、不需要新扫描器、不需要生命周期登记。

⚠️ **更正（2026-10-22 晚，用户拍板 B）**：上一轮说「框架零改动」**说漏了一处** ——
容器级数据的**持久化路径**。物品形态 ctx 拿不到 `IAttachmentHolder`（物品栈不是附件持有者）
⇒ `getDataHosts()` 为空 ⇒ `ContainerFluidWriteback` 的归档循环一次都不跑 ⇒ 数据只在内存表，
LRU 一回收就丢，SavedData 永远空。修法见 §3.4。
⇒ 准确表述：**tick 管线零改动，`ContainerContext` 增加一个持久化钩子。**

**活化门槛顺手解决三件事**：

| 原顾虑 | 怎么解决的 |
|---|---|
| 性能（100 个玩家各带 5 个背包） | 没活化的背包不进 `grouped`。只有玩家主动活化的才 tick |
| 判定（怎么认出容器物品） | `isLivingItem(stack)` 已覆盖 |
| `D-container-08` 的玩法顾虑（泛化稀释活潜影箱卖点） | 不成立 —— 要活化才动，不是所有背包物品都在跑 |

这正是活潜影箱设计稿 §2.3 的形状（「功能内部递归，完全复用现有逻辑」）——
本稿是它的**精妙特化实现**，不是另一条路。

---

## 3. 真正要做的三件

### 3.1 内部 ctx：内容 handler 从哪来

物品形态容器**没有 `IItemHandler` 能力面**（能力面是容器级的，物品栈没有）。
但 GUI 打开时精妙自己构造了 `BackpackInventoryHandler`，而它是**标准 `IItemHandler` 实现**
⇒ 我们不需要懂它 SavedData 的格式，只需要拿到那个 handler。

**联动模块提供**（`living/compat/sophisticated/`，与 `SophisticatedMenuResolver` 同构）：

```java
public interface BackpackContentAccess {
    /** 从背包物品栈取内容 handler；取不到返回 null（未升级/未初始化） */
    @Nullable IItemHandler handlerOf(ItemStack backpackStack);
}
```

由 `ModSophisticated` 注册，`LivingBackpackFunction` 只依赖这个接口 ——
核心不认识精妙类型（`D-container-09`）。

⚠️ **成本**：每次 tick 都要拿 handler。精妙的 `BackpackWrapper.fromStack` 有内部缓存，
但我们要量一下真实开销；太贵就加「stack → handler」的弱缓存（stack 变化即失效）。

### 3.2 UUID + SavedData：容器级数据的持久身份

位置键在物品形态不存在 ⇒ 容器级数据需要**跨形态、跨重启**的身份。

三层（结论已定案：**组件里只留 UUID，数据进 SavedData**）：

```
身份   UUID            BE 附件（方块）/ 物品组件（物品）   ← 唯一的跨形态信物
数据   SavedData       按 UUID 索引，全局一份             ← 唯一的跨形态真相
缓存   CONTAINER_DATA  内存表，按 cuuid 键               ← 热数据，LRU 可弃
```

已从精妙 `BackpackStorage` 借来并定案的：
- **懒创建** UUID（第一次真正需要才生成）⇒ 老存档无需迁移命令
- **复制必须 remove UUID**（`cloneBackpack` 范式）⇒ 复制品数据独立，升为强制范式
- **加载时丢弃空条目** ⇒ 存档瘦身
- Overworld DataStorage 全局一份、`computeIfAbsent` + `setDirty`（MC 自动保存周期落盘）

**UUID 的搬运复用 2026-10-21 的 `ContainerHandoff`** —— 职责从「搬一整份数据」缩成
「搬一个 UUID」。昨天修的拾取/放置/破坏三条路径正好覆盖 UUID 的流转。

### 3.3 渲染：viewer 匹配简化

内部 ctx 带 cuuid 键 ⇒ `FluidFlowServerSync.flushAfterTick` 直接复用。
剩下的只是「玩家正看着的菜单对应哪个 cuuid」：

- 联动模块从 `BackpackContainer` 反查（它持有 handler → UUID），注册进 `MenuSlotOwners`
- 一次解析，**不需要** Open/Close 生命周期登记

### 3.4 持久化路径：`ContainerContext` 加 `persist` 钩子（动框架，已拍板 B）

**问题**：物品形态 ctx 没有 `IAttachmentHolder` ⇒ `getDataHosts()` 返回空 ⇒
`ContainerFluidWriteback.onWriteback` 的归档循环不跑 ⇒ 数据只在内存表，LRU 回收即丢，
SavedData 永远空。**UUID + SavedData 是档案柜，但没人往里放东西。**

**方案 B（2026-10-22 用户拍板）**：把“数据存哪”收成 ctx 的职责。

```java
// L1 ContainerContext —— 与 getDataHosts() 并列的 default 方法
/** 把某个容器级数据 key 的值归档到本宿主的持久层。 */
default <T> void persist(ContainerDataKey<T> key, T value) {
    for (var host : getDataHosts()) host.setData(key.attachment(), value);   // 现状：BE / Player 附件
}

// 物品形态 ctx 覆写
@Override
public <T> void persist(ContainerDataKey<T> key, T value) {
    savedData.put(uuid, key, value);     // 空值 ⇒ remove
}
```

**领域侧从 3 行循环变 1 行调用**：

```java
// 原来（水领域）
for (var host : ctx.getDataHosts()) host.setData(FLUID_DATA.value(), fluidData);
// 之后
ctx.persist(ContainerFluidData.KEY, fluidData);
```

**为什么不是 A（每个领域各自判空 hosts）**：A 在活潜影箱立项时要再抄 N 遍
（每个领域的 writeback 都判一次空 hosts），且“存哪”的知识泄漏进领域。
B 是新增形态零框架改动（对扩展开放、对修改关闭）——用户 2026-10-22 拍板。

**SavedData 粒度**：按 UUID 一条**聚合记录**（`Map<UUID, ContainerDataSnapshot>`，
snapshot 含所有非空 key 的值）—— 一个 UUID 一次序列化，与 `ContainerDataStore` 结构对应。

⚠️ **两个待设计细节**（2026-10-22 晚查代码后**收窄为一个**）：
1. ~~`ContainerDataKey` 不带 CODEC 句柄~~ ⇒ 聚合 snapshot 无法把“任意 key 的值”编进 NBT。
   **要加，但与现有 `attachment` 完全同构**（新增 `savedWith` 工厂 + `codec()` getter）。
2. ~~`attachment()` 不存在~~ ⇒ **已存在**。`ContainerDataKey.attachment()` 是现成 getter，
   水领域已在用 `persistentWith("fluid", ..., CONTAINER_FLUID_DATA)` 声明附件。
   ⇒ **BE 路径的 default 实现现在就能写，零额外框架改动**：

   ```java
   default <T> void persist(ContainerDataKey<T> key, T value) {
       AttachmentType<T> att = key.attachment();
       if (att == null) return;                       // 不落盘的 key（应力/红石）
       for (var host : getDataHosts()) host.setData(att, value);
   }
   ```

⇒ 语义天然对齐：`persistent`（无附件）= 不落盘、`persistentWith`（有附件）= 落盘
⇒ SavedData 只收后者，与 I-C9「只有带 `serialize` 的才搬」同一条判据。

**契约后果**（已写进 [container-boundary.md](../system-design/container-boundary.md) I-C9）：
> 每种宿主的持久化方式由 ctx 自己声明，**领域不得假设有 BE**。
> 拿不到 `IAttachmentHolder` 不是异常，是一种正常宿主（物品形态，持久层是 SavedData）。

### 3.5 真机首测（2026-10-22 深夜）：能 tick，但只接了 6/11 条线

**结果**：活背包内部的活物品**确实开始 tick 了**（第一步成立），但：
- **活漏斗设置的传输槽位与实际传输的槽位对不上**
- **活漏斗高级 tooltip 不显示容器信息**

**根因不是槽位算错，是「两条路径分叉」**：

| 路径 | 走什么 | 状态 |
|---|---|---|
| tick | `LivingBackpackFunction` 自建 `ItemFormContainerContext` → `processContext` | ✅ 工作 |
| 菜单/交互 | `ContainerContexts.resolve(player, slot)` | ❌ **完全没接**（搜 `ItemForm`/`cuuid` 零命中） |

⇒ 设置槽位时解析不出容器（回落默认），tick 时用的是另一个 ctx（`cuuid_<uuid>`）——
**两套映射**。tooltip 同链：`ContainerRuntimeCache` 按槽位找 ctx，找不到就没信息。

**教训**：一个容器形态要接 **11 条线**才叫「正常工作」，不是「能 tick」就算完。
清单见 [guides/container-compatibility.md](../guides/container-compatibility.md) §新容器形态接入清单，
活背包当前勾选：✅1 ✅2 ⚠️3 ✅4 ✅5 **✅6 ✅7**（2026-10-22 补上）⚠️8 ✅9 ❌10 ✅11。

**#6 / #7 已补上**（同日）：
- **#6 菜单解析**：`ItemFormContainers.Source.handleOfMenu` 默认实现 = 扫玩家物品栏找
  「本来源认领且带 UUID」的物品，**唯一一件才认、多件返回 null**（菜单不告诉你对应第几件，
  猜错会操作到另一个容器 ⇒ 宁可不认）。`ContainerContexts.resolve` 末尾加该分支。
- **#7 viewer 匹配**：`FluidFlowServerSync.viewersOf` 加「按容器 UUID 反查在线玩家」分支
  （物品形态没有位置，原按 positions 匹配会直接返回空）。

**#10 世界语义明确不做**（用户 2026-10-22 定）：`getBlockPos()` 返回 null 是正确语义，
活漏斗的方向传输属于**活潜影箱**的逻辑，活背包不背。

⚠️ **不做「persist 保护」**（用户 2026-10-22 否决）：曾提议「旧值非空→新值变空时拒绝写入」，
被否 —— **它保护不了任何值得保护的东西**（真问题在槽位，不在写入），
而且这类守卫的存在理由会随上下文消失，留下一个没人敢删的分支。
**如无必要，勿增逻辑**；调试期毁档就毁档（alpha 阶段不做旧存档兼容）。

---

## 4. 与活潜影箱的关系
| | 活背包（本文） | 活潜影箱（C） |
|---|---|---|
| 身份/持久化 | UUID + SavedData | **同一套地基** |
| 驱动力 | 宿主容器 tick → 功能内部递归 | 同 |
| 容器物品范围 | 精妙背包（特化） | 潜影盒 / 通用（含无人观看） |
| 边界交换 | 不需要（单层） | `BoundaryExchanger` 三种穿透 |
| 嵌套治理 | 暂不做（见 §5） | 深度预算 + 环检测 |
| 2D↔3D 桥接 | 无 | 有 |

⇒ 本文交付的地基（UUID 组件 / SavedData / 按 UUID 索引 / handler 注册口）C 直接复用；
C 额外做边界交换与嵌套。

---

## 5. 方案是怎么被纠偏回来的（留档，比最终方案值钱）

四轮，每轮单看都“合理”，合起来是离原点越来越远：

| 轮 | 我的方案 | 错在哪 | 用户点醒句 |
|---|---|---|---|
| 1 | **viewer 驱动**：Open 回填 / Close 写回 / 活跃期注册表 | 把「渲染拿不到数据」当成本问题，设计了一整套生命周期 | 「viewer 驱动，这是我没有想到的一点」（肯定了我没做的部分，也暴露我在设计驱动） |
| 2 | **框架级扫描**：每 tick 遍历在线玩家背包 | 这就是 `D-container-08` 否掉的扫描展开，我还引它给自己背书 | 「为什么要遍历玩家背包呢」 |
| 3 | **宿主容器 tick 时加判定**：在框架循环里加 `is(tag)` 分支 | 仍是在框架层特殊化，且判定的必要性没论证 | 「这个检查有什么用」 |
| 4 | **活背包 = 一个活物品功能** | ✅ 框架零改动，复用 `LivingItemFunction` | 「活背包也是活物品，为什么要特殊呢」 |

**教训（已固化为 AGENTS.md 设计铁律）**：

> 症状驱动 + 增量补丁，会系统性地把人带离核心抽象。
> 方案里每出现一层新机制，必须能回答「现有抽象为什么不够」。
> 我引用了 `D-container-08` 却只取「否掉扫描展开」半句，漏了「活潜影箱的专属逻辑」
> 那半句 —— **而那半句正是答案**。引用过的文档要回头读原文。

---

## 6. 已定案 / 未定案

**已定案（2026-10-22）**：
| 项 | 结论 |
|---|---|
| 键形前缀 | `cuuid_<uuid>`（不撞 `container_<dim>_x_y_z`） |
| 嵌套 | 现在就实现 depth + 环检测（UUID 栈），**上限写死 1**，等 C 立项只改值 |
| linked storage 多人同看 | 接受并文档写清（识别它是精妙私有概念，核心不碰） |
| 关着 GUI 也 tick | **烧**（活化门槛已限制数量） |
| handler 成本 | 先不缓存，量了再决定（别预先优化） |
| **持久化路径** | **方案 B**：`ContainerContext.persist(key, value)`，见 §3.4 |
| 活化入口 | **现成可用**：活按钮取 `menu.getCarried()`（鼠标上那件）⇒ 拖出背包 + 开任意容器 GUI + 点按钮 |

**未定案（拍板前不要动工）**：

1. ~~**`ContainerDataKey` 的 CODEC / 附件类型句柄**~~ ⇒ **2026-10-22 晚查代码后降级为「落地步骤」**：
   附件已有（`attachment()` getter + 水领域在用），只剩 CODEC，且与 attachment 同构
   （`savedWith` 工厂 + `codec()` getter + 两个领域各声明一行）。见 §3.4。
2. **嵌套上限放开时机**：现在写死 1，等活潜影箱立项一起放开（倾向是）。
3. **handler 获取的真实开销**：要实测（`BackpackWrapper.fromStack` 每 tick 每背包一次）。

---

## 7. 验证入口

| 项 | 守卫 |
|---|---|
| 框架零改动 | `ContainerLivingItemHandler` / `scanAndGroupLivingItems` diff 为空 |
| 活化门槛 | 未活化的精妙背包不进 `grouped`（不 tick、无开销） |
| 递归生效 | 活背包里的活熔炉会烧、活桶会续水（关着 GUI 也烧） |
| UUID 守恒 | 破坏 → 拾取 → 放置，UUID 不变；复制 ⇒ 副本新 UUID、数据独立 |
| SavedData 往返 | 存档 → 重进，容器级数据按 UUID 读回；空条目不写盘 |
| 渲染 | 手持活背包打开 GUI，流体层出现且每 tick 刷新 |
| 不回归 | 方块形态容器（箱子/船/矿车）渲染与数据零变化 |
| LRU 不丢脏数据 | 宿主容器超时回收缓存后，再打开能从 SavedData 回填 |

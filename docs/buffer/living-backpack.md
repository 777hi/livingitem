<!-- markdownlint-disable -->

# 活背包（精妙特化的活物品功能）

> **状态：探讨中（2026-10-22，第三轮定形）** —— 由「物品形态的背包里流体不渲染」引出。
> **定位**：这不是通用基础设施，而是**一个活物品功能**（精妙背包特化）；
> 它与 [活潜影箱实现细节](活潜影箱实现细节.md) 共享 UUID + SavedData 地基，
> 但驱动力完全复用现有 tick 管线 —— **框架零改动**。
>
> ⚠️ 本稿前两轮（viewer 驱动 / 框架扫描）**全部作废**，纠偏过程见 §5。
> 按早期结论动手会白做三轮。

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

## 6. 未定案（拍板前不要动工）

1. **嵌套要不要现在做**：活背包里放一个活背包 ⇒ 两层递归。活潜影箱设计稿已定案
   「功能内部递归 + 深度预算 + 环检测」，本文是它的单层特化。建议**现在就按可递归实现**
   （`processContext` 天然支持），但深度上限先写死 1，等 C 立项再放开。
2. **handler 获取成本**：`BackpackWrapper.fromStack` 每次调用的开销未实测；太贵就加弱缓存。
3. **多人同看**：精妙 linked storage 让多个背包共享一份内容，但它们 UUID 不同 ⇒
   容器级数据各算各的。建议接受并写进文档。
4. **键形前缀**：`container_<uuid>` 会撞既有 `container_<dim>_x_y_z` 的
   `ContainerKeys.kindOf` 分支 ⇒ 需换前缀（如 `cuuid_<uuid>`）。**必须先定再动工。**
5. **关着 GUI 也 tick**（玩法口径）：扫描驱动的自然结果是「背包在玩家身上就烧」。
   倾向「烧」（这才是活背包），但要你确认。

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

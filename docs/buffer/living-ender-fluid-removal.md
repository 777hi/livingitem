# 砍掉「原版末影箱当流体容器」的兼容（2026-10-06）

> **性质**：删除类改动的记录文档（**不留痕就会变成事故**）。
> **定稿**：2026-10-06，用户拍板：「砍了吧，不兼容了，太费劲了。兼容了也没有什么玩法。」
> **硬边界**：🔴 **不动活末影箱物品**（`domain/ender/` 整个子系统 + `living-ender-chest-tech.md`）。
> 砍的只是「玩家自己的**原版末影箱 GUI** 能不能当流体容器 / 装活物品」这一层。

## §1 为什么砍

1. **同一概念反复出事**：三次实测泄漏 —— ① 末影箱的水渲染到玩家物品栏（键前缀撞车）
   ② 末影箱什么都不渲染（客户端 `instanceof` 死分支）③ 末影箱的水渲染到**别的箱子**界面
   （派发不判 viewer，每 tick 覆盖目标提示）。每次都要加协议字段 / 客户端状态。
2. **复杂度已渗进架构层**：Player attachment 整条落盘线（`Map<容器键,数据>` + `KEYED_CODEC`）、
   `SimpleContainerContext` 的**显式稳定键构造器**（为它 crash-2026-04 逼出来的）、
   流体快照**第三条派发** + 协议 `RenderTarget` 字段。
3. **玩法收益低**：末影箱当流体容器的独特价值只是"随身 + 跨玩家"，而这正是复杂度来源。

**补偿形态（不变）**：想"随身存流体"走**活末影箱物品**（已有子系统），与「专用流体活物品」
同一思路 —— 不在通用容器能力上开后门。

## §2 删除清单

| # | 件 | 处理 |
|---|---|---|
| 1 | `container/EnderChestContainerContext.java` | **删文件** |
| 2 | `processEnderChest` + 调用点 + `ownerPlayer` 的 ender 分支 | 删 |
| 3 | `ContainerContexts.resolve` 的 F-1 末影箱分支 | 删（末影箱槽位 ⇒ 返回 null ⇒ 不能汲/倒） |
| 4 | `SimpleContainerContext` 显式稳定键构造器（6 参） | 删（唯一调用者是被删的 #1） |
| 5 | `FluidFlowServerSync` 末影箱派发 + `renderTargetOf` 的 ENDER 分支 | 删 |
| 6 | `FluidFlowSyncPacket.target` 协议字段 | 删 ⇒ 客户端**回到按键前缀路由**（只剩 `player_<uuid>` 与 BE 坐标键，**无前缀冲突**） |
| 7 | `FluidFlowClientCache` 的 `enderSnapshot` / `RenderTarget` / `chestLikeTarget` / `getChestLike()` | 删 ⇒ 回到「背包桶 + BE 桶」两槽位 |
| 8 | 客户端两处消费（mixin 渲染 / `GuiInteractionHelper` 汲倒判定） | 改回 `group == playerInv ? getPlayer() : get()` |
| 9 | 测试：`SimpleContainerContextTest` 显式键 3 项、`ContainerContextsTest` 末影箱项 | 删 |

## §3 明确保留（不动）

- `domain/ender/**` 10 个类 + `living-ender-chest-tech.md`（活末影箱**物品**子系统，用户硬边界）。
- `LivingEnderChestFunction` / `Accessor` 里的 `PlayerEnderChestContainer` 判位（属物品子系统）。
- `CONTAINER_FLUID_DATA_PLAYER` + `KEYED_CODEC`：现在只剩**背包**一个用户，但**保留 Map 形态**
  （改单值要动落盘格式，零玩法收益、不划算）；注释改为「玩家背包路径」现状说明。
- 行为空时写回 EMPTY 的双路径（与末影箱无关）。

## §4 玩家可见回退（已知代价）

原版末影箱**不能**倒活水 / 汲水 / 装活物品、**不渲染流体**；
其余容器（背包、大小箱子、末影箱**物品**）行为**完全不变**。

## §5 测试守卫

| # | 守卫 |
|---|---|
| G1 | `ContainerContexts.resolve` 对末影箱槽位返回 `null`（明确"不支持"，而非误解析） |
| G2 | 客户端缓存按键路由回归：`player_<uuid>` ⇒ 背包桶；BE 坐标键 ⇒ BE 桶（10-05 泄漏修复的不变量本体） |
| G3 | 背包流体链路全绿（既有水体测试即覆盖） |

## §6 文档跟进清单

- [ ] §2 引擎结构 / §7 渲染（恢复两槽位）/ §8 落盘（删末影箱行）
- [ ] §9 挂起项：新增「原版末影箱不支持流体」明示
- [ ] `docs/TODO.md`：撤下已完成的「末影箱汲/倒」项，改记为「不支持」
- [ ] `docs/reference/file-map.md`：删 `EnderChestContainerContext`
- [ ] `changelog.md` + `AGENTS.md` 进展行 + 测试计数 + `doc_check.py` 全过

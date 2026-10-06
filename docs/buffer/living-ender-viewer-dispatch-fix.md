# 修「末影箱的流体渲染串到别的箱子界面」（第 ③ 次泄漏）—— 2026-10-06

> **性质**：修bug 的记录文档（这条链上已经出过三次事故，值得留痕）。
> **背景**：同日先做了「砍掉原版末影箱当流体容器」（提交 `1613b65`），随后**回退**（撤销误伤），
> 改为修 bug。砍掉的那次其实把 `processEnderChest`（第 4 条完整 tick 入口）也删了，
> 导致末影箱里**全部**活物品机制失效 —— 超出「砍流体」的范围，属误伤。回退后本文记录真正的修法。

## §1 三次泄漏的现状（回退后的代码状态）

| # | 现象 | 根因 | 状态 |
|---|---|---|---|
| ① | 末影箱的水渲染到**玩家物品栏** | 客户端**按键前缀路由**：`player_<uuid>` 与 `player_<uuid>_ender_chest` 前缀相撞 | ✅ 已修（`RenderTarget` 协议字段，服务端权威告知目标） |
| ② | 末影箱**什么都不渲染** | 客户端 `slot.container instanceof PlayerEnderChestContainer` 是**死分支**（客户端只有 27 格 `SimpleContainer` 替身，真实容器只在服务端）⇒ 与普通 3 行箱子界面**完全同形** | ✅ 已修（同 ① 的 `RenderTarget` 机制；`23a3bb0` 补齐客户端两处消费） |
| ③ | 末影箱的水渲染到**别的箱子界面** | **服务端末影箱派发不判 viewer** —— 见下 | ❌ **未修（本文要修的）** |

## §2 根因（③ 的完整链条）

1. 服务端 `FluidFlowServerSync.dispatch` 的末影箱分支：
   ```java
   if (ctx instanceof EnderChestContainerContext ender
           && ender.getOwner() instanceof ServerPlayer owner) {
       owner.connection.send(packet);      // ← 无条件直发主人
       return;
   }
   ```
   **不判玩家当前是否正打开末影箱界面** ⇒ 末影箱有流体时，**每 tick**都给他发一份快照
   （BE 容器那条路径本来就有 `ContainerContexts.isViewing` 过滤，末影箱这条漏了）。

2. 客户端 `FluidFlowClientCache` 的分桶：
   ```java
   case ENDER_CHEST -> { enderSnapshot = snapshot; chestLikeTarget = RenderTarget.ENDER_CHEST; }
   ```
   `chestLikeTarget` 是**全局的「非背包组该用哪个桶」提示，由最近收到的包决定**
   （因为客户端分不清末影箱界面和普通 3 行箱子界面 —— 见 §1 ②）。

3. ⇒玩家**关掉末影箱、打开一个普通箱子**：包还在来 ⇒ 提示被翻成 `ENDER_CHEST`
   ⇒ 普通箱子界面读 `getChestLike()` 拿到**末影箱**的水 ⇒ **串台**。
   背包组读 `getPlayer()`，不受影响 ⇒ 所以泄漏只发生在「非背包组」。

## §3 定稿方案（用户拍板：修，不砍）

**服务端派发判 viewer**，与 BE 路径同构：末影箱快照**只在玩家正打开自己的末影箱界面时**发。

- 新增 `ContainerContexts.isViewingEnderChest(Player)`（公共判据，唯一实现点）；
  `LivingEnderChestFunction.isInEnderChestGui` 改为**委托**它（消掉重复判据，两处原本逐字相同）。
- `FluidFlowServerSync.dispatch` 末影箱分支加判定；**不发时也要 `return`**
  （末影箱没有 BE 关联，不能落到下面的 BE 匹配分支）。

判据沿用已在生产代码里被验证过的写法（`LivingEnderChestFunction` 原实现）：

```java
menu instanceof ChestMenu chest && chest.getContainer() instanceof PlayerEnderChestContainer
```

**为什么不判「有没有流体」而是判「在不在看」**：这与 BE 路径一致，且顺带覆盖清空包边沿
（`CLIENT_ACTIVE` / `onContainerClose` 的空快照逻辑不变）。

## §4 被否选项与理由

| 选项 | 否掉的理由 |
|---|---|
| **客户端加界面归属校验** | 客户端**拿不到**真实容器（§1 ②：界面上只有 27 格替身）⇒ 无法校验，只能靠服务端告知 ⇒ 回到已有的 `RenderTarget` 机制，无新增价值 |
| **末影箱与普通箱子分两个渲染 mixin 分支** | 客户端界面同形，**物理上不可区分** ⇒ 做不到 |
| **干脆不每 tick 发，改成脏标记** | 收益是省包，但泄漏的根因是「发了不该看的界面」，脏标记后仍会发 ⇒ 不治因；且会丢掉「打开后 1 tick 内出图 + 自愈」的既有语义 |
| **回退到砍掉流体兼容** | 已否决（误伤 `processEnderChest`，破坏用户硬边界「不动活末影箱物品」） |

## §5 测试守卫

| # | 守卫 | 落点 |
|---|---|---|
| G1 | `isViewingEnderChest`：末影箱菜单 → true；`ChestMenu` 但容器是普通箱 → false；非 `ChestMenu` 菜单 → false；null → false | `ContainerContextsTest`新增 Nested |
| G2 | 客户端分桶不变量：`update(ENDER_CHEST)` ⇒ `getChestLike()` 读 ender 桶；`update(BLOCK)` ⇒ 读 box 桶；`clear()` ⇒ 三个桶全空且提示重置为 BLOCK（跨界面不闪旧水） | 新增 `FluidFlowClientCacheTest` |

## §6 文档跟进清单

- [ ] `living-fluid-tech.md` §7（渲染）/ §9（挂起项）：恢复「末影箱支持流体」，并记入第 ③ 次泄漏的修法与判据
- [ ] `docs/TODO.md` 第 1 项（末影箱汲/倒）：从「已撤销」改回「已完成」，附泄漏史指针
- [ ] `changelog.md` + `AGENTS.md` 进展行 + 测试计数
- [ ] `doc_check.py` 全过

## §7 遗留（不阻塞本次）

- `GuiInteractionHelper` 客户端汲倒判定仍按界面分组判断，与服务端 viewer 判定同源但各自实现；
  将来若再动这块，考虑收敛到一处。
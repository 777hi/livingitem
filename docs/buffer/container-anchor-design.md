# 容器世界锚点（ContainerAnchor）普查与设计

> **状态：未定案（2026-10-23）** —— 本文只做普查 + 设计提案，拍板前不动代码。
> **起因**：真机「活背包里的活 TNT 点燃后重复倒计时、不爆炸」。
> 追根因时发现这不是孤例，而是**同一个能力缺口在多个功能里各自表现不同**。

---

## §0 一句话结论

**「容器在世界里作用在哪」这件事，现在有 4 份各写一遍的解析**，且**两种非方块形态
（物品形态 / 实体容器）在每份里的处理都不一样**——有的静默跳过、有的静默出错、
有的无限循环。收成一个 `ContainerAnchor`（参考物，不是坐标）能让这些缺口一次性可见。

---

## §1 普查：谁真的需要世界锚点

判据：**该功能是否会写世界**（方块 / 实体 / 音效 / 网络应力），或**读世界状态**（邻居信号 / 方块朝向）。

| 功能 | 需要锚点 | 用途 | 物品形态今天的行为 | 实体容器今天的行为 |
|---|---|---|---|---|
| **活信标** | ✅ | 光束位置 + buff 立方体中心 | `resolveHostPosition` → null ⇒ **不开光**（已文档化） | `getBlockPos()` null + 无 owner ⇒ 不开光（已文档化） |
| **活 TNT** | ✅ | 爆炸中心 | `ignite` 返回 false ⇒ 调用方吞掉并重置 ⇒ **循环**（见 §3） | 同左（`getBlockPos()` 也是 null） |
| **活水车** | ✅ | 应力注入点（下方 BE） | 两条路都不走 ⇒ **零输出**（但 tooltip 仍显示应力） | 同左（`getAssociatedBlockEntities()` 空） |
| **活工具 / 活武器** | ✅ | 记忆射线的重放起点 | `origin == null` ⇒ **整个 tick 直接 return** | **无分支** ⇒ 同上（只认掉落物） |
| **活红石 ×9**（按钮/拉杆/比较器/中继器/火把/灯/块/铜/红石） | ✅ 读 | 读邻居方块信号 + 方块朝向 | `positions` 空 ⇒ 循环不跑 ⇒ **六面输入恒 0** | `positions` 有值 ⇒ 正常 |
| 活酿造台 | △ | 音效目标（要的是 **viewer** 不是坐标） | 无音效（合理：没有"附近"可言） | 正常（按位置匹配） |
| 活涂蜡铜块 | ✗ | 只需 BE `setChanged()`（持久化） | 走 SavedData（已正确） | 走 `getDataHosts()`（已正确） |
| 活漏斗 / 熔炉 / 箱子 / 末影箱 / 耕地 / 地图 / 桶 / 流体 / 打火石 / 背包 | ✗ | 纯数据 / 内部槽位 / 方向来自物品组件 | 正常 | 正常 |

**结论：4 个功能需要坐标型锚点 + 1 组（红石 9 件）需要位置型读取。其余 12 个无关。**

---

## §2 现状：四份重复的解析

同一个「三选一」规则，四处各写一遍，**而且「玩家形态取哪个点」四个都不一样**：

| 位置 | 方块容器 | 玩家容器 | 实体容器 | 物品形态 |
|---|---|---|---|---|
| `ExplosionComponent.ignite` | 方块**中心** | `player.position()`（脚下） | ❌ 无分支 | `return false` |
| `ContainerStressWriteback` | BE 方块**下方** | `player.blockPosition()`（脚下方块） | ❌ 无分支（用 `getAssociatedBlockEntities`） | 静默跳过 |
| `LivingToolFunction.resolveOrigin` | 方块**中心** | `player.getEyePosition()`（**眼睛**） | 仅 `ItemEntityContainerContext`（掉落物） | `return null` |
| `BeaconFunction.resolveHostPosition` | 有盖箱子中心（否则 null） | `getOwnerPlayer().position()` | ❌ 无分支 | null |

**「取哪个点」不同是对的**——射线要从眼睛出发，应力要落在脚下。所以**不能把锚点定义成坐标**。
但这四份的**分类逻辑**（方块 / 玩家 / 实体 / 无）是完全一样的，重复了四遍。

⚠️ 顺带发现：`ContainerStressWriteback` 用的是 `getAssociatedBlockEntities()`，
而 2026-10-21 实体容器立项时已把写回口径统一到 `getDataHosts()`（BE 或 Entity）
⇒ **船/矿车里的活水车从不输出应力**，且没人审计过。

---

## §3 设计提案：锚点是「参考物」，不是坐标

```java
/** 容器在世界里的作用点 —— 只回答「参考物是谁」。 */
public sealed interface ContainerAnchor {
    /** 方块容器：由调用方决定用中心 / 下方 / 邻居。 */
    record AtBlock(BlockPos pos) implements ContainerAnchor {}
    /** 玩家容器：由调用方决定用脚下 / 脚下方块 / 眼睛。 */
    record AtPlayer(Player player) implements ContainerAnchor {}
    /** 实体容器 / 掉落物：由调用方决定用碰撞箱中心 / 实体位置。 */
    record AtEntity(Entity entity) implements ContainerAnchor {}
}
// 「没有锚点」= 返回 null（物品形态，未继承宿主时）
```

```java
// ContainerContext —— 唯一实现点
default ContainerAnchor getAnchor() {
    if (getBlockPos() != null) return new AtBlock(getBlockPos());
    if (getOwnerPlayer() != null) return new AtPlayer(getOwnerPlayer());
    if (/* 实体容器 */) return new AtEntity(entity);
    return null;   // 物品形态：没有世界锚点
}
```

**框架只回答「谁/在哪」，功能自己决定「用哪个点、要不要用」。**

各功能改造后：

```java
// TNT：null ⇒ 显式拒绝（不点燃 / 保留状态），不再吞返回值
ContainerAnchor anchor = ctx.getAnchor();
if (anchor == null) { tick.skipUnsupported(ID, "无世界锚点"); return; }
Vec3 center = switch (anchor) {
    case AtBlock b  -> Vec3.atCenterOf(b.pos());
    case AtPlayer p -> p.player().position();
    case AtEntity e -> e.entity().position();
};

// 水车：AtBlock ⇒ pos.below()；AtPlayer ⇒ player.blockPosition()；AtEntity ⇒ 实体位置
// 工具：AtBlock ⇒ 中心；AtPlayer ⇒ 眼睛；AtEntity ⇒ 碰撞箱中心
// 信标：AtBlock 且是有盖箱子 ⇒ 中心；AtPlayer ⇒ position()；其余 ⇒ 不开光（保持现状）
```

---

## §4 提案二：物品形态继承宿主的锚点

`LivingBackpackFunction.tick(entries, container, tick, level)` 手里就有**宿主 ctx**。
把它的 `getAnchor()` 传给内层 `ItemFormContainerContext`，物品形态就有了锚点。

| 背包在哪 | 继承到的锚点 | 水车 | TNT | 工具 |
|---|---|---|---|---|
| 玩家身上 | `AtPlayer` | 输出到脚下 | **炸在脚下（自爆）** | 眼睛射线可用 |
| 箱子里 | `AtBlock` | 箱子下方 | 箱子处 | 方块中心 |
| 船/矿车里 | `AtEntity` | 实体位置 | 实体位置 | 碰撞箱中心 |
| 掉在地上 | `null` | 不输出 | 不炸 | 不重放 |

**⚠️ 继承不能让框架一刀切**：TNT 继承后就是自爆。⇒ **「要不要用锚点」仍然是功能的决定**，
框架只负责把宿主的事实递下去。

---

## §5 未定案（拍板前不动代码）

1. **各功能在「无锚点」时的语义**——必须逐个定，不能统一：
   - TNT：拒绝点燃？还是点燃后停在 0 并显示「此处无法引爆」？
   - 水车：不输出（但 tooltip 要如实说「未接入网络」，否则是骗人）
   - 工具：不重放（现状）—— 要不要在 tooltip 说明？
   - 信标：不开光（现状，已文档化）
2. **继承宿主的边界**：背包里的背包（`MAX_DEPTH = 1`，暂不涉及）；背包在**掉落物**里
   （`ItemEntityContainerContext`）—— 掉落物本身有位置，要不要继承？
3. **红石 9 件**：物品形态「收不到信号」是正确语义还是缺口？
   （若正确，则背包里的活 TNT 只能靠**手动点燃**——这与真机现象吻合）
4. **活武器是另一条路**：`LivingWeaponAssist` 扫的是 `player.getInventory()`，
   与 `ContainerContext` 无关 ⇒ **活背包里的活武器不参与补刀**。
   这是「背包内容算不算装备」的独立玩法问题，**不在本设计射程内**。

---

## §6 TNT 现象的两个候选成因（需真机日志区分）

| 候选 | 机制 | 特征 |
|---|---|---|
| **A. 无锚点 + 信号非 0** | `ignite` 返回 false → 调用方无条件重置 → 下一 tick 信号仍在 ⇒ 重新点燃 | 需要背包里的红石信号 > 0（但 §5-3 说物品形态收不到信号 ⇒ 存疑） |
| **B. 就地修改未落盘** | `LivingTntData.set(stack, ...)` 改的是内存里的栈，未走 `ctx.setItem`；NBT 侧仍是 `fuse=80` ⇒ 每 tick 从 80 重新开始 | 不需要信号，且能解释「永远从同一个数开始」 |

⚠️ 旁证：`LivingToolFunction` 有显式的 `writeBack`（还带 `ItemStack.matches` 短路坑的注释），
说明**「就地改组件的功能必须自己写回」是既有约定**——而 TNT 没有写回。
⇒ **B 的可能性更大**，但两者都修才安全（A 是 §3 的显式拒绝，B 是写回）。

---

## §7 建议实施顺序

1. **`ContainerAnchor` + `getAnchor()`**（4 份解析收成 1 处）—— 机械改动，无行为变化
2. **TNT 修 B（写回）** —— 独立于锚点，先让倒计时能真正推进
3. **TNT 修 A（显式拒绝）** —— 无锚点时不再循环
4. **水车 / 工具 / 信标** 改走 `getAnchor()`（行为不变，只是收编）
5. **继承宿主**（`ItemFormContainerContext` 接受 anchor 参数）—— 逐个功能决定要不要用
6. **实体容器的缺口**（水车 `getDataHosts()`）—— 独立小修
7. **红石 9 件 / 活武器** —— 单独讨论，不在本轮

---

## §8 相关

- [容器边界口径](../system-design/container-boundary.md) —— I-C5（按位置匹配 viewer）/ I-C6（降级可观测）
- [活背包技术文档](../tech/living-backpack-tech.md) —— 物品形态容器的身份 / 几何 / 持久化
- [活潜影箱实现细节](活潜影箱实现细节.md) —— 自建 GUI 形态，将来也要回答同一组问题

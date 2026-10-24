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

| 功能 | 需要锚点 | 用途 |
|---|---|---|
| **活信标** | ✅ | 光束位置 + buff 立方体中心 |
| **活 TNT** | ✅ | 爆炸中心 |
| **活水车** | ✅ | 应力注入点（下方 BE） |
| **活工具 / 活武器** | ✅ | 记忆射线的重放起点 |
| **活红石 ×9**（按钮/拉杆/比较器/中继器/火把/灯/块/铜/红石） | ✅ 读 | 读邻居方块信号 + 方块朝向 |
| 活酿造台 | △ | 音效目标（要的是 **viewer** 不是坐标） |
| 活涂蜡铜块 | ✗ | 只需 BE `setChanged()`（持久化，走 `getDataHosts`） |
| 活漏斗 / 熔炉 / 箱子 / 末影箱 / 耕地 / 地图 / 桶 / 流体 / 打火石 / 背包 | ✗ | 纯数据 / 内部槽位 / 方向来自物品组件 |

**结论：4 个功能需要坐标型锚点 + 1 组（红石 9 件）需要位置型读取。其余 12 个无关。**

---

## §1.1 五种容器形态 × 五个功能：今天各自怎么坏

⚠️ **「容器形态」有 5 种，不是 3 种**——`掉落物` 与 `实体容器（船/矿车）` 是两个不同的 ctx：

| 形态 | ctx | 有位置？ | 有主人？ | 有实体？ |
|---|---|---|---|---|
| 方块容器 | `SimpleContainerContext`(BE) | ✅ `getBlockPos` | ✗ | ✗ |
| 玩家容器 | `SimpleContainerContext`(Inventory) / `EnderChestContainerContext` | ✗ | ✅ `getOwnerPlayer` | ✗ |
| **实体容器**（船/矿车） | `EntityContainerContext` | ✗（刻意 null） | ✗ | ✅ |
| **掉落物** | `ItemEntityContainerContext` | ✗ | ✗ | ✅ |
| 物品形态（活背包） | `ItemFormContainerContext` | ✗ | ✗ | ✗ |

| 功能 | 方块 | 玩家 | 实体容器 | 掉落物 | 物品形态 |
|---|---|---|---|---|---|
| 活信标 | ✅ | ✅ | 不开光（已文档化） | 不开光（合理） | 不开光（已文档化） |
| **活 TNT** | ✅ | ✅ | ✅（AtEntity） | ✅（AtEntity，2026-10-23 起被 tick） | 循环（无锚点，见 §6.1） |
| **活水车** | ✅ | ✅ | **零输出**（写回没跟上 `getDataHosts`） | **零输出** | **零输出**（tooltip 仍显示应力） |
| **活工具 / 武器** | ✅ | ✅ | 无分支 ⇒ 静默 | ✅ 有专门分支（`rayOrigin`） | 静默 |
| 活红石 ×9 | ✅ | ✗（无邻居，合理） | ✅ | ✗ | ✗ |

**这张表就是「为什么要收锚点」的最好论据**：
- 同一功能在 5 种形态下的行为**散落在 4 处代码里**，谁都没有完整视图
- **掉落物是唯一「做对过」的形态**（工具那条），因为它当初被显式想到了
- 反过来，实体容器（10-21 才加）**从没被审计过**——水车、TNT、工具三处全缺

---

## §2 现状：四份重复的解析

同一个「三选一」规则，四处各写一遍，**而且「玩家形态取哪个点」四个都不一样**：

| 位置 | 方块容器 | 玩家容器 | 实体容器（船） | 掉落物 | 物品形态 |
|---|---|---|---|---|---|
| `ExplosionComponent.ignite` | 方块**中心** | `player.position()`（脚下） | ❌ 无分支 | ❌ 无分支 | `return false` |
| `ContainerStressWriteback` | BE 方块**下方** | `player.blockPosition()`（脚下方块） | ❌ 无分支（用 `getAssociatedBlockEntities`） | ❌ 无分支 | 静默跳过 |
| `LivingToolFunction.resolveOrigin` | 方块**中心** | `player.getEyePosition()`（**眼睛**） | ❌ **无分支** | ✅ `rayOrigin(entity)`（碰撞箱中心） | `return null` |
| `BeaconFunction.resolveHostPosition` | 有盖箱子中心（否则 null） | `getOwnerPlayer().position()` | ❌ 无分支 | ❌ 无分支 | null |

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

## §4 提案二：物品形态继承宿主的锚点（✅ 已实施，仅 TNT 启用）

`LivingBackpackFunction.tick(entries, container, tick, level)` 手里就有**宿主 ctx**。
把它的 `getAnchor()` 传给内层 `ItemFormContainerContext`，物品形态就有了锚点。

| 背包在哪 | 继承到的锚点 | 水车 | TNT | 工具 |
|---|---|---|---|---|
| 玩家身上 | `AtPlayer` | 输出到脚下 | **炸在脚下（自爆）** | 眼睛射线可用 |
| 箱子里 | `AtBlock` | 箱子下方 | 箱子处 | 方块中心 |
| 船/矿车里 | `AtEntity`（船） | 实体位置 | 实体位置 | 碰撞箱中心 |
| **掉在地上** | `AtEntity`（掉落物实体） | 掉落物位置 | **炸在原地** | 碰撞箱中心 |
| 背包自己掉在地上且是活背包 | `AtEntity`（那个 ItemEntity） | 同上 | 同上 | 同上 |

⚠️ **掉落物形态有实体 ⇒ 它有锚点**（`AtEntity`）。这与「物品形态没有锚点」不矛盾：
物品形态缺的是**宿主**，一旦继承宿主（宿主可能是方块 / 玩家 / 实体），它就也有了。
**唯一真正没有锚点的情况是「活背包掉在地上」之外的链条断点** —— 实际上
`ItemEntityContainerContext` 自带实体 ⇒ 掉落物永远有锚点，除非显式不给。

**⚠️ 继承不能让框架一刀切**：TNT 继承后就是自爆。⇒ **「要不要用锚点」仍然是功能的决定**，
框架只负责把宿主的事实递下去。

### §4.1 落地形状（2026-10-23）

两个访问器，**刻意分开**：

| 方法 | 回答 | 物品形态 |
|---|---|---|
| `getAnchor()` | 这个容器**自己**在世界里的哪里 | 恒 `null` |
| `getHostAnchor()` | 它**借住**在世界的哪里 | 宿主的值（tick 路径由 `LivingBackpackFunction` 传入） |

为什么不合成一个：若 `getAnchor()` 直接返回继承值，**工具/水车/信标会在你不知情的情况下
改变行为**。分开后，功能要用必须显式写 —— 这就是「需要的活物品直接申请」。

**目前只有活 TNT 启用**（在 `ExplosionComponent.ignite` 里取「自己的锚点，没有就用宿主的」）。
理由：爆炸点用继承值是**修复**（物品在箱子里就该炸在箱子处），
而工具/水车/信标用继承值是**加强玩法**（自动挖矿 / 随身发电 / 随身光环），必须逐个拍板。

### §4.2 为什么不是「无锚点就不 tick」

曾考虑过给功能加 `requiresWorldAnchor()` 声明 + 框架闸门（无锚点则整个功能不 tick）。
被否，理由：**那等于「把点着的炸弹塞进包里，引信就暂停」** —— 不诚实，而且可利用。
宿主继承给出的是物理答案（炸在宿主处），也顺带消掉了「冻结 / 失败 / 循环」三个伪问题。

---

## §5 未定案（拍板前不动代码）

1. **各功能在「无锚点」时的语义**——必须逐个定，不能统一：
   - TNT：**不做特殊处理**（2026-10-24 定）。无锚点在 tick 路径上**不可达**
     （物品形态的宿主锚点由 `LivingBackpackFunction` 传下来，宿主是方块/玩家/船，恒非空），
     所以「无锚点怎么办」是给死分支设计结局 —— 已把那段逻辑整段删掉。
     ⚠️ 若将来允许关掉宿主锚点（配置化），无锚点会变成可达，那时必须重新设计
     （注意：容器的**内部红石**与位置无关，熄灭后会被重新点燃 ⇒ 不能靠"熄灭"收拾）。
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
5. **只接了一半：下游还在问容器自己**（2026-10-24 实测定位，未修）——
   `resolveOrigin` / `resolveHostPosition` 已改成问 anchor，但拿到值之后的**下游分派**
   仍按容器自身身份判断，物品形态两个字段都是 null：
   - 信标 `BeaconFunction.tick`：`getBlockPos()` / `getOwnerPlayer()` 双双 null
     ⇒ 光束 / 开盖 / 玻璃色 / 联机广播全不执行（buff 生效，因为 `applyEffects`
     是唯一直接用 hostPos 的）⇒ 「buff 有、光没有」。
   - 工具 `LivingToolFunction.tick`：`hostBlocks`（「别挖自己容器」黑名单）取自
     `getAssociatedBlockPositions()` + `getBlockPos()`，物品形态皆空 ⇒
     箱子里射线起点在箱子方块中心，第一个穿到的格子就是那只箱子。
   ⇒ 统一口径：**「宿主是谁」只问 anchor，别回头问容器自己**。
   修法与边界（信标 `beamPos` 在物品形态会拿到 null，可能要 `getHostBlockPositions()`）
   见 [TODO.md](../TODO.md) 活背包 6.2。
6. **客户端没有「物品形态」数据源**（2026-10-24 实测，靠后）——
   渲染器枚举的四条来源里没有背包，服务端也从未给物品形态发过对应的包；
   且内层写回的组件变化传不出物品形态（`syncSlotToClients` no-op + 原版
   `matches` 检测不到自定义组件变化）。分三件事补：脏消息递出 / 客户端第五条
   数据源 / 远程 S2C（键用容器 UUID）。见 [TODO.md](../TODO.md) 活背包 6.1。

---

## §6 TNT 现象的两个候选成因（需真机日志区分）

| 候选 | 机制 | 特征 |
|---|---|---|
| **A. 无锚点 + 信号非 0** | `ignite` 返回 false → 调用方无条件重置 → 下一 tick 信号仍在 ⇒ 重新点燃 | 需要背包里的红石信号 > 0（但 §5-3 说物品形态收不到信号 ⇒ 存疑） |
| **B. 就地修改未落盘** | `LivingTntData.set(stack, ...)` 改的是内存里的栈，未走 `ctx.setItem`；NBT 侧仍是 `fuse=80` ⇒ 每 tick 从 80 重新开始 | 不需要信号，且能解释「永远从同一个数开始」 |

⚠️ 旁证：`LivingToolFunction` 有显式的 `writeBack`（还带 `ItemStack.matches` 短路坑的注释），
说明**「就地改组件的功能必须自己写回」是既有约定**——而 TNT 没有写回。
⇒ **B 的可能性更大**，但两者都修才安全（A 是 §3 的显式拒绝，B 是写回）。

**🔬 判别实验（不用改代码就能区分）**：拿一个已点燃的活 TNT，**扔到地上**（掉落物形态）。

| 观察结果 | 结论 |
|---|---|
| 也重复倒计时 | **B**（写回问题，与容器形态无关） |
| 倒计时走到 0 停住（不循环、不炸） | **A**（无锚点，且信号为 0 ⇒ 不会重新点燃） |
| 正常爆炸 | 两者都不是 —— 另有原因 |

⚠️ 注意：按 §1.1，**掉落物形态今天同样没有爆炸锚点**（`getBlockPos()` null + `getInventory()` null），
所以「正常爆炸」几乎不可能出现；这个实验主要区分 A / B。

### §6.1 实测结论（2026-10-23，走通宿主锚点之后）

宿主锚点接通后**能爆炸了**，但立刻暴露第三个成因：

| 候选 | 结论 |
|---|---|
| A 无锚点 + 信号非 0 | 不是主因（物品形态收不到邻居信号，§5-3） |
| B 就地修改未落盘 | **方向反了** —— 缺写回不是问题；问题是**写回发生在 ignite 之后** |
| **C 炸完又被写回**（新） | ✅ 实测：`ignite` 把槽位清成 `EMPTY`，紧接着 tick 尾部把 tick 开始时的快照 `stack` 写回去 ⇒ **炸了但 TNT 还在**（而且是「未点燃」的新状态） |

⇒ 教训：**写回本身没错，错在「谁拥有这次修改」**。`ignite` 的语义是「消耗」，
消耗之后的槽位属于 `ignite`，tick 不得再碰。已在 `LivingTntFunction` 里以
「成功即 `return`」表达，并加 3 条回归测试（单颗 / 多颗 / 炸不了不重置）。

---

## §7 实施顺序与进度

| # | 步骤 | 状态 |
|---|---|---|
| 1 | **`ContainerAnchor` + `getAnchor()`**（含实体容器 / 掉落物两个必须的覆写） | ✅ 已完成 |
| 2 | **TNT 修 B（写回）** —— `context.setItem(slot, stack)`，不再依赖"活引用" | ✅ 已完成 |
| 3 | **TNT 无锚点语义** —— 无锚点在 tick 路径不可达 ⇒ 相关分支整段删除（2026-10-24 定） | ✅ 已完成 |
| 4 | **TNT 爆炸 + 信标光束** 改走 `getAnchor()`（行为不变） | ✅ 已完成 |
| 5 | **水车 / 工具** 改走 `getAnchor()` | ⏳ 待做（见 §7.1） |
| 6 | **继承宿主**（`getHostAnchor()` + TNT 启用；AtEntity 爆炸点也补了） | ✅ 已完成（仅 TNT） |
| 7 | **实体容器的水车缺口**（`ContainerStressWriteback` 的 `getAssociatedBlockEntities` → `getDataHosts`） | ⏳ 待做 |
| 8 | **红石 9 件 / 活武器** | ⏳ 单独讨论 |
| 9 | **掉落物形态准入改成功能自声明**（`supportsDroppedItem`）—— 修「活 TNT 掉在地上不 tick」 | ✅ 已完成 |

### §7.1 为什么第 5 步没跟第 4 步一起做

第 4 步的两个站点可以**行为完全不变**地迁移（`AtEntity` 本来就返回"不支持"）。
第 5 步不行，各有一个待拍板点：

- **工具**：`AtEntity` 现在只认 `ItemEntityContainerContext`（掉落物），**没有 `EntityContainerContext` 分支**。
  迁移后要不要让船里的工具也能重放射线？（用实体位置当起点）
- **水车**：输出目标是"每个数据宿主的下方"，是**多位置**语义（大箱子两半各有齿轮）
  —— 它其实不是"一个锚点"，而是"每个 data host"。要单独设计，不能硬套。

⚠️ 另外：`ContainerStressWriteback` 用 `getAssociatedBlockEntities()`（只覆盖 BE），
而 10-21 已把写回口径统一到 `getDataHosts()`（BE 或 Entity）⇒ 船里的水车零输出。
这是第 7 步，与锚点无关（是"per data host"而不是"one anchor"）。

---

## §8 相关

- [容器边界口径](../system-design/container-boundary.md) —— I-C5（按位置匹配 viewer）/ I-C6（降级可观测）
- [活背包技术文档](../tech/living-backpack-tech.md) —— 物品形态容器的身份 / 几何 / 持久化
- [活潜影箱实现细节](活潜影箱实现细节.md) —— 自建 GUI 形态，将来也要回答同一组问题

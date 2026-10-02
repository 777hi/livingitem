# 活武器技术文档

> **状态**：核心链路已实现（2026-09-23），**仅限近战（剑类）**；施法 / 射击 / 蓄力类**未实现**（见 §9）。
>
> ⭐ **共享基础设施不在这里** —— FakePlayer、记忆射线模型、辅助环、挖掘/交互回放、DataComponent 编解码、
> 渲染管线、**射线微调（起点锚点 + 朝向跟随，两模式通用）**全部见 [living-tool-tech.md](living-tool-tech.md)。**本文只写武器侧的差异**，共享部分一律用指针，
> **不复制**（复制必然漂移）。
>
> 📄 设计探讨与待决问题池仍在 [../idea.md](../idea.md)（§1 核心原则 / §3 铁魔法调研 / §4 待决问题池）。

---

## §0 定位与铁律

**活武器不实现任何攻击逻辑，只负责「代玩家出手」。**

```
活武器 = 记忆（朝哪 + 什么操作）+ 回放（摆好位置与朝向 → 调物品的【官方入口】）
```

打多少伤害、发什么子弹、施什么法 —— **全由那个物品自己决定**。

| 活化的东西 | 谁决定效果 |
|---|---|
| 原版剑 / 斧 | 原版 `attack()`（锋利 / 击退 / 火焰附加 / 横扫自动生效） |
| 模组武器 | 它自己的实现（经 `hurtEnemy` / `onLeftClickEntity` 等官方扩展点） |

### 由此推出三条铁律

1. **不识别具体模组** —— 不问"这是不是法杖"，只把操作原样重放；**识别是对方的事**。
2. **走官方入口** —— 只用 NeoForge / 原版钩子。对方守官方口径就自动兼容。
3. **耐久走原版管线** —— 攻击走 `ItemStack#hurtEnemy` ⇒ 见 `living-tool-tech.md` §2.4 的三层。

> 📌 目标不是"做一把活剑"，而是**一套「代玩家出手」的抽象层**。

### ⭐ 需求范围（2026-09-24 用户定）

| 范围 | 内容 | 状态 |
|---|---|---|
| **主线功能** | **近战（剑 / 斧 / 重锤）** —— 攻击记忆、自主模式、辅助模式、记忆清除 | ✅ **已完成** |
| **联动功能** | 模组**法杖 / 枪械** 等"能施法 / 能射击"的物品 | 🔗 **非主线** —— 见下 |

> 🔗 **联动 ≠ 专门适配**。
> 因为铁律①「不识别具体模组」+ 铁律②「走官方入口」⇒
> **兼容是自然结果**，而不是需要逐个实现的功能。
> 例如：`replayUse` 已调 `CommonHooks.onItemRightClick` ⇒
> 铁魔法监听的 `PlayerInteractEvent.RightClickItem` **会被 post** ⇒ 活法杖**理论上已经能施法**。
>
> ⇒ 因此**不做**判据扩展 / 类型分派的专项实现。
> 个别模组若确有需要（如放行 `casting_implement` 判据），属于**可选增强**，按需再做。

---

## §1 判据：什么是活武器

```java
isLivingWeapon(stack) = LivingItemManager.isLivingItem(stack)
                     && stack.is(ItemTags.WEAPON_ENCHANTABLE)      // LivingToolRecorder
```

⭐ **用原版「可附魔类别」标签，不用 `instanceof SwordItem`** —— 这是 Mojang 自己的分类口径，
模组武器通常也会正确归类 ⇒ **自动兼容**。

| 标签 | 覆盖 | 本期 |
|---|---|---|
| `WEAPON_ENCHANTABLE` | 剑 / 斧 / 重锤… | ✅ **已认** |
| `BOW_ENCHANTABLE` | 弓 | ❌ 未开（蓄力型，见 §9） |
| `CROSSBOW_ENCHANTABLE` | 弩 | ❌ 未开 |
| `TRIDENT_ENCHANTABLE` | 三叉戟 | ❌ 未开 |
| `MACE_ENCHANTABLE` | 重锤 | ❌ 未开（已由 WEAPON 覆盖） |

**⇒ 活石头不在任何武器标签里 ⇒ 不会被误判成武器** ✅

⚠️ **该标签包含斧** ⇒ 活斧子**既是工具又是武器**，两条路都走（D3 共存），优先级见 §6。

---

## §2 数据模型：第三类记忆 `AttackMemory`

`LivingToolMemory` 是**三字段**（原为两字段）：

```java
record LivingToolMemory(@Nullable RayMemory dig, @Nullable RayMemory use, @Nullable AttackMemory attack)

record AttackMemory(Vec3 offset, @Nullable EntityType<?> entityType)
```

| 字段 | 语义 |
|---|---|
| `offset` | 「录制者眼睛 → 目标实体」的偏移向量（含长度与方向）—— 与 `RayMemory` 同构 |
| `entityType` | 蹲下时记录的生物类型（**R2**）；`null` = 不限类型 |

⭐ **另建 record 而不是复用 `RayMemory`**（D1）：后者带 `Block` 字段、语义是方块；
混用会污染语义且要动已稳定的编解码 ⇒ 独立出来**零回归风险**。

- `Codec` 与 `StreamCodec` 都已就位（`ATTACK_CODEC` / `ATTACK_STREAM_CODEC`）
- `isEmpty()` 判**三个**字段 ⇒ 带攻击记忆的武器**不上环**（口径自动正确）

---

## §3 录制：`LivingDamageEvent.Post`

入口 `LivingToolRecorder#onLivingDamage`。

| 规则 | 内容 |
|---|---|
| **R1** | 手持活武器**攻击生物** ⇒ 记「朝向 + 距离」射线 |
| **R2** | **蹲下**攻击 ⇒ 额外记生物类型 |
| **R4** | ⭐ **不区分左右键** —— 只按「造成伤害」判定 ⇒ 绕开"这武器用哪只手" |
| **R5** | 一次命中多个 ⇒ 只记**第一个** —— 借 L44 保护期实现 |

### ⭐ 射线的朝向取【玩家视线】，不是「眼睛 → 目标中心」

```java
Vec3 look = player.getViewVector(1.0F).normalize();
double distance = eye.distanceTo(target.getBoundingBox().getCenter());
Vec3 hitLocation = eye.add(look.scale(Math.min(distance, MAX_RAY_LENGTH)));
```

> ⚠️ **曾用 `eye → target.getBoundingBox().getCenter()`**（以为"包围盒中心最能代表打在身上"）——
> 实测像"**记忆射线朝向不是我攻击时的视角朝向**"（2026-09-24）：
> 玩家瞄头 / 瞄脚、或怪物高矮不同时，这条线会**明显偏离视线**（距离越近角度差越大）。
>
> ⇒ **与挖掘侧保持同构**：那边 `raycastSurface` 也是**沿视线**取命中点。
> 长度仍取「到目标的距离」⇒ 回放时射线够得着目标（D2：沿射线找实体）。

⚠️ 若仍有偏差，下一个嫌疑是**录制时机**：`LivingDamageEvent` 在**伤害结算**时才触发，
比实际攻击晚一瞬（玩家可能已微微转视角）。届时应改到**攻击瞬间**取视角
（`AttackEntityEvent` 经 `onPlayerAttackTarget` 在 `Player#attack` 第一行触发，更早）。

### 三个坑（必须过滤）

| # | 坑 | 处理 |
|---|---|---|
| 1 | 远程武器（弓）`directEntity` 是箭 ⇒ `getWeaponItem()` 返回 null | 本期只做近战，不涉及；将来需 fallback 取主手 |
| 2 | 非攻击伤害（火焰附加 / 摔落 / 中毒）同样触发本事件 | 只认 `getWeaponItem()` 是**活武器**的 |
| 3 | 🔴 **回放时会自我录制** —— FakePlayer 造成的伤害照常触发 | 必须 `isFakePlayer()` 排除（同 `L39`），否则越打越偏 |

### ⚠️ 必须用 `Post`，不能用 `Pre`

`LivingDamageEvent` 在 NeoForge 1.21 已拆成 `Pre` / `Post` 两个子类，**`getSource()` 只在子类上**。
用 `Post`：伤害已结算 ⇒ 不会把「被格挡 / 被减免到 0」的攻击录进来。

> 复算：`libs/src/neoforge-21.1.249-merged/net/neoforged/neoforge/event/entity/living/LivingDamageEvent.java`
> 可见 `public abstract class LivingDamageEvent` 与 `static class Pre/Post`。

---

## §4 回放：`replayAttack`

`LivingToolReplay#replayAttack`（与 `replayDig` / `replayUse` 并列）。

```
沿记忆射线找实体 → faceTarget 摆朝向 → 推进冷却 → fake.attack(entity) → 写回副本
```

### 4.1 找目标（`findAttackTarget`）

用官方 `ProjectileUtil.getEntityHitResult`（D2），**不是自己写射线检测**。

能打什么由 `isAttackableByLivingWeapon` 决定：

| 判据 | 挡掉 |
|---|---|
| `LivingEntity` | **船 / 矿车 / 掉落物**（非生物一律不碰） |
| `!Player` | **所有玩家**（含主人）+ 顺带覆盖 `fake` 自己与旁观者 |
| `!ArmorStand` | 盔甲架（它是 `LivingEntity` 且 `isAttackable()` 为 true） |
| `!OwnableEntity(主人)` | 主人驯服的宠物 |
| `isAttackable()` | 掉落物等极少数 override |

> 🔴 **`Entity#isAttackable()` 默认返回 `true`**（只有 `ItemEntity` 等极少数 override 成 `false`）
> ⇒ **光靠它几乎挡不住任何东西**。少了上面几条，活剑会去砍**盔甲架和玩家的船**。
> 复算：`libs/src/neoforge-21.1.249-merged/net/minecraft/world/entity/Entity.java`。

**⚠️ 硬约束：活武器不支持 PVP** —— 所有玩家一律不打。要放开必须先定义"敌对关系"判据。

### 4.2 隔墙检测（不能省）

实体检测**不看方块** ⇒ 不处理就会"隔着墙打死后面的怪"。
做法：沿射线再查一次方块，方块比实体更近 ⇒ 放弃。

### 4.3 🔴 `AttackEntityEvent` 不需要手动补（纠正一条旧结论）

[../idea.md](../idea.md) §2.6 曾把攻击侧列为「❌ 待补」，**该结论已被源码推翻**：

```java
public void attack(Entity target) {
    if (!CommonHooks.onPlayerAttackTarget(this, target)) return;   // Player#attack 第一行
    ...
}
public static boolean onPlayerAttackTarget(Player player, Entity target) {
    if (NeoForge.EVENT_BUS.post(new AttackEntityEvent(player, target)).isCanceled()) return false;
    return stack.isEmpty() || !stack.getItem().onLeftClickEntity(stack, player, target);
}
```

⇒ 调 `fake.attack(target)` **已经**触发 `AttackEntityEvent` **和** `Item#onLeftClickEntity`。
**不要再手动 post 一次**（会重复触发）。

> 📌 **教训**：判断"某个事件会不会触发"必须**顺着调用链查**，不能只看 post 的位置。
> 当时只 grep 到「`AttackEntityEvent` 只在 `CommonHooks` 里 post」就断定不触发 ⇒ 判错。

---

## §5 攻击冷却：原生机制（2026-09-30 受控 tick 重构）

**历史坑（已由架构根治）**：`Player#attack` 的伤害按冷却比例打折
（`f *= 0.2F + f2*f2*0.8F`，f2 = `getAttackStrengthScale(0.5F)`），而
`attackStrengthTicker` 由 `Player#tick()` 自增 —— **FakePlayer.tick 是空的** ⇒
该值永远 0 ⇒ 活武器永远 20% 伤害、无横扫无暴击。第一版解法是覆写
`getAttackStrengthScale` + 在 `replayAttack` 里按「上次攻击 tick / 攻速属性」手算 ——
随之产生一整类坑：冷却时长小数截断死锁、`LivingToolAction` 写回被 `matches`
吞掉导致高速连击（2026-09-29）、属性镜像竞态改写冷却分母导致白板化（2026-09-30）。

**现方案（受控 tick，见 `living-tool-tech.md` §5.7）**：
`LivingToolFakePlayer#driveWielderTick` 每 game tick 驱动真链 ⇒
`attackStrengthTicker` 原生自增、`fake.attack()` 命中后原生重置，
`replayAttack` 的门只剩一行：

```java
if (fake.getAttackStrengthScale(0.5F) < 1.0F) return AttackResult.none(Outcome.COOLING);
```

节奏完全由武器攻速属性决定，与真玩家同源；上述整类坑随原生机制退役。
`LivingToolAction` 不再承担冷却计时（只负责客户端动画）。

副作用（原版一致行为）：假玩家新建/换武器后 `attackStrengthTicker` 从 0 爬升 ⇒
**首刀前有一个冷却期的热身** —— 与真玩家换武器后相同。

> ⚠️ 历史教训保留：这个坑的三个变体（死锁 / 连击 / 白板）都是"手动维护
> 应由 tick 驱动的状态"的必然产物 —— 见 §8.1 镜像竞态与 `living-tool-tech.md` §5.7。

### 🔴 历史坑档案（手动节奏时代的教训，保留防复发）

**「静默失效」三连**（症状：「有记忆、有怪，但一刀都不打」，比崩溃难查得多）：

| # | 坑 | 现状 |
|---|---|---|
| ① | 首次冷却用 `(long) cooldown` 当 elapsed ⇒ 小数截断（剑 12.5→12）⇒ 0.96<1 判冷却中 ⇒ 且该路径不写记录 ⇒ **永久死锁** | ✅ 随原生节奏结构性根除（ticker 无条件自增，无手动除法） |
| ② | `ATTACK_SPEED=0` ⇒ `Infinity` ⇒ 永远判不满 | ✅ 同上 |
| ③ | 隔墙检测没传 `hostBlocks` ⇒ 容器起点埋在方块内 ⇒ 永判隔墙 | ✅ **仍有效**：`findAttackTarget` 必须传 `hostBlocks` |

**第四坑（反向）**：出手记录写在 `attack()` 之后 ⇒ 模组武器在 `onLeftClickEntity`
自结伤害返回 true / 事件取消 / 异常 ⇒ `attack()` 提前返回 ⇒ 记录丢失 ⇒ 每 tick 一刀
（2026-09-29 修复：记录写在 attack 之前 + 攻击分支不走 `matches` 短路必写回）。
✅ **节奏层面已随原生机制结构性根除**（ticker 由 attack 原生重置，不依赖记录）；
**攻击分支必写回的规则保留** —— 它还承担把耐久 / 动画状态落进容器的职责。

> 📌 **通用判据**（仍然成立）：凡是"某个闸门放行后才能推进状态"的循环，
> 必须检查「首次 / 无记录」那条路径能不能自己走通 —— 若它在放行前就 `return`
> 且不写状态，就会永久卡死。

### 冷却数值：完全由【那把武器自身】的攻击速度决定

```
冷却(tick) = 1.0 / 攻击速度 × 20
攻击速度   = 玩家基础 4.0 + 物品修饰符
```

| 武器 | 修饰符 | 实际攻速 | 冷却 | 间隔 |
|---|---|---|---|---|
| **剑**（全材质） | `-2.4` | 1.6 | 12.5 tick | **≈ 0.63 秒** |
| 木斧 / 石斧 | `-3.2` | 0.8 | 25 tick | ≈ 1.25 秒 |
| 铁斧 | `-3.1` | 0.9 | 22.2 tick | ≈ 1.11 秒 |
| 金斧 | `-3.0` | 1.0 | 20 tick | ≈ 1.0 秒 |

> 出处：`Items.java` 的 `SwordItem.createAttributes(tier, 3, -2.4F)` /
> `AxeItem.createAttributes(tier, 6.0F, -3.2F)` 等。

**⇒ 活斧子的冷却约为活剑的【两倍】。**

#### 连带影响（均已确认 · 不改）

| 影响 | 说明 |
|---|---|
| **每把独立** | 每把活武器按【自己手上那把】算 ⇒ 活剑与活斧节奏不同 |
| **伤害 / 攻速平衡自动继承** | 剑（低伤快）、斧（高伤慢）⇒ DPS 接近，与原版一致 ✅ |
| ⚠️ **辅助模式会"跟不上"** | 玩家连打（剑 0.63 秒）时，活斧（1.1 秒+ 冷却）**两刀里只能随一刀** |

> **决定（2026-09-24）：保留冷却，不改。**
>
> 若将来要让辅助攻击严格"一刀随一次"：给 `replayAttack` 加「忽略冷却」参数，
> 且 ⚠️ **必须同时把 `scale` 强制设为 1.0** —— 否则虽然出手了，伤害仍只有 20%。
> 自主模式**不**应忽略冷却（它本来就该按自己的节奏打）。

**"上次攻击 tick"复用 `LivingToolAction.tick`** —— ⚠️ 语义上它本是给**客户端动画**用的
（不落盘，只有 StreamCodec）⇒ 现在一物两用。**做蓄力型时应另建独立组件**，见 §9。

### ⚠️ 由此引出的通用约束：组件里可空的 `BlockPos` 必须按 optional 编码

活武器攻击时目标**不是方块** ⇒ `replayAttack` 写 `new LivingToolAction(now, null)`。

而 `LivingToolAction` 原先用 `BlockPos.STREAM_CODEC` 直接编码 `target` ⇒ **编码 null 抛 NPE**：

```
Caused by: NullPointerException: Cannot invoke "BlockPos.asLong()" because "p_320546_" is null
  at DataComponentPatch$1.encodeComponent(...)
  at LivingToolHostPacket.encode(...)
⇒ Failed to encode packet 'clientbound/minecraft:custom_payload' ⇒ 玩家被踢出游戏
```

> 🔴 **表现为「存档崩了、游戏没崩」** —— 其实是发包失败导致被踢出连接。

**⇒ 约束**：**任何 DataComponent 里带 `BlockPos` 且可能为 null 的字段，一律按「可空」编码**
（写 boolean 标志位）。同理，**客户端消费方也要判 null**（否则 `surfacePoint` 会 NPE）。

⚠️ 别用 `ByteBufCodecs.optional(BlockPos.STREAM_CODEC)` 链式 `.map()` ——
`BlockPos.STREAM_CODEC` 的缓冲类型是 `ByteBuf`（不是 `FriendlyByteBuf`）⇒ 泛型对不上，编译不过。
**手写编解码器**最直接。

> 📌 这个坑**本来就有**：`replayUse` 的「右键空气」分支同样传 null（法杖施法走那条），
> 只是此前没在容器形态触发。

---

## §6 调度与优先级（S2「射线决定」）

`LivingToolFunction`：

- `canApply` = `isLivingTool(stack) || isLivingWeapon(stack)` ⇒ **工具与武器共用这一个 function**
- tick 分派：

```
有 attack 记忆 → replayAttack
否则           → replayDig → replayUse   （互斥，一个 tick 只做一件事）
```

⭐ **走哪条路完全由「记忆射线本身」决定**（2026-09-23 用户定），不是"看现场有什么再挑"。
攻击优先 ⇒ 对齐 W2「射线同时命中实体和方块时，实体优先」。

### ⭐ 双记忆共存：活斧子「有怪打怪、没怪挖矿」（方案 C，2026-09-24 用户定）

活斧子**既是工具又是武器**，可以同时拥有 `dig` 与 `attack` 两条记忆。处理顺序：

```
有 attack 记忆？
  ├─ 射线找到怪 → 冷却满 ⇒ 打；冷却中 ⇒ 【等待】（不转去挖）
  └─ 射线没找到怪 → 转去按 dig / use 记忆干活
```

> 🔴 **早期实现的缺陷**：曾写成「有 attack ⇒ 只走攻击」
> ⇒ **挖掘记忆完全失效** —— 打过一次怪后再也不挖矿，除非手动挥空清掉攻击记忆。
> 这等于**削弱了活斧子**（它本来是工具）。

**⇒ 关键在于 `replayAttack` 必须能区分两种"没出手"**：

| 状态 | 含义 | 调度层该做什么 |
|---|---|---|
| `NO_TARGET` | 射线上没有目标 | **可以**转去挖掘 / 交互 |
| `COOLING` | 有目标但冷却未满 | **等待**，不要转去挖（否则像"边冷却边挖方块"） |

⇒ 只返回 `null` 是**不够的** —— 无法区分这两者，故返回 `AttackResult(tool, Outcome)`。

> ⚠️ 与实体优先的 W2 仍然一致：射线上**同时**有怪和方块时，先打怪。

### ⚠️ 宿主形态：判据必须走单一来源

| 形态 | 入口 | 判据 |
|---|---|---|
| 容器（**普通方块容器**：箱子 / 末影箱等） | `ContainerLivingItemHandler#processContext` | 按 `canApply` 分组 ⇒ ✅ 自动覆盖 |
| **玩家背包** | 同上 | ✅ 自动覆盖 |
| **掉落物** | `LivingItem#processItemEntityContainers` | 🔴 **独立前置过滤** —— **不走** `canApply` 分组 |

> ⚠️ **上表「容器」不含活箱子** —— 活箱子**不是宿主形态**，放进活箱子里的活物品
> **不会被 tick**（不攻击、不挖掘），这是设计现状而非 bug。
> 依据与复算判据见 [living-chest-tech.md](living-chest-tech.md) §3.3，别再去追处理路径。

> 🔴 **事故（2026-09-24）**：掉落物形态的过滤原先只写 `isLivingTool`
> ⇒ **活剑被整个跳过 ⇒ 不 tick ⇒ 不攻击**。
> ⇒ 该处必须用 `LivingToolRecorder#isLivingToolOrWeapon`（与 `canApply` **同一来源**）。
>
> 📌 **教训**：凡是「**是否纳入某条处理管线**」的判据，都要收敛成**单一来源**；
> 分散写就必然在扩展（新增物品类型）时漏改一处 ——
> 环成员口径曾在 3 处重复（活剑"隐身"）已是前车之鉴。

---

## §7 渲染

| 项 | 说明 |
|---|---|
| 攻击射线颜色 | **品红**（`ATTACK_R/G/B`），区别于挖掘的橙红、交互的青蓝 |
| 🔴 `RayMemory` 桥接 | `LivingToolModelRenderer#renderOne` 里把 `AttackMemory` 转成 `RayMemory(offset, null)` 复用渲染路径 |

> ⚠️ **少了这个桥接会 NPE**：只带攻击记忆的活剑 `dig` / `use` 都是 null，
> 原来的 `memory.dig() != null ? dig : use` 会拿到 null 后调 `endpointFrom` ⇒ **崩溃**。

容器形态没有同步"有没有生物命中" ⇒ 攻击射线**恒按命中画**（实心）。

### 7.1 动画（已实现 · 2026-09-24 用户定）

两种模式的动画口径（用户原话：「辅助模式，活武器就做个攻击环，加缩放脉冲，攻击环的话武器朝向圆心。
有记忆的，像活工具的交互动画一样。背后环暂时不动」）：

| 模式 | 动画 |
|---|---|
| **有记忆** | 攻击时**瞬现到目标位 + 缩放脉冲** —— 与活工具交互动画同款，走 `renderOne` 既有的 `action.target()` 分支 |
| **无记忆** | **攻击环**：攻击时飞到目标生物处围一圈、每次出手**缩放脉冲**；停手（> 20 tick）收回背后环 |

**有记忆那条为何几乎是免费的**：`replayAttack` 出手时往武器上写
`LivingToolAction(now, 目标生物所在格)`（⭐ 取**包围盒中心**所在格，取 `blockPosition()` 的话
大型生物会让动画沉到脚底）⇒ `renderOne` 里 `action.target() != null` 的瞬现+脉冲分支**自动生效**。
⚠️ 该组件**只走网络同步、不落盘** ⇒ 正好。

**攻击环**（`LivingToolModelRenderer#renderAttackRing`，与挖掘环逐条对称）：

```
挖掘环：按住左键 ⇒ 飞到【方块】处转圈 … 松手 ⇒ 回背后
攻击环：正在打   ⇒ 飞到【生物】处脉冲 … 停手 ⇒ 回背后
```

- **不转圈，改脉冲**：挖掘用风车自转表现"持续出力"，攻击是一下一下的 ⇒ 复用交互脉冲的
  `PULSE_TICKS`（6）与 `PULSE_SCALE`（**0.30**，2026-09-24 用户调，原 0.15）
- **剑尖朝圆心**（`drawRing` 新增 `inward` 参数）：工具环是"柄朝圆心、头朝外"，
  攻击环相反（一圈剑指向中心）。⭐ 实现：`dir` 取反对齐（模型 +Y = 剑尖）；
  `inward` 时 yaw 偏移 π ⇒ +X 反向 ⇒ `ringRoll` 补 π（推导值，**若实测剑面反了就去掉**）
- **环存活窗口 = 脉冲时长**（`ATTACK_RING_TICKS = PULSE_TICKS`，2026-09-24 用户定）：
  每次出手 ⇒ 飞到目标处脉冲一下 ⇒ 立刻收回背后，「扑咬式」节奏。⚠️ 窗口小于武器攻击冷却
  ⇒ 连续攻击时环反复"飞出去→收回"—— **有意的效果**（曾取 20 留在原地，用户看效果后改掉）
- **半径改【双曲饱和】**（2026-10-01 用户定）：`r(n) = RING_RADIUS_MAX · n / (n + RING_RADIUS_HALF_SATURATION)`
  = `1.77 · n / (n + 21)`（2026-10-01 定，K 由 7 → 21）⇒ **先快后慢、渐近上限**
  （1 把 0.08 / 10 把 0.57 / 21 把 0.89 = 上限一半 / 36 把 1.12），
  取代原来的线性 `BASE + STEP × n`（不再需要 BASE / STEP）。
  📌 K 越大 = 饱和越晚：起步更紧凑、"随数量铺开"的区间更长。
  三个环（背后 / 挖掘 / 攻击）共用同一公式（攻击环的独立常量此前已删除）。
- **背后环混编一圈**（2026-09-24 用户定）：待机的工具与武器**合并成同一圈** ——
  `renderAssistRing` / `renderAttackRing` 都改为返回「待机物品」，由 `render` 统一调一次
  `renderBackRing`。各画各的话两圈同心同半径 ⇒ 完全重叠。其它玩家的环本来就是
  `isAssistItem`（工具 ∪ 武器）混编 ⇒ 两端口径由此一致
- **背后环半径调大**：`RING_RADIUS_BASE` 0.28 → **0.35**、MAX 1.10 → **1.20** ——
  背后环混编含剑（剑长穿模）；挖掘环 / 攻击环共用同组基准，跟着稍大无碍
- **目标位置零新增同步**：直接读武器上的 `LivingToolAction.target()`（辅助攻击时服务端已写），
  取本组**最新一次出手**的那把（辅助攻击全体朝同一目标 ⇒ 一把即可代表全组）

**环成员拆分**（`render` 里）：

```
assistTools   = isAssistTool   → 挖掘环 / 背后环（不动）
assistWeapons = isAssistWeapon → 攻击环
```

⚠️ **活斧子两者都满足**（D3 共存）⇒ 必须 `if (isAssistTool) … else if (isAssistWeapon) …`
⇒ 活斧子归**工具环**（挖掘优先），不会画两遍。

**安全性**：`surfacePoint` 对 clip 未命中（目标在空中等）**退回格心** ⇒ 永不返回 null ⇒
`renderOne` 的攻击分支与 `renderAttackRing` 都不会 NPE。

### 7.2 模型姿态：全线统一「手持 display + 固定整体变换」（✅ 2026-09-30 定案）

**核心思想**（用户的直觉：「明明在玩家手里姿态都是对的」⇒ 那就走手上那条路）：

```
MC 用物品自己的 display transform 把每件物品摆正 —— 建模差异 100% 在这里被吸收
我们只叠加【固定的整体变换】—— 不需要知道任何物品的"长轴 / 板面法线"
```

**最终链路（`drawModel`，写在后面的先作用于模型）**：

```
renderStatic(THIRD_PERSON_RIGHT_HAND)     ← MC 摆正（吸收建模差异 + 手持缩放）
  ← 立正：固定整体旋转（按【场景 × 模型类型】四组常量，见下表）
  ← 自转：固定轴 X
  ← 滚转：环·普通 = 运行时（板面法线→切线）+ 标定90° + ringRoll（0/π 翻转）
          环·BEWLR = ringRoll（0/π）
          射线 = 常量 RAY_ROLL_FIX 180°（四候选实测 —— 无圆平面，纯美学选择）
  ← pitch / yaw 对齐方向（环=径向；射线=记忆射线方向）
```

**立正常量（四组，按【场景 × 模型类型】）**：

| | 普通（贴图薄板） | BEWLR（实体模型） |
|---|---|---|
| **环**（对齐基准 = 水平径向） | 绕 X **+10°**（`ITEM_UPRIGHT_FIX`，环/射线共用） | 绕 Y **−90°**（`BEWLR_UPRIGHT_FIX`） |
| **射线**（对齐基准 = 任意 3D 射线方向） | 绕 X **+10°**（与环共用） | **identity（不转）**（`BEWLR_RAY_UPRIGHT_FIX`） |

| 环节 | 值 / 做法 | 说明 |
|---|---|---|
| 立正（普通） | 绕 X **+10°** | ⭐ 由 handheld `rotation [0,-90,55]` **推出**：手持时长轴在手空间 ≈ `(0, 0.985, −0.173)`——**几乎已竖直** ⇒ 只差 ~10°，**不是 90° 量级**（曾误取 +100° ⇒ 长轴转成 ⊥ 径向 = "横平竖直但柄沿切线"） |
| 立正（BEWLR·环） | 绕 Y **−90°** | 四候选实测 #3；选轴历程：绕 X 无效（长轴含 X）、绕 Z 角度不对、**绕 Y 正确** |
| 立正（BEWLR·射线） | **identity** | 五候选实测 **#0**：环需要 −90° 是因为环要求柄**水平**；射线本来就沿任意方向 ⇒ 手持姿态直接对齐即可 |
| 滚转（环·普通） | 运行时：把常量 `PLATE_LOCAL`（=−X，由 handheld 推出）转到**切线** `normal × dir`，再 **+90°**，再叠加 **`ringRoll`（按第几把 0 / π）** | 🔴 **滚转不能是常量**：立正后局部轴朝向随 yaw（= 环上位置）变化 ⇒ 板面朝向依赖位置；但**板面法线可以是常量** ⇒ 无需逐物品计算。`ringRoll` 不能省：没有它开锋朝向绕环连续 ⇒ 左右半圆刃一前一后 |
| 滚转（环·BEWLR） | `ringRoll`（0 / π） | ⚠️ 不能与普通模型共用运行时那套 —— `PLATE_LOCAL` 是按贴图薄板推的，BEWLR 是实体模型 ⇒ 照用会躺（实测） |
| 滚转（射线） | `RAY_ROLL_FIX` = 绕柄 **180°** | 四候选实测；旧值 270° 是旧链标定，统一链下失效 |
| 自转 | 固定轴 **X** | 常量 |
| 大小 | 手持 scale 由 MC 应用 | 与手上一致（曾因此比旧方案"小一点"，是正确表现） |

**为何 BEWLR 的立正角与普通不同**：两者**模型空间不同**（BEWLR 是实体模型且 `renderByItem`
内部自带变换，如灾变 `scale(1,-1,-1)`）⇒ 各需一组常量，但**是同一套做法**（固定整体旋转），
不是两套哲学。

### 7.3 ⛔ 四次失败的「通用立正」尝试（2026-09-24 ~ 09-29，均已回退，别再走）

目标是让**不守约定的模组物品也自动立正**（原始症状：原子分解机歪 45°、大小不对）。
**四轮全部失败，代码已回退到 §7.2 的旧方案；尝试本身留在 git 历史里当参考。**
（✅ 真正的解法见 §7.4 —— 不是"更聪明的计算"，而是**换一条渲染路径**。）

> ⚠️ **本节第一版曾写了一条已被证伪的结论，务必以这里为准**：
> ~~"本环境（sodium/iris）`renderStatic` 不应用 display transform"~~ —— **是错的。**
> 源码 `ItemRenderer`（`renderStatic` 内部实际调用的那个 `render`）L123 就是
> `ClientHooks.handleCameraTransforms(...)`，**会应用**；L155-157 也**会**处理 BEWLR
> （`renderByItem`）。当时真正的问题只是**滚转 / 轴选择没标定**（"风扇叶片"= 长轴已对、板面躺），
> 我却误判成"transform 没生效"，一路去改立正，于是四轮全废。

| # | 日期 / commit | 方案 | 结果 |
|---|---|---|---|
| 1–2 | 09-24 ×2、09-28 ×1 | 应用手持 transform + 抵消 FIXED + 手算/运行时立正补偿（`+100°` / `+10°` / `rotationTo`） | 原版**平躺** |
| 3 | 09-29 · `90e0d84` | 手写手持 transform + context 传 `NONE` + 运行时 rotationTo 立正 + 滚转送**切线** + 实测标定 `-90°` | 原版**姿态正确**✅，但**原子分解机仍歪** ❌ |
| 4 | 09-29 · `93ae2b1` | **几何实测**：BakedQuad 顶点 → 协方差 → PCA 定长轴 / 板面法线（含简并检测兜底） | 姿态**怪异** ❌ |

**这四轮换来的硬事实（比结论更有价值）**：

- 🔴 **本环境（sodium/iris）`renderStatic` 不应用 display transform** —— 调试打印证实：
  API 能读到正确值（`tpRot=(0,-90,55)`、`tpScl=0.85`），但换 `FIXED` / `THIRD_PERSON_RIGHT_HAND`
  视觉**毫无变化**。⇒ 凡是"交给 MC 应用 transform"的方案**都不成立**。
- 🔴 **原版 2D 物品模型在几何上没有唯一长轴**：顶点铺满整个 `1×1` 方板 ⇒ x / y 方差相等
  ⇒ PCA **简并**（主成分不唯一）。⇒ **纯几何推断对原版不可行**，
  除非改读**贴图 alpha 分布**去算"不透明像素的主方向"（未尝试，是唯一还没走的路）。
- ⚠️ **立正 / 滚转 / 自转三者强耦合**：改一次链路就得重新标定滚转 ——
  实测差 `-90°`，且与射线模式 `RAY_ROLL_FIX = +270°` **数值一致** ⇒ 同一处系统性偏差。
  单独调其中一个必然顾此失彼（"连续两轮毫无改善"就是这么来的）。
- ⚠️ 板面法线必须走**两步**变换（`handRot` + 立正）才算得对；只算一步 ⇒ 滚转全错。
- ⚠️ 滚转目标是**切线**不是 `normal`（取 `normal` ⇒ 板面 ∥ 圆平面 = "像风扇叶片"）。

**⇒ 事后复盘（2026-09-30 更新）**：这四次失败的**真正教训**只有一条 ——
当时问题出在**滚转没标定**，我却误判成"transform 没生效"，在立正上反复折腾。
而**"走手上那条路"的方向本来就是对的**，最终也正是靠它解决的（见 §7.4）。

### 7.4 ✅「走手上那条路」—— 想法成立，已全线采纳（2026-09-29 提出，09-30 落地）

用户的直觉：**「明明在玩家手里姿态都是对的」⇒ 那就走【手上渲染】那条路。**

✅ **已验证并全线落地** —— 实现比预想的更简单：不必换用
`ItemInHandRenderer#renderItem`（那是第一人称手部入口），
**直接把 `renderStatic` 的 context 换成 `THIRD_PERSON_RIGHT_HAND` 即可**：
`renderStatic` 内部本来就会 `handleCameraTransforms` 应用 display transform（源码 L123）。

- 首个验证（原子分解机）：**歪 45° → "横平竖直" → 绕 Y −90° 立正 → 完全正确**（路线 A）
- 随后**路线 B（原版 + 普通模组）也统一到同一条链**（见 §7.2 最终链路）：
  立正角由 handheld `rotation` **推导**（绕 X +10°，非 90° 量级），滚转运行时算 + 标定 90°
  + `ringRoll` 翻转，自转轴固定 X —— 全部常量化，无任何逐物品"长轴/板面"约定
- ❌ **不需要造"隐形玩家"**：姿态由 display transform 决定，与"谁拿着"无关
- ⭐ **通用解的正确分工**：**MC 负责"每件物品各自摆正"，我们只负责"整体转向径向"**
- 📌 **踩坑记录（统一过程）**：立正曾误用 +100°（把长轴转成 ⊥ 径向）；滚转曾误设为常量
  （板面朝向随位置漂移 ⇒ "板面躺着"系统性出现）；`ringRoll`（0/π 翻转）曾误删
  （开锋朝向左右不一致）。**每个现象都有明确的几何解释，别靠反复试。**

**连带坑（已修）**：重构时 `fixedPose` 只在路线 B 分支赋值、路线 A 分支却引用它
⇒ 环上有 BEWLR 物品时 `renderBackRing` 第一帧 NPE，**进存档即崩**（crash report 实锤）。
两个分支必须各读各的。

---

### 8.1 属性镜像：让饰品增益生效（✅ 已实测 · 2026-09-24）

**问题**：饰品模组（Curios 等）的加成是往**玩家实体**的 `AttributeMap` 挂 `AttributeModifier`，
而活工具/活武器借 **FakePlayer** 出手 —— 它是另一个实体、另有一本属性账 ⇒ 主人的增益**全部读不到**。

**解法**（`LivingToolFakePlayer#syncOwnerAttributes`，借力不换人 —— 不换玩家本体执行，
原因见"为什么不能改用玩家本身"）：把主人身上白名单属性的修饰符**复制**过来（transient）：

| 项 | 说明 |
|---|---|
| 白名单 | `ATTACK_DAMAGE` / `ATTACK_SPEED`（冷却按它换算 ⇒ 攻速饰品直接变快出手）/ `ATTACK_KNOCKBACK` / `MINING_EFFICIENCY` / `BLOCK_BREAK_SPEED` |
| ⭐ 排除主人**主手物品**贡献 | 否则主人手里那把的附魔会与活武器自己的（`equipTool` 装的）**叠加成双倍**。主手贡献用 `stack.forEachModifier` 可精确枚举（物品自带 + 附魔一并覆盖） |
| base 不动 | 攻击力/攻速以活武器自身为准，镜像的只是"额外的"部分 |
| **重算式** | 每次出手/精算前先清（记 `mirroredModifierIds`）后装 ⇒ 主人穿脱饰品下一刀自动跟上，实例共享不残留 |
| 调用点 | `replayAttack` / `replayDig` / `replayUse` / 辅助挖掘 `digSpeed` —— **活工具与活武器同时受益** |

**边界（诚实）**：只覆盖**属性型**饰品。事件型（按实体实例/饰品槽判定的）吃不到——
那类认"玩家对象本身"，镜像救不了；按 **UUID** 判定的事件型本来就生效（FakePlayer UUID = 主人）。

#### 🔴 属性镜像竞态：「特定活武器永久白板化」（2026-09-30 修复）

**症状**：某一把特定的活武器攻击频率异常快（0.25s 一刀）、伤害恒为 **1**（= 徒手基础值），
且因高频攻击常驻拉仇恨（怪对假玩家复仇 → 宿主位置）；其它活武器完全正常。

**根因**（镜像清理按 **id** 删 + equipTool 同物品不补回的复合）：

```
主人的属性刷新在【实体 tick】（handleEquipmentChanges），而主手换物品发生在
【容器点击】（tick 之间处理）⇒ 二者相差一整个 tick 的竞态窗口：
  窗口内主人地图仍带旧武器的 bd/bs（minecraft:base_attack_damage/speed），
  而主人主手已是新物品 ⇒ 镜像把它们当"非主手来源"镜像到假玩家【并记录 id】
  ⇒ 下一轮镜像清理按 id 删掉它们 —— 与活武器自身修饰符【同 id！】
  ⇒ equipTool 的 changed=false（同一把武器）⇒ 永不补回 ⇒ 永久白板
```

**修法（两道）**：① `equipTool` 属性修饰符改为**每次摘旧装新**（幂等自愈，代价可忽略），
`changed` 门控只保留给位置类附魔效果（那个每 tick 重复触发会叠加）；
② 镜像排除集追加**假玩家当前手持物品自己的修饰符 id** —— 同名 id 一律不镜像/不记录
（现实不存在"与武器同 id 的非主手来源"，损失可忽略）。

### 8.1.1 生效条件：只看主人，不看宿主（2026-09-29 实测澄清）

镜像发生在**回放层**（`replayAttack` / `replayDig` / `replayUse` 每次调用开头都先
`syncOwnerAttributes`），不在宿主层 ⇒ **与武器/工具放在哪种宿主里无关** ——
普通箱子、玩家背包、掉落物形态在条件满足时**同样吃满**（2026-09-29 用户实测确认）。
原理链条：

```
活化时 setLiving(stack, true, owner) 把主人 UUID 写进 LIVING_TOOL_OWNER 组件
  ⇒ 该组件【持久随物品走】，物品迁移到任何宿主都还在
  ⇒ 任意宿主形态的回放第一步：FakePlayerCache.get(level, getToolOwner(stack))
  ⇒ fake.syncOwnerAttributes()：按 UUID 找到主人 → 复制白名单修饰符 → 出手时生效
```

| 条件 | 判据 |
|---|---|
| ✅ 生效 | 主人**在线** 且 与武器在**同一维度**（`getPlayerByUUID` 遍历的是**本维度**玩家列表，`EntityGetter#L199`）|
| ❌ 静默跳过 | 主人离线 / 在别的维度 / 栈上无主人记录（FALLBACK_UUID）⇒ 无身可借 |

> ⚠️ **旧表述勘误**：本节曾写「主人离线（容器/掉落物形态）⇒ 无身可借」——
> 把宿主形态与主人在线混为一谈，会让排查者误以为容器/掉落物形态天然吃不到镜像。
> 宿主形态**不是**条件；**排查"某形态没吃到饰品加成"时，先查主人是否在线且同维度，
> 再查栈上有没有主人 UUID —— 与宿主无关**。

> 「为什么不能改用玩家本身」的完整论证（`Player#attack` 无武器参数、会重置玩家冷却、
> 消耗玩家饱食度、扣错耐久），见 2026-09-24 对话存档 —— 核心是 `Player#attack()` 自洽读 `this`。

---

## §8 兼容性（已验证，附源码依据）

| 项 | 状态 | 依据 |
|---|---|---|
| 原版剑 / 斧 / 重锤 | ✅ | 在 `WEAPON_ENCHANTABLE` 内 |
| **锋利 / 击退 / 火焰附加** | ✅ **已实测**（2026-09-24） | 属性类走 `ItemStack#forEachModifier`（**内含** `EnchantmentHelper.forEachModifier`）；<br>效果类走 `Player#attack` 内的 `EnchantmentHelper.doPostAttackEffects` |
| **经验修补** | ✅ **已实测**（2026-09-24） | 反证耐久走的是原版管线（`hurtEnemy` → `hurtAndBreak`） |
| 横扫 / 暴击 | ✅ | 需冷却满（`f2 > 0.9F`）⇒ 依赖 §5 的手动推进 |
| 耐久 | ✅ | `Player#attack` 内调 `itemstack.hurtEnemy(...)` ⇒ 走 `hurtAndBreak` 三层 |
| 模组"不毁"附魔 | ✅ | 同上（`living-tool-tech.md` §2.4） |
| **位置类附魔效果**（`EnchantmentLocationBasedEffect`） | ❌ **未复刻** | 原版在 `handleEquipmentChanges` 里还调<br>`EnchantmentHelper.runLocationChangedEffects` / `stopLocationBasedEffects`，<br>我们的 `equipTool` 只复刻了修饰符部分。<br>⚠️ 原版内置几乎不用，**主要为模组服务** |
| `AttackEntityEvent` | ✅ | `CommonHooks.onPlayerAttackTarget`（§4.3） |
| `Item#onLeftClickEntity` | ✅ | 同上，同一钩子里 |
| 领地 / 保护插件 | ✅ | FakePlayer UUID = 主人 |
| 自我录制 | ✅ | `isFakePlayer()` 排除 |

### 已知副作用与限制（不修 —— 2026-09-24 用户决定）

#### ① 容器 / 掉落物形态：射线会整体下移约一格 ⇒ 可能打不到

**同一条记忆，在容器里会比在玩家身上"低一格"。**

| 宿主形态 | 射线起点（`LivingToolFunction#resolveOrigin`） | 相对高度 |
|---|---|---|
| 玩家 | `getEyePosition()` | ≈ 脚底 **+1.62** |
| **容器** | `Vec3.atCenterOf(pos)` | 方块中心 **+0.5** |
| 掉落物 | 碰撞箱中心 | 更低 |

而记忆存的 `offset` 是录制时的「**玩家眼睛 → 目标**」⇒ 换到容器后射线**整体下移约 1.1 格**：

```
录制时：眼睛(1.62) ──斜向下──> 怪(0.9)         ✅ 命中
容器里：中心(0.5)  ──同样的斜向下──> (-0.15)   ❌ 钻进地下
```

⇒ 射线**先撞到地面** ⇒ 被 §4.2 的隔墙检测判成「方块更近」⇒ **放弃攻击**。

> **实测判据（2026-09-24）**：把容器**垫高一格**即可命中 ⇒ 确认是高度差，不是方向问题。
> 另一个伴生现象：需要别的活武器先把怪打一下（击退 / 移动）后，这把才打得到 ——
> 因为怪被移动后进了射线。
>
> **决定：不修。** 若将来要修，最干净的是**攻击回放时把 `origin` 抬到与录制口径一致的眼睛高度**
> （只影响攻击、不动挖掘，约 10 行）；
> ❌ 不要「延长射线」—— 那与挖掘侧 L15「纯净射线」的口径不一致。
>
> 📌 **为什么挖掘侧没事**：`scanForTarget` 是**沿射线逐格扫描找方块**，平移后仍命中路径上的方块；
> 而攻击找的是**特定位置的实体**，对平移敏感。

#### ② 怪物会把仇恨记到活武器身上

表现为怪冲着活武器（宿主位置）来。

```
fake.attack(怪)
   → 原版生物 AI 自动 setTarget(fake)          ← 仇恨是真的，记在 FakePlayer 头上
   → 但 fake 的位置被我们 setPos 成【宿主位置】  ← 见 replayAttack
   → 怪朝宿主位置移动 ⇒ 看着像"恨上了那把活武器"
```

⚠️ **加重因素**：`LivingToolFakePlayer` **从不加入世界**
（`LivingToolFakePlayerCache` 只 `computeIfAbsent` 创建，**没有** `addFreshEntity`），
且**无敌**（`L25`）⇒ 严格说怪可能在追一个"**不在世界、又打不死**"的目标。

> **决定：不修。** 影响主要是观感，实测未出现怪卡死。
>
> **若将来要修**（出现卡怪 / 明显异常），做法是在 `replayAttack` **出手之后**把仇恨转移：
> ```java
> if (hit.getEntity() instanceof Mob mob) {
>     ServerPlayer owner = level.getPlayerByUUID(ownerUuid);
>     mob.setTarget(owner != null ? owner : null);   // 主人不在场 ⇒ 清掉
>     mob.setLastHurtByMob(owner);
> }
> ```
> ⇒ 怪转而来追主人，符合"活武器替主人打架"的设定；约 10 行。
>
> 📌 参考：Create 的 Deployer 用同样的 FakePlayer 方案，**也没处理这个**。

🔗 **法杖 / 枪械 = 联动功能（非主线）** —— 靠走官方入口自然兼容，见 §0「需求范围」；
**不属于"未覆盖的缺陷"**，故不列在 §9 的未实现表里。

---

## §9 未实现（⚠️ 别当成现状）

| 项 | 阻塞原因 |
|---|---|
| **蓄力型**（弓 / 弩 / 三叉戟） | 需「开始 → 持续推进 → 释放」状态机；FakePlayer 不 tick ⇒ **只会拉弓、射不出去** |
| **PVP** | 需先定义"敌对关系"判据；现为硬排除所有 `Player` |

### 辅助攻击（已实现 · 2026-09-24）

**玩家打中怪 ⇒ 背包里【无记忆】的活武器一起出手**（`LivingWeaponAssist`）。

| 环节 | 做法 |
|---|---|
| 触发 | `LivingDamageEvent.Post`（**伤害结算后**），且 `source.getEntity()` 是**真实玩家** |
| 取武器 | `LivingToolRecorder#isAssistWeapon`（无记忆的活武器）；**跳过主手槽位**（玩家自己已挥） |
| 射线 | **临时构造**「玩家眼睛 → 怪物中心」（无记忆 ⇒ 现算），复用 `replayAttack` |
| 写回 | `inventory.setItem(slot, result.tool())` |

#### 🔴 关键：必须压制无敌帧，否则全部白打

原版生物受击后 `invulnerableTime = 20`、`> 10` 即**完全免疫** ⇒
**玩家那一刀已经占掉了这个窗口** ⇒ 活武器随后打的全部被吞
（**伤害 / 附魔 / 击退都不触发**）。

⇒ 用官方接口把辅助攻击期间的无敌压到 0：

```java
@SubscribeEvent
static void onIncomingDamage(LivingIncomingDamageEvent e) {
    if (!active) return;
    e.getContainer().setPostAttackInvulnerabilityTicks(0);   // ⭐ 官方入口
}
```

⭐ **`active` 标志只在辅助攻击循环期间为 true** ⇒
玩家自己那一刀、以及**自主模式**（有记忆、每 tick 打）都不受影响 —— 否则 DPS 会失控。

#### ⚠️ 官方接口**救不了当前这一刀** ⇒ 必须再压一道

`hurt()` 的确切顺序（行号源自 `LivingEntity` 源码）：

```
1152  damageContainers.push(...)
1153  onEntityIncomingDamage(...)                     ← 我们的事件：设 container = 0
...
1190  if (invulnerableTime > 10 && !BYPASSES) ...     ← 免疫判断：读【当前 invulnerableTime】
1202  invulnerableTime = getPostAttackInvulnerabilityTicks()   ← 才用上我们设的 0
```

⇒ 官方接口改的是「**本次之后**」的无敌时间，而 `1190` 的免疫判断读的是**当前值** ⇒
单靠它，**第 2 把会被吞**（实测：只有背包最靠前的一把生效）。

⇒ 故循环里每把攻击**前**再直接压一道（`invulnerableTime` 是 `public` 字段，无需反射 / AT）：

```java
if (target.invulnerableTime > 10) {
    target.invulnerableTime = 10;   // 进 hurt() 会先 -1 ⇒ 9 ⇒ 跨过 `> 10` 的免疫阈值
}
```

> 📌 两者叠加是幂等的：官方入口负责"之后"，直接兜底负责"当前"。
> ⚠️ 直接改字段属于野路子（见 `living-tool-tech.md` §13），此处是**确认官方接口不足以覆盖**后的补充 —— 别把它当成首选方案。

#### 另外两处保护

| 保护 | 原因 |
|---|---|
| `target.isDeadOrDying()` 就 `break` | 怪中途死了还继续挥 ⇒ **不造成伤害、不触发附魔，却仍扣耐久** |
| 取消击退（`LivingKnockBackEvent`） | N 把各推一次 ⇒ 怪会被**崩飞**，不像"围殴"（照铁魔法做法） |

> ⚠️ **更正 [`../idea.md`](../idea.md) §1.7 的口径**：那里写「不加伤害闸门 ⇒ 接受伤害线性叠加」。
> 实际上**原版本来会用无敌间隔封顶**（根本不会叠加）；
> 是**我们主动压制无敌帧**之后才真正叠加的 —— 别把因果搞反。

### 记忆清除（已实现 · 2026-09-24）

⭐ **判据：这一刀没打到怪 ⇒ 清掉攻击记忆。**

| 触发 | 机制 |
|---|---|
| 左键**挥向方块** | `PlayerInteractEvent.LeftClickBlock`（服务端，仅 `START`）⇒ `withoutAttack()` |
| 左键**挥空** | `LeftClickEmpty`（**仅客户端**）⇒ `ToolMemoryClearPacket(KIND_ATTACK)` ⇒ 服务端 `withoutAttack()` |
| 右键空气 / 物品 | 本期**不做**（近战用左键）；施法类那期再补 |

> ⚠️ 两条**都套 L44 保护期**（写完记忆 20 tick 内不清除）——
> 打完怪玩家往往还会顺势挥几刀，不保护的话刚录的记忆立刻被自己清掉。
> 活工具侧这个坑**已实测踩过**，活武器直接照搬。

⇒ 由此得到的手感：**挥一刀空 / 挥向方块就能在「打架」和「挖矿」之间切换**
（活斧子同时是工具与武器，清掉 attack 后按 S2 自然回到挖掘模式）。

⚠️ **实现副作用（记一下）**：`ToolMemoryClearPacket` 原是 `boolean dig`，
装不下第三种 ⇒ 已扩展成**三态 int**（`KIND_DIG=0` / `KIND_USE=1` / `KIND_ATTACK=2`）。
服务端按 `kind` 分派，且**判据分开**：清挖掘/交互要求手持**活工具**，清攻击要求手持**活武器**
（活剑不是活工具，合并判据会漏）。

### 环成员口径（已统一）

```
环成员   = 无记忆的（活工具 ∪ 活武器）   →  LivingToolRecorder#isAssistItem
辅助挖掘 = 无记忆的【活工具】            →  LivingToolRecorder#isAssistTool
```

⭐ 这两段**曾经在 3 处各写一遍**（`LivingToolModelRenderer` / `LivingToolPlayerSync` / `LivingToolAssist`）
⇒ 加武器时必须同步改 3 处 ⇒ 已收敛成 `LivingToolRecorder` 里的两个方法。**不要再复制一份。**

---

## §10 实测清单

| # | 操作 | 预期 |
|---|---|---|
| 1 | 活化一把剑（无记忆） | 出现在**背后的环上**（不再是"隐身"） |
| 2 | 手持活剑打一只怪 | 记忆录上；F3+B 看到**品红射线** |
| 3 | 放进箱子 / 扔地上 | 它自己朝那个方向打怪 |
| 4 | 观察攻击节奏 | 按**剑的攻击速度**来，**不会每 tick 一刀**（冷却生效） |
| 5 | 隔着墙 | 打不到墙后的怪 |
| 6 | 旁边有盔甲架 / 自己的狼 / 船 | **都不会被打** |
| 7 | 转身 / 移动 | 整圈刚性跟随，不散架 |

### 判据（可复算）

- **冷却是否生效**：活剑应该"隔一会才砍一刀"，而不是每 tick 掉血。
  若每 tick 都打 ⇒ `getAttackStrengthScale()` 的 override 没生效。
- **伤害是否打折**：满冷却一刀应约等于玩家手持同剑的伤害。
  若明显偏低（约 20%）⇒ 冷却没推进。


# 活武器技术文档

> **状态**：核心链路已实现（2026-09-23），**仅限近战（剑类）**；施法 / 射击 / 蓄力类**未实现**（见 §9）。
>
> ⭐ **共享基础设施不在这里** —— FakePlayer、记忆射线模型、辅助环、挖掘/交互回放、DataComponent 编解码、
> 渲染管线全部见 [living-tool-tech.md](living-tool-tech.md)。**本文只写武器侧的差异**，共享部分一律用指针，
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

## §5 🔴 攻击冷却：必须手动推进

**这是活武器最容易踩、且症状最隐蔽的坑。**

```java
// Player#attack 内部
float f2 = this.getAttackStrengthScale(0.5F);
f  *= 0.2F + f2 * f2 * 0.8F;     // 基础伤害
f1 *= f2;                         // 附魔伤害同样打折
boolean flag4 = f2 > 0.9F;        // 击退 / 横扫 / 暴击也要满冷却
```

而 `getAttackStrengthScale` 读的是 **private** 的 `attackStrengthTicker`，它由 `Player#tick()` 自增 ——
**`FakePlayer#tick()` 是空实现**（`L27` 一脉）⇒ 该值**永远是 0** ⇒

> 🔴 **不推进 = 活武器永远只有 20% 伤害，且永远触发不了横扫与暴击。**

### 解法：override 读数，不用反射、不用 AT

`LivingToolFakePlayer` 覆写：

```java
@Override public float getAttackStrengthScale(float adjustTicks) { return this.attackStrengthScale; }
public void setAttackStrengthScale(float scale) { ... }
public float getAttackCooldownTicks() {
    float delay = this.getCurrentItemAttackStrengthDelay();
    if (!Float.isFinite(delay) || delay < 1.0F) return 1.0F;   // 见下方坑 ②
    return delay;
}
```

推进在 `replayAttack` 里按**世界轴 tick 差**重算（同 `LivingToolProgress` 的重算式，见 `living-tool-tech.md`）：

```java
float scale = last == null ? 1.0F : (float) (now - last.tick()) / cooldown;
fake.setAttackStrengthScale(scale);
if (scale < 1.0F) return null;   // 还在冷却中，本次不出手
```

| 为什么不用 | 理由 |
|---|---|
| 反射改 private 字段 | **生产环境会因混淆失效** |
| Access Transformer | 要新增配置；能不用就不用 |

### 🔴 三个「静默失效」—— 症状都是「有记忆、有怪，但一刀都不打」

这类 bug 的共同特征：**不报错、不崩溃、就是不出手**，比崩溃难查得多。

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| ① | **首次冷却用 `(long) cooldown` 当 elapsed** | 冷却时长**常是小数**（剑攻速 1.6 ⇒ `1.0/1.6*20 = 12.5` tick）⇒ `(long)12.5 = 12` ⇒ `12/12.5 = 0.96 < 1` ⇒ 判成"冷却中" ⇒ **且这条路径不写 action ⇒ `last` 永远为 null ⇒ 永久死锁** | 没打过就直接给 `1.0F` |
| ② | **`ATTACK_SPEED` 为 0 ⇒ 冷却 = `Infinity`** | `Math.max(Infinity, 1)` 挡不住 ⇒ `elapsed / Infinity = 0` ⇒ 永远不满 | `!Float.isFinite(delay)` 兜底 |
| ③ | **隔墙检测没传 `hostBlocks`** | 容器形态下射线起点**埋在容器方块内** ⇒ 第一个命中的是"自己的家" ⇒ 永远判成隔墙 | 传 `hostBlocks`（与挖掘侧一致） |

> 📌 **通用判据**：凡是"某个闸门放行后才能推进状态"的循环，
> **必须检查「首次 / 无记录」那条路径能不能自己走通** ——
> 若它在放行前就 `return`，且 return 之前**不写状态**，就会**永久卡死**。

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
| 容器（箱子 / 末影箱） | `ContainerLivingItemHandler#processContext` | 按 `canApply` 分组 ⇒ ✅ 自动覆盖 |
| **玩家背包** | 同上 | ✅ 自动覆盖 |
| **掉落物** | `LivingItem#processItemEntityContainers` | 🔴 **独立前置过滤** —— **不走** `canApply` 分组 |

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
  `PULSE_TICKS`（6）与 `PULSE_SCALE`（0.15）
- **剑尖朝圆心**（`drawRing` 新增 `inward` 参数）：工具环是"柄朝圆心、头朝外"，
  攻击环相反（一圈剑指向中心）。⭐ 实现：`dir` 取反对齐（模型 +Y = 剑尖）；
  `inward` 时 yaw 偏移 π ⇒ +X 反向 ⇒ `ringRoll` 补 π（推导值，**若实测剑面反了就去掉**）
- **环存活窗口** `ATTACK_RING_TICKS = 20` 必须**大于**武器攻击冷却（剑约 12.5 tick）⇒
  连续攻击时环**留在原地反复脉冲**，不会"飞出去→收回"地闪
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


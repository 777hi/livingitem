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

> 📌 目标不是"做一把活剑"，而是**一套「代玩家出手」的抽象层**（用户 §0 原话）。

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

**命中点取 `target.getBoundingBox().getCenter()`**（不是眼睛 / 脚底）—— 最能代表"打在身上"，
回放时沿该射线做实体检测也最容易命中。

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

⚠️ 推论：活斧子若同时有挖掘记忆与攻击记忆 ⇒ **只走攻击**（不会同时挖）。

---

## §7 渲染

| 项 | 说明 |
|---|---|
| 攻击射线颜色 | **品红**（`ATTACK_R/G/B`），区别于挖掘的橙红、交互的青蓝 |
| 🔴 `RayMemory` 桥接 | `LivingToolModelRenderer#renderOne` 里把 `AttackMemory` 转成 `RayMemory(offset, null)` 复用渲染路径 |

> ⚠️ **少了这个桥接会 NPE**：只带攻击记忆的活剑 `dig` / `use` 都是 null，
> 原来的 `memory.dig() != null ? dig : use` 会拿到 null 后调 `endpointFrom` ⇒ **崩溃**。

容器形态没有同步"有没有生物命中" ⇒ 攻击射线**恒按命中画**（实心）。

动画按 2026-09-23 决定**暂缓**，初期直接复用活工具同款。

---

## §8 兼容性（已验证，附源码依据）

| 项 | 状态 | 依据 |
|---|---|---|
| 原版剑 / 斧 / 重锤 | ✅ | 在 `WEAPON_ENCHANTABLE` 内 |
| 锋利 / 击退 / 火焰附加 / 横扫 / 暴击 | ✅ | `fake.attack()` 走原版管线 |
| 耐久 | ✅ | `Player#attack` 内调 `itemstack.hurtEnemy(...)` ⇒ 走 `hurtAndBreak` 三层 |
| 模组"不毁"附魔 | ✅ | 同上（`living-tool-tech.md` §2.4） |
| `AttackEntityEvent` | ✅ | `CommonHooks.onPlayerAttackTarget`（§4.3） |
| `Item#onLeftClickEntity` | ✅ | 同上，同一钩子里 |
| 领地 / 保护插件 | ✅ | FakePlayer UUID = 主人 |
| 自我录制 | ✅ | `isFakePlayer()` 排除 |

**未覆盖**：模组法杖 / 枪械（见 §9）。

---

## §9 未实现（⚠️ 别当成现状）

| 项 | 阻塞原因 |
|---|---|
| **模组法杖 / 枪械** | ① 法杖不在 `WEAPON_ENCHANTABLE`（铁魔法靠 `casting_implement` **组件**识别）<br>② 法杖是**右键施法**，而回放只调 `attack()` ⇒ 需按物品类型**分派**到 `use()` 分支（**该分支已存在**）<br>📁 参考 `libs/src/irons-spells-n-spellbooks-1.21` |
| **辅助攻击**（无记忆时帮玩家打） | 未做；设计见 [../idea.md](../idea.md) §1.7 |
| **蓄力型**（弓 / 弩 / 三叉戟） | 需「开始 → 持续推进 → 释放」状态机；FakePlayer 不 tick ⇒ **只会拉弓、射不出去** |
| **PVP** | 需先定义"敌对关系"判据；现为硬排除所有 `Player` |
| **清除攻击记忆** | ⚠️ **未实现**（方案已定，见下方小节）。`LivingToolMemory#withoutAttack()` 已就位，但**没有任何调用方** |

### 记忆清除（方案已定 · 待实现）

⭐ **判据：这一刀没打到怪 ⇒ 清掉攻击记忆。**

| 触发 | 机制 | 备注 |
|---|---|---|
| 左键**挥向方块** | `PlayerInteractEvent.LeftClickBlock`（服务端） | 零网络改动 |
| 左键**挥空** | `LeftClickEmpty`（**仅客户端**）⇒ 需发包 | 与活工具 L43 同款 |
| 右键空气 / 物品 | 本期**不做**（近战用左键）；施法类那期再补 | R4 录制不分左右键，但清除目前认左键 |

⇒ 由此得到的手感：**挥一刀空就能在「打架」和「挖矿」之间切换**
（活斧子同时是工具与武器，清掉 attack 就自然回到挖掘模式）。

> ⚠️ **必须套 L44 保护期**（写入记忆后 20 tick 内不清除）——
> 打完怪玩家往往还会顺势挥几刀，不保护的话刚录的记忆立刻被自己清掉。
> 活工具侧这个坑**已实测踩过**（`L44`），活武器直接照搬。

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


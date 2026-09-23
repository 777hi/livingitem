# 活武器

> **状态**：设计中（2026-09-22 起）。⚠️ **本文是「设计探讨层」** —— 记录原始需求、官方入口调研、
> 待决问题池与探讨日志，**描述的是计划 / 未决，不是现状**。
>
> ⭐ **活武器「已实现」的部分（近战核心链路）见 [`docs/tech/living-weapon-tech.md`](tech/living-weapon-tech.md)**
> —— 那边才描述现状。本文只在结论被定案后保留探讨过程。
>
> 📄 活工具的设计探讨记录（问题池 + 定案）：[`docs/system-design/living-tool-design.md`](system-design/living-tool-design.md)
> 📄 活工具技术文档：[`docs/tech/living-tool-tech.md`](tech/living-tool-tech.md)
>
> 结构：`§0` 原始需求 → `§1` 核心原则 → `§2` 官方入口盘点 → `§3` 铁魔法调研 → `§4` 待决问题池 → `§5` 探讨日志。

---

## §0 原始需求（用户原话）

> 「比起功能，我更想实现的是**攻击方式的逻辑**。
> 我打算联动其它模组的**法杖、枪械**之类的。**套用同一套攻击逻辑**，以此实现多样的玩法。」

**⇒ 目标不是"做一把活剑"，而是一套「代玩家出手」的抽象层** —— 用同一套逻辑驱动任何"能攻击 / 能施法"的物品（原版剑、模组法杖、模组枪械）。

---

## §1 核心原则：活武器**不实现**任何攻击逻辑，只负责「代玩家出手」

```
活武器 = 记忆（朝哪 + 什么操作）
       + 回放（摆好位置与朝向 → 调物品的【官方入口】）
```

**打多少伤害、发什么子弹、施什么法 —— 全由那个物品自己决定。**

| 活化的东西 | 谁决定效果 |
|---|---|
| 原版剑 / 斧 | 原版 `attack()`（锋利 / 击退 / 火焰附加自动生效） |
| 模组法杖 | 法杖自己的施法逻辑 |
| 模组枪械 | 枪自己的射击逻辑（弹药 / 后坐力 / 伤害） |

### 由此推出三条铁律

1. **不识别具体模组** —— 不问"这是不是法杖"，只把"玩家做了一次右键"原样重放出去；**识别是对方的事**。
2. **走官方入口** —— 只用 NeoForge / 原版钩子。对方只要也守官方口径，就自动兼容。
3. **耐久走原版管线** —— 见 §2.4 的三层。

### 1.5 记忆录制规则（用户 2026-09-22 定）

| # | 规则 |
|---|---|
| **R1** | 手持活武器**攻击生物** ⇒ 记录「**朝向 + 距离**」的射线 |
| **R2** | **蹲下**攻击 ⇒ 同上，但额外**记住生物类型**（完全类比 `L4` 的"蹲下记方块类型"） |
| **R3** | **击杀**生物 ⇒ **记仇**：以宿主为中心、半径 **32 格**（与渲染 / 同步距离一致）内出现同种生物 ⇒ **自动追踪攻击** |
| **R4** | ⭐ **不区分左右键** —— 有的武器左键攻击、有的右键攻击 ⇒ **只按「造成伤害」与「击杀」判定录制时机** |
| **R5** | 一次攻击命中多个生物 ⇒ 记**第一个**（怎么简单怎么来） |

**录制入口**

| 规则 | 事件 |
|---|---|
| R1 / R2 / R5 | `LivingDamageEvent`（造成伤害时） |
| R3 | `LivingDeathEvent`（击杀时） |

**怎么认出"是哪把武器"**：`DamageSource#getWeaponItem()`（近战有效）。

> ⭐ **R4 是最关键的一笔** —— 它绕开了"这武器用哪只手"的判断：
> 不管左键还是右键，**只要"这把武器造成了伤害 / 击杀"就录**。

#### ⚠️ 三个坑（预判）

| # | 坑 | 处理 |
|---|---|---|
| 1 | **远程武器**（弓）`directEntity` 是**箭**，不是玩家 ⇒ `getWeaponItem()` 返回 null | fallback：`source.getEntity()`（射箭者）+ 取主手 |
| 2 | **非攻击伤害**（火焰附加 / 摔落 / 中毒 / 药水）也会触发 `LivingDamageEvent` | 过滤：只认"由这把活武器造成的" |
| 3 | 🔴 **回放时会自我录制** —— FakePlayer 造成的伤害 / 击杀**照常触发这两个事件** | 必须 `isFakePlayer()` 排除（**同 `L39`**，否则越打越偏） |

### 1.6 活武器的判据（2026-09-23 定）

⭐ **用原版「可附魔类别」标签识别武器** —— 这是官方分类，模组物品通常也会正确归类。

| 标签 | 覆盖 |
|---|---|
| `ItemTags.WEAPON_ENCHANTABLE` | 剑 / 斧 / 重锤… |
| `ItemTags.BOW_ENCHANTABLE` | 弓 |
| `ItemTags.CROSSBOW_ENCHANTABLE` | 弩 |
| `ItemTags.TRIDENT_ENCHANTABLE` | 三叉戟 |
| `ItemTags.MACE_ENCHANTABLE` | 重锤 |

```java
isLivingWeapon(stack) = isLivingItem(stack) && (以上任一标签)
```

**⇒ 活石头不在任何武器标签里 ⇒ 不会被当成武器** ✅

> 📌 用户定调：*"我想活化的不只是几把剑，而是整个武器战斗系统"* ⇒ 故取广义（弓 / 弩 / 三叉戟 / 重锤全算）。
>
> ⚠️ 这些标签本质是"**可附魔类别**"，未必 100% 等价于"武器"，但它胜在是**官方口径**（模组通常也往这些标签里塞自家物品）⇒ 比 `instanceof SwordItem` 之类可靠得多。

### 1.7 辅助攻击（无记忆时）—— 类比辅助挖掘（2026-09-23 定）

**无记忆的活武器：玩家攻击时，全部朝目标各攻击一次**（与"活工具帮玩家挖方块"同构）。

| 项 | 决定 |
|---|---|
| 伤害闸门 | ❌ **不加**（不做伤害折扣 / 参与上限 / 冷却）⇒ 接受伤害线性叠加 |
| 天然成本 | 每把参与的各扣 1 点耐久（同辅助挖掘） |
| 排布 | **环形**（与挖掘环同款），避免多把叠在一点 |
| 去程 | **瞬现**（不做插值 —— 要的就是"突然出现"的突兀感） |
| 动画 | **暂用活工具同款**（挖掘转圈 / 交互脉冲） |

#### 谁在背后的环上（2026-09-23 定）

```
环的成员 = 无记忆的（活工具 ∪ 活武器）     ← 决定"看不看得见"
辅助挖掘 → 只有活工具
辅助攻击 → 只有活武器
```

| 物品 | 在环上 | 辅助挖掘 | 辅助攻击 |
|---|---|---|---|
| 活镐 / 活铲 / 活锄 | ✅ | ✅ | — |
| 活剑 / 活弓 / 活重锤 | ✅ | — | ✅ |
| **活斧子**（两者都是） | ✅（**只算一次**） | ✅ | ✅ |
| 活石头（都不是） | ❌ | — | — |

> 🔴 **现状问题**：现在 `isAssistTool` 只认 `isLivingTool`（`ItemAbility`）⇒
> **无记忆的活剑既不在环上、也不辅助挖掘、也不攻击 ⇒ 完全"隐身"** ❌ ⇒ 需改成员判断。

#### 📌 动画的后续构想（暂缓 —— 先做功能逻辑）

- **去**：在环上**快速缩小消失** → 在目标位置出现（视觉语言："工具缩小后瞬移到目标"）
- **回**：加飞回动画（参考 `renderOne` 已有的 `RETURN_TICKS = 8` + `easeOut` 缓出）
- **攻击**：刺击（快速出去 + 缓回）+ squash & stretch 形变

> 用户 2026-09-23 决定：**先实现功能逻辑，渲染 / 动画部分之后再做**；
> 活武器动画初期直接复用活工具同款。

---

## §2 官方入口盘点（2026-09-22 核 NeoForge 21.1.249 源码）

### 2.1 左键（破坏）—— 已全覆盖 ✅

`ServerPlayerGameMode#handleBlockBreakAction`

| 步骤 | 官方钩子 | 我们 |
|---|---|---|
| ① 事件 | `CommonHooks.onLeftClickBlock(player, pos, face, action)` | ✅ START / STOP / ABORT **三个都调** |
| ② 挥击 | `state.attack(...)` + `EnchantmentHelper.onHitBlock(...)` | ✅ |
| ③ 推进进度 | 原版靠 `tick()` ⇒ **FakePlayer 的 tick 是空实现**（`L27`） | ✅ 自己推进 |
| ④ 裂纹 | `level.destroyBlockProgress(...)` | ✅ |
| ⑤ 破坏 | `gameMode.destroyBlock(pos)` | ✅ 内部自带 `fireBlockBreak` + `removeBlock`（含耐久 `mineBlock`）+ 掉落 |

**⇒ 结论：左键没有类似 §2.3 那种遗漏。**

### 2.2 右键 · **方块**（`useItemOn`）—— 已覆盖 ✅

```java
CommonHooks.onRightClickBlock(player, hand, pos, hitVec);   // ① 事件
stack.useOn(new UseOnContext(...));                          // ② 物品实现
```

### 2.3 右键 · **空气 / 物品**（`useItem`）—— 2026-09-22 补齐 ✅

```java
InteractionResult cancelResult = CommonHooks.onItemRightClick(player, hand);   // ① 事件
if (cancelResult != null) return cancelResult;
stack.use(level, player, hand);                                              // ② 物品实现
```

> ⚠️ **这两个右击事件完全独立** —— `CommonHooks` 源码里 `onRightClickBlock` **不会**顺带 post `RightClickItem`。
> 所以原版是"按目标类型二选一"：**命中方块走 §2.2，没命中走 §2.3**。我们也照做。

### 2.4 耐久的官方三层管线

`ItemStack#hurtAndBreak` 内部依次：

| 层 | 作用 |
|---|---|
| ① `getItem().damageItem(...)` | **NeoForge 钩子** —— 模组的「不毁 / 永恒」附魔在这里把 amount 改成 0 |
| ② `EnchantmentHelper.processDurabilityChange(...)` | 原版耐久附魔 + **数据驱动**的减免 |
| ③ `entity.onEquippedItemBroken(item, slot)` | 破毁回调（核源码：只广播实体事件 ⇒ **损坏音效**，无副作用） |

**⇒ 推论**：只要走 `hurtAndBreak` / `mineBlock`，模组的"不毁"附魔**自动生效**；**不要用空 lambda 绕过第 ③ 层**（2026-09-21 修的就是这个）。

### 2.5 🔑 `faceTarget` —— 让 FakePlayer 看向记忆射线

法杖 / 枪械几乎都读 `player.getLookAngle()`（或 `getViewVector`）来决定朝哪施放。
**只 `setPos` 不设朝向 ⇒ 会朝 FakePlayer 的残留朝向施放。**

由 `LivingEntity#calculateViewVector` 反解（已实现在 `LivingToolReplay#faceTarget`）：

```
x = −sin(yRot)·cos(xRot)
y = −sin(xRot)
z =  cos(yRot)·cos(xRot)
⇒  xRot = −deg(asin(dir.y))
   yRot =  deg(atan2(−dir.x, dir.z))
```

另需 `setYHeadRot(yRot)` —— 部分模组读的是头部朝向。

### 2.6 攻击侧 —— `AttackEntityEvent` 与「三处同类漏洞」（2026-09-22 核源码）

#### ① `AttackEntityEvent` **不携带武器**

```java
public class AttackEntityEvent extends PlayerEvent implements ICancellableEvent {
    private final Entity target;      // 唯一字段
}
```

想知道武器只能 `event.getEntity().getMainHandItem()` —— ⚠️ 取的是"**当前**主手"，未必是"攻击时用的那把"。

#### ② 但**物品侧**明确知道

`Player#attack` 会把那把武器传进去：

```java
ItemStack itemstack = this.getMainHandItem();
flag5 = itemstack.hurtEnemy(target, this);     // ← 物品收到：「我用你打了它」
itemstack.postHurtEnemy(target, this);         // ← 打完再通知一次
EnchantmentHelper.doPostAttackEffects(...);    // ← 锋利 / 击退等附魔效果
```

**⇒ 模组的剑重写 `hurtEnemy` / `postHurtEnemy` 就能"认领"这次攻击。**
**⇒ 我们只需 `fake.attack(target)`，这些全部自动跑，无需我们介入。**

#### ③ 完整前置：`CommonHooks.onPlayerAttackTarget`

```java
public static boolean onPlayerAttackTarget(Player player, Entity target) {
    if (NeoForge.EVENT_BUS.post(new AttackEntityEvent(player, target)).isCanceled())
        return false;
    ItemStack stack = player.getMainHandItem();
    return stack.isEmpty() || !stack.getItem().onLeftClickEntity(stack, player, target);
}
```

⭐ **它做了两件事**：post `AttackEntityEvent` **＋** 调物品的 `onLeftClickEntity` 钩子（又一个官方扩展点）。

**⇒ 实现实体攻击时的正确写法**：

```java
if (!CommonHooks.onPlayerAttackTarget(fake, target)) return;   // ① 前置（事件 + 物品钩子）
fake.attack(target);                                            // ② 真正攻击
```

#### 📌 三处同类漏洞 —— 同一个模式

| 侧 | 外部事件层（易漏） | 内部实现层（我们直调） | 状态 |
|---|---|---|---|
| 右键 | `onItemRightClick` | `use()` | ❌ → **✅ 已补**（2026-09-22） |
| 左键 | `onLeftClickBlock`（START / STOP / ABORT） | `destroyBlock()` | ✅ 一直有 |
| **攻击** | **`AttackEntityEvent`**（经 `onPlayerAttackTarget`） | **`player.attack()`** | ✅ **无需补**（2026-09-23 核源码纠正） |

> 🔴 **2026-09-23 纠正**：上表原先标注攻击侧「❌ 待补」，**是错的**。
> 核 `Player#attack` 源码：它的**第一行**就是 `if (!CommonHooks.onPlayerAttackTarget(this, target)) return;`，
> 而 `onPlayerAttackTarget` 内部会 post `AttackEntityEvent` 并调 `Item#onLeftClickEntity`。
> ⇒ **直接调 `fake.attack(target)` 已经触发这两者**，再手动 post 会**重复触发**。
> 详见 [`tech/living-weapon-tech.md`](tech/living-weapon-tech.md) §4.3。
>
> ⚠️ 下面的「口诀」**对攻击侧不适用**（它只对真正不经 `Player#attack` 的路径成立）：

> **规律**：NeoForge 把"玩家交互事件" post 在**网络包处理层**；我们走"服务端直调" ⇒ **那一层永远不会自己跑**。
>
> **⇒ 口诀：凡是原版由「客户端发包」触发的交互，我们都必须手动补 post 对应的事件。**
>
> 这也是为什么「识别法杖」不重要、**走对分支**才重要 —— 同理，攻击时不识别武器，
> 只要补上 `onPlayerAttackTarget`，**武器自己会通过 `hurtEnemy` 认领这次攻击**。

### 2.7 ⚠️ 射线的长度上限（性能，2026-09-22 提出）

**问题**：`LivingToolReplay#scanForTarget` 以 `SCAN_STEP = 0.1` 格**逐格扫描** ⇒
循环次数 ≈ **10 × 射线长度**，且**每 tick、每把活工具都跑**。

| 射线长度 | 单把工具每 tick 的循环次数 |
|---|---|
| 4.5 格（原版默认交互距离） | ~45 |
| 32 格 | ~320 |
| **1000 格** | **~10,000** ❌ |

**⇒ 而且比 CPU 更糟的是**：每一步都要 `level.getBlockState(...)`，
**未加载的区块会被强制加载** ⇒ 超长射线会顺带**拉取大量区块**（I/O 灾难）。

> 📌 渲染侧**已有 32 格剔除**（`MAX_DISTANCE`），不受影响；
> **风险全在逻辑侧的扫描** —— 它现在**没有长度上限**。

**⇒ 防御（两处都要加，缺一不可）**

| 位置 | 做法 |
|---|---|
| **录制时** | 射线长度超过上限 ⇒ **截断**（或拒绝录制） |
| **回放时** | 扫描长度**再兜底一次** —— 防 NBT 被改 / 模组改大交互距离 |

**建议上限**：与 `MAX_DISTANCE`、`LivingToolHostSync.RADIUS`、`LivingToolPlayerSync.RADIUS` 全部对齐 ⇒ **32 格**。

**记仇模式（R3）**：球形搜索走 `level.getEntities(AABB)`，比逐格扫描轻，
但仍是**每 tick 每把** ⇒ 建议**降频**（例如每 5 tick 查一次）。

**待定（U 组）**

| # | P | 问题 | 倾向 |
|---|---|---|---|
| U1 | P0 | 射线长度上限定多少？ | **32 格**（与渲染 / 同步半径一致） |
| U2 | P0 | 超限时截断还是拒绝？ | **截断**（保住已有的方向信息） |
| U3 | P1 | 记仇的球形搜索要不要降频？ | **降频**（每 5 tick） |

### 2.8 原版 `clip` 的遍历逻辑（2026-09-23 核源码，已据此优化）

`Level#clip` 内部调 `BlockGetter#traverseBlocks` —— **体素遍历（DDA / Amanatides-Woo）**：

```java
// 每次循环跳到【下一个格子边界】，而不是走固定的一小段
double d9  = l == 0 ? MAX_VALUE : (double) l / d6;              // 沿 x 推进一整格所需的 t
double d12 = d9 * (l > 0 ? 1.0 - Mth.frac(d3) : Mth.frac(d3));  // 到下一个 x 边界的 t
while (d12 <= 1.0 || d13 <= 1.0 || d14 <= 1.0) { /* 取最早到达的轴，只推进那一格 */ }
```

| | 原实现（固定 `SCAN_STEP = 0.1`） | 官方（跳格子边界） |
|---|---|---|
| 复杂度 | O(长度 / 0.1) ⇒ **同一格重复访问约 10 次** | **O(穿过的格子数)** |
| 1000 格射线 | ~10,000 次 | **~1,700 次** |

⭐ **关键**：`tester` 返回 `null` 即继续下一格 ⇒ **黑名单（宿主自己）的能力不受影响**
⇒ 已于 2026-09-23 改用 `traverseBlocks`（commit `919cc0f`），并删掉不再需要的 `SCAN_STEP`。

> 📌 **教训**：需要"官方没提供的特性"时（这里是"跳过宿主方块"），
> **先看官方算法能不能拆开复用，而不是整块自己重写** —— 自己写很容易在性能/正确性上悄悄劣化。

---

## §3 铁魔法（Irons Spells 'n Spellbooks）调研结论

> 📁 `libs/src/irons-spells-n-spellbooks-1.21`（NeoForge 版，可直接参考）

### 3.1 类继承

```
Item → CastingItem (…/item/CastingItem.java:9) → StaffItem (…/item/weapons/StaffItem.java:7)
```

⚠️ **名字像法杖但不属于施法系统**：`StaffOfTheNines` 直接 `extends Item`；
`MagicSwordItem` / `HitherThitherWand` 是 `IPresetSpellContainer`（**携带法术**），不是施法触发器。

### 3.2 识别法杖的正确方式 = 数据组件

```java
// ComponentRegistry.java:43 —— irons_spellbooks:casting_implement（Unit）
// CastingItem 构造时自动挂上：super(pProperties.component(CASTING_IMPLEMENT, Unit.INSTANCE));

// 模组自己就是这么判断的（ServerPlayerEvents.java:152）
if (itemStack.has(ComponentRegistry.CASTING_IMPLEMENT)) { ... }
```

| 方式 | 评价 |
|---|---|
| ✅ `stack.has(<casting_implement>)` | **推荐** —— 模组自身判据，跨模组友好 |
| ❌ `instanceof StaffItem` | 会漏掉"只挂组件"的跨模组物品 |
| ❌ `irons_spellbooks:staff` 标签 | Java 侧**零引用**，且硬编码 6 个 id，第三方物品不会进 |

### 3.3 🔴 施法入口**不是** `use`

`CastingItem` / `StaffItem` **都没有重写 `use()`**。施法挂在事件上：

```
PlayerInteractEvent.RightClickItem
  → ServerPlayerEvents.onUseItem(:131)
  → 检查 CASTING_IMPLEMENT(:152)
  → spell.attemptInitiateCast(itemStack, spellLevel, level, player, castSource, true, castingSlot)  (:178)
```

**⇒ 这正是必须补 §2.3 的原因**：不补那条分支，法杖永远不施法（**识别对了也没用**）。

### 3.4 两个组件正交（别混）

| 组件 | 语义 | 持有者 |
|---|---|---|
| `casting_implement` | **触发器**：右键施放"当前选中的法术" | 法杖 |
| `spell_container` | **存储器**：携带哪些法术 | 魔法书 / 魔剑 / 卷轴 / 附魔护甲 |

**法杖没有 `spell_container`**（`StaffItem` 未实现 `IPresetSpellContainer`）。

### 3.5 消耗机制

- `AbstractSpell#canBeCastedBy(:373)` —— 查魔力 / 冷却（**仅在 SPELLBOOK / SWORD 两种 CastSource 下查冷却**）
- `AbstractSpell#castSpell(:305)` —— 实际扣魔力(:316-320)、加冷却(:328-330)

---

## §4 待决问题池（W / V / T 组）

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| W0 | P0 | **"活武器"指哪些？** 只活剑？还是剑/斧/三叉戟/弓全算？ | a) 先做活剑 b) 一整类 | **a** —— 抽象层写通用，验证先用剑 | *待定* |
| W1 | P1 | 用哪条记忆驱动攻击？ | a) 复用 `dig`（左键） b) 新增 attack 记忆 | **a** ⇒ 数据模型零改动 | *待定* |
| W2 | P1 | 射线同时命中实体和方块，先走哪个入口？ | a) 实体优先 b) 方块优先 | **a**（对齐原版准星行为） | *待定* |
| W3 | P1 | 近战走哪个入口？ | a) `fake.attack(entity)` b) 自己算伤害 | **a**（附魔自动生效） | *待定* |
| W4 | P1 | 攻击冷却？ | a) 遵守原版 b) 自己节流 | **a**；⚠️ FakePlayer `tick()` 空 ⇒ 需**手动推进**（同 `L27`） | *待定* |
| W5 | P2 | 要"辅助攻击"（背包活武器给玩家**加伤害**，类比辅助挖掘）吗？ | a) 做 b) 不做 | **b**（平衡敏感） | *待定* |
| W6 | P2 | 要"自动选目标"（不依赖记忆射线，自动打附近敌人）吗？ | a) 做 b) 不做 | 这是**并列的新模式**（守卫型），非本期 | *待定* |
| W7 | P0 | 🔑 `faceTarget` 的朝向反解对不对？ | — | 按公式反解，**待实测**（上下颠倒 = 符号反了） | *待实测* |

### 4.2 V 组 —— 记忆录制（用户方案：让玩家「教」用法）

> **核心思路（用户 2026-09-22）**：不识别武器种类，改为**录制用法** ——
> 哪只手（左/右键）+ 方向 + **按了多久**。
> 📌 用户原话：*"有的模组里的武器，左键和右键是两种攻击方式，那么就根据玩家录制的记忆。
> 记忆也可以录制左键右键的长按时长。"*

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| V1 | P0 | `duration`（长按时长）存哪？ | a) 加进 `RayMemory` b) 新组件 | **a** —— `optionalFieldOf` 默认 0 ⇒ **旧存档自动兼容** | *待定* |
| V2 | P0 | 长按的录制入口？ | a) 改现有 b) **新增** `LivingEntityUseItemEvent` | **b**（不动现有的，防回归去皮/耕地） | *待定* |
| V3 | P1 | 左键长按要不要录？ | a) 要 b) 不要 | **b** —— 原版左键**没有**"使用"流程（只有持续挖）；模组左键长按属自定义 | *待定* |
| V4 | P1 | 盾牌 / 防御算什么？ | — | 就是**长按右键** ⇒ 与弓同一套，**无需特殊处理** | *待定* |
| V5 | P2 | 点太快导致录到 `duration = 0`？ | a) 不管 b) 加最小阈值 | **a** —— 点一下本来就是"点击型"，0 是**正确语义** | *待定* |

### 4.3 T 组 —— 触发条件与节奏

| # | P | 问题 | 选项 | 倾向 | 结论 |
|---|---|---|---|---|---|
| T1 | P0 | 瞄准类的目标怎么找？ | a) 沿记忆射线 b) 附近最近敌人 | **a**（与挖掘同构，"朝哪打"由录制的方向决定） | *待定* |
| T2 | P0 | `duration = -1`（持续）怎么录出来？ | a) 超过上限即持续 b) 玩家手动标记 | **a**（按住 > 约 3 秒记为"一直按着"） | *待定* |
| T3 | P0 | 盾牌的触发条件？ | a) 附近有敌对生物 b) 宿主正在受击 c) 任一 | **c（任一）** | *待定* |
| T4 | P1 | "敌对"怎么判？ | a) 敌对生物标签 b) 可被玩家攻击 | **b**（复用原版判据） | *待定* |
| T5 | P1 | 冷却 / 节奏？ | a) 额外加节流 b) 靠回放本身耗时 | **b**（推进 `duration` 天然限速） | *待定* |
| T6 | P0 | ⚠️ **点击类（C）的重复间隔？** | a) 读原版冷却 b) 固定最小间隔 c) 两者 | **c** —— 优先 `getCooldowns()`，无则默认间隔（可配） | *待定* |
| T7 | P1 | 持续时要不要偶尔松手再按？ | a) 要 b) 不要 | **b** —— 持续 = 真持续（激光类需要**无缝**） | *待定* |

### 4.4 三种攻击行为（用户 2026-09-22 指出）

| 类型 | 攻击发生在 | 例子 | 玩家录制出 | 回放时 |
|---|---|---|---|---|
| **A 一直长按一直攻击** | `onUseTick`（每 tick） | 激光 / 连发武器 | 一直按着 ⇒ **`duration = -1`** | **持续不松手**，`onUseTick` 一直跑 |
| **B 长按后松开才攻击** | `releaseUsing` | 弓 / 蓄力法术 | 按 N tick 松手 ⇒ **`duration = N`** | 推进 N tick → **松手** |
| **C 按一次攻击一次** | `use()`（不进 using） | 法杖 / 单发武器 | 点一下 ⇒ **`duration = 0`** | 只调 `use()` |

⭐ **关键**：我们**不分辨**这三种 —— 整条流程
（`use` → `startUsing` → `onUseTick × N` → `releaseUsing`）**原样重放**，
**哪一步产生攻击由物品自己决定**。区分完全来自"玩家怎么录"。

### 4.5 D 组 —— 实现决策（2026-09-23 拍板）

| # | 问题 | 结论 |
|---|---|---|
| D1 | 攻击记忆的数据结构？ | ✅ **新增 `AttackMemory`**（不动现有 `RayMemory` ⇒ 零回归风险） |
| D2 | 沿射线怎么找实体？ | ✅ **`ProjectileUtil.getEntityHitResult`**（官方；带 `Predicate<Entity>` 可排除宿主） |
| D3 | 攻击记忆与挖掘记忆的关系？ | ✅ **共存**（活斧子两者都有） |
| D4 | R3 记仇先做吗？ | ✅ **先不做**（球形搜索 + 追踪，复杂得多） |
| D5 | 活武器判据？ | ✅ **`isLivingItem` + 5 个官方武器标签**（见 §1.6） |
| — | R1 记的"距离"怎么用？ | ✅ **沿射线找**（不是"只在那个距离附近"） |
| — | 攻击冷却怎么推进？ | ✅ **读物品属性**（攻击速度） |
| — | 官方找方块的 API 为何不用？ | `level.clip` **不支持跳过方块**（宿主黑名单）⇒ 自己遍历；现已改用其底层 `traverseBlocks`（见 §2.8） |

---

## §5 探讨日志

### 2026-09-22 —— 攻击方式抽象 + 铁魔法调研

- 用户定调：**要的是"攻击方式的逻辑"，不是"活剑"这个功能** ⇒ 目标改为一套抽象层。
- 确立核心原则：**活武器不实现攻击逻辑，只"代玩家出手"**。
- 研读铁魔法源码得三条结论：
  1. 识别法杖 = `has(casting_implement)` 数据组件，**不要** `instanceof` / 别用 `staff` 标签
  2. **施法入口不是 `use`**，是 `RightClickItem` ⇒ 我们此前只做了"右键方块"分支，**整个漏了"右键空气"**
  3. `casting_implement`（触发器）与 `spell_container`（存储器）**正交**
- 核 `CommonHooks` 源码确认：`onRightClickBlock` 与 `onItemRightClick` **完全独立** ⇒ 原版按目标类型二选一。
- 改动 `LivingToolReplay#replayUse`：拆成「方块 / 空气」两条分支。
- 新增 `faceTarget(fake, origin, end)` —— 让 FakePlayer 看向记忆射线，这是联动法杖/枪械的钥匙。
- 复查左键：三个 action 齐全 + `destroyBlock` 内部完整 ⇒ **没有类似遗漏**。

### 2026-09-23 —— 活武器判据 + 遍历优化 + D 组拍板

- 用户定调：要活化的是**整个武器战斗系统**（不只剑，还有弓 / 弩 / 三叉戟 / 重锤）。
- 发现原版用「**可附魔类别**」标签分类武器 ⇒ 定为活武器判据（**活石头不会误判**）。
- 用户问"官方 `clip` 的逻辑是什么" ⇒ 核源码发现是**体素遍历**（跳格子边界），
  我们原来的**固定 0.1 步长慢一个数量级** ⇒ 改用 `BlockGetter.traverseBlocks`，黑名单能力保留。
- 拍板 D1~D5，并确定 R1 的用法（沿射线找）与冷却推进方式（读物品属性）。
- 用户认可"用可附魔类别区分武器类型"这条思路。

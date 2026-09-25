# 开发历史更新记录

> **本文件存放全部开发历史正文**（按日期倒序）。`AGENTS.md`「开发进展」只保留
> 最近 10 条「一行结论 + 指针」，**完整正文写在这里**。
>
> **写入规则（2026-09-22 起，D-doc-04）**：在**顶部**对应日期节下追加正文；
> 同一天多次开发**追加到同一节**（⇒ 不再有「同日多批次」问题）。
> AGENTS 侧只做两件事：顶部插一行摘要、超过 10 条时删最底部一行
> —— **没有「归档」环节，无需守恒校验**（正文从来没离开过本文件）。详见 `docs/README.md` §4。
>
> ⚠️ **2026-09-16 补录**：`2026-09-01 ~ 09-04` 曾**断档** —— 该段工作只写在
> `.workbuddy-ai/memory/` 日工作日志里（而日志按策略 30 天后会被删除），`AGENTS.md`
> 与本文件都无记录。本次已从日工作日志补录，特此留痕。
> **教训仍然有效**：历史必须落进仓库，只写工具侧日志 = 会随日志删除而永久丢失。

---

## 2026-09-24

- 🔴 活武器：修「攻击后**玩家被踢出**」（组件可空 `BlockPos` 发包 NPE；表现为"存档崩了、游戏没崩"）—— `living-weapon-tech.md` §5
- ✅ 活武器：新增**记忆清除**（挥空 / 挥向方块 ⇒ 清攻击记忆，套 L44 保护期）—— §9
- ✅ 活武器：修**掉落物形态不攻击**（该宿主入口漏了活武器判据）—— §6
- ✅ 活武器：双记忆共存改为**有怪打怪、没怪挖矿**（原先挖掘记忆完全失效）—— §6
- ✅ 活武器：新增**辅助攻击**（玩家打中怪 ⇒ 背包里无记忆的活武器一起出手）。
  🔴 关键：原版无敌间隔会让**玩家那一刀之后的所有攻击全部失效**（伤害/附魔/击退都不触发），
  故用官方接口 `LivingIncomingDamageEvent#getContainer().setPostAttackInvulnerabilityTicks(0)`
  仅在辅助攻击期间压制无敌（铁魔法同款做法）；另有「死亡即停」「取消击退」两处保护。
- ✅ 活武器：**动画落地**（原先暂缓）—— §7.1
  有记忆：攻击**瞬现到目标位 + 缩放脉冲**（与活工具交互动画同款；`replayAttack` 出手时写
  目标所在格 ⇒ `renderOne` 既有分支自动生效，零新增同步）；
  无记忆：新增**攻击环**（对称挖掘环：攻击时飞到目标生物处，风车自转改**缩放脉冲**，
  **剑尖朝圆心** —— 与工具环的"柄朝圆心"相反）；环成员按工具/武器拆分
  （活斧子归工具环，`if / else if` 互斥防画两遍）。背后环不变。
- 🎛️ 活武器动画**实测微调**（用户看效果后定）—— §7.1：脉冲力度 0.15→**0.30**；
  攻击环存活窗口改为**与脉冲等长**（"扑咬式"：出手⇒飞过去脉冲一下⇒收回，替代原"留原地 20 tick"）；
  攻击环起始半径独立调大（**0.42**，原沿用工具环 0.28 ⇒ 剑长穿模；工具环不变）。
- 🎛️ **背后环改为工具与武器混编一圈**（原先各画一圈会完全重叠）——
  `renderAssistRing`/`renderAttackRing` 返回待机物品，`render` 统一画一次 `renderBackRing`；
  背后环半径 0.28→**0.35**（上限 1.10→1.20，混编含剑防穿模）。
- ✅ 新增**属性镜像**（`LivingToolFakePlayer#syncOwnerAttributes`，2026-09-24 用户需求：
  饰品模组强化玩家本体属性 ⇒ 活工具/活武器借 FakePlayer 出手吃不到增益）——
  把主人白名单属性（攻击伤害/攻速/击退/挖掘效率/挖掘速度）的修饰符复制给 FakePlayer；
  ⭐ 排除主人主手物品贡献（防附魔双倍）；重算式（穿脱饰品自动跟上）；
  挖掘/交互/攻击回放与辅助挖掘四处接入，活工具活武器同时受益。
  边界：只覆盖属性型饰品，事件型（按实例判定）吃不到（§8.1）。
- 🎨 环上模型**立正重做**：按 `isCustomRenderer()` 分两条路（§7.2）——
  灾变等 BEWLR 物品（不守"手持斜45°"约定）走 fixed display 全权渲染 + thirdperson/fixed
  缩放比（修"偏45°+大小不对"，实测姿态/大小全对）；原版 + 守约定模组**回退旧实测方案**
  （-45°绕Z）——两次重构尝试（手持transform+运行时立正）都实测平躺、根因未明，已记录勿再走；
  修复重构引入的 NPE（fixedPose 跨分支引用 ⇒ 画背后环首帧即崩）。

> 当天另有若干「只改实现、口径不变」的改动（三个静默失效、射线朝向改取视线、附魔实测、
> 位置类附魔缺口），按 `docs/README.md` §4.0 判据**只记在 `living-weapon-tech.md`**，不进 changelog。

---

## 2026-09-25

- ✅ 联机：**主动模式（有记忆）联机可见** —— 收集口径扩为 `isAssistItem ∪ 有记忆`
  （记忆/进度/动作全在 DataComponent，随 `ItemStack.STREAM_CODEC` 包内副本天然携带，零新增同步）；
  `renderOtherPlayerItems` 对有记忆物品复用 `renderOne`（悬空工具体 + 挖掘转圈 + 攻击脉冲）；
  `RayRenderer` 加第四条路 `renderRemotePlayerHosts`（F3+B，远程玩家记忆射线）。`living-tool-tech.md` §11.9
- ✅ 联机：**攻击环可见**（`LivingToolAction` 组件随包走，渲染端按组件分组为攻击环/背后环）
- 🎛️ 环半径用户调参：背后/挖掘环 0.7 起、每把 +0.007、上限 1.40
- ✅ tooltip：模式措辞简化为**主动/被动**（原"辅助玩家挖掘/自主挖掘"带挖掘字眼，活武器不适用）；
  补**攻击记忆行**（距离 + 蹲下录的生物限定，`EntityType#getDescription`）
- 🎛️ **创造模式放开辅助**（原 2026-09-20 定案"创造/旁观跳过"）—— 辅助攻击与辅助挖掘都对
  创造玩家生效（攻击侧创造有实际意义且创造物品不掉耐久；挖掘侧放开无副作用），旁观依旧排除
- 📝 已知问题记录（暂不修）：工具右键**不可交互方块也会录上 use 记忆** ——
  `BlockToolModificationEvent` 在原版判定前无条件 post（`AxeItem#evaluateNewBlockState`
  对任意方块连发三次 ability 尝试）+ L44 保护期挡住 `RightClickItem` 的自愈清除；
  将来修法：按 ability 重算原版判定。`living-tool-tech.md` §4.3

---

## 2026-09-23

- ✅ 新增：**活武器「近战核心链路」**（记忆 → 录制 → 回放 → 调度 → 渲染）。
  判据 `isLivingWeapon` 用原版**「可附魔类别」标签**（`ItemTags.WEAPON_ENCHANTABLE`）——
  官方分类口径，模组武器通常也正确归类 ⇒ 自动兼容；**活石头不在任何武器标签里 ⇒ 不会误判** ✅
  ⚠️ 只认这一个标签：弓 / 弩 / 三叉戟是**蓄力型**，需"开始→持续→释放"状态机，留待后续（FakePlayer 不 tick ⇒ 会只拉弓、射不出去）。
- ✅ 数据模型：**`AttackMemory` 成为第三类记忆**（`LivingToolMemory` 由两字段变三字段）。
  ⭐ **另建 record 而非复用 `RayMemory`**：后者带 `Block` 字段、语义是方块，混用会污染语义且要动
  已稳定的编解码 ⇒ 独立出来**零回归风险**。`Codec` / `StreamCodec` 同步就位。
  ⇒ `isEmpty()` 判三个字段 ⇒ 带攻击记忆的武器**自动不上环**。
- 🔴 踩坑：**`LivingDamageEvent` 在 NeoForge 1.21 已拆成 `Pre` / `Post`**，`getSource()` 只在子类上。
  用 **`Post`**（伤害已结算）⇒ 不会把「被格挡 / 被减免到 0」的攻击录进来。
- 🔴 踩坑：**攻击冷却不推进 = 只有 20% 伤害**。`Player#attack` 里 `f *= 0.2F + f²*0.8F`（f = 冷却比例），
  而 f 读的是 **private** 的 `attackStrengthTicker`，靠 `Player#tick()` 自增 ——
  **`FakePlayer#tick()` 是空实现**（`L27` 一脉）⇒ 恒为 0 ⇒ **永远是两折伤害，且永不触发横扫/暴击**。
  解法：**override `getAttackStrengthScale()`**（`LivingToolFakePlayer`），由回放侧按
  「世界轴 tick 差 ÷ 物品冷却时长」推进（S1-a，与原版同源）。
  不用反射（生产环境因混淆失效）、不用 AT（要新增配置）。
- ✅ 回放 `replayAttack`：官方 `ProjectileUtil.getEntityHitResult` 找实体 → `faceTarget` → `fake.attack()`。
  **不实现任何攻击逻辑**，伤害/附魔/耐久全走原版管线（锋利 / 击退 / 火焰附加 / 横扫 / 暴击自动生效）。
  ⭐ **隔墙检测不能省**：实体检测不看方块 ⇒ 不加就会"隔着墙打死后面的怪"。
- ✅ 目标过滤收窄（`isAttackableByLivingWeapon`）：
  🔴 **`Entity#isAttackable()` 默认返回 `true`**（只有 `ItemEntity` 等极少数 override）⇒ **光靠它几乎挡不住东西**。
  补上 `LivingEntity`（挡船/矿车/掉落物）、`!Player`（挡所有玩家，含主人）、`!ArmorStand`、
  `!OwnableEntity(主人)`（挡自己的宠物）⇒ 否则活剑会去砍**盔甲架和玩家的船**。
  ⚠️ 由此**活武器不支持 PVP**（所有玩家一律不打），要放开须先定义"敌对关系"判据。
- ✅ 调度：`LivingToolFunction.canApply` 改为「工具 ∪ 武器」共用；tick 按 **S2「射线决定」** 分派
  —— **有哪条记忆射线就走哪条路**（攻击优先 ⇒ 对齐 W2"实体优先于方块"）。
- ✅ 修复（**会崩**）：`LivingToolModelRenderer#renderOne` 里 `dig != null ? dig : use` 对
  **只带攻击记忆**的活剑会拿到 null 后调 `endpointFrom` ⇒ **NPE**。改为把 `AttackMemory`
  桥接成 `RayMemory(offset, null)` 复用渲染路径；`LivingToolRayRenderer` 新增**品红**攻击射线。
- ✅ 重构：环成员口径**曾在 3 处各写一遍**（渲染 / 同步 / 辅助）⇒ 加武器时必须同步改 3 处，
  已收敛成 `LivingToolRecorder#isAssistItem`（环成员：工具∪武器）与 `#isAssistTool`（辅助挖掘：只工具）。
- 📄 纠正一条旧结论：**`AttackEntityEvent` 无需手动补**。`idea.md` §2.6 曾标为「❌ 待补」，
  核源码发现 `Player#attack` 第一行就调 `CommonHooks.onPlayerAttackTarget`，它内部已 post 该事件
  **并**调 `Item#onLeftClickEntity` ⇒ 再手动 post 会**重复触发**。
  📌 教训：**判断"事件会不会触发"必须顺着调用链查，不能只看 post 的位置**。
- 📄 文档：新建 `docs/tech/living-weapon-tech.md`（活武器独立成子系统，共享部分用指针不复制）；
  `idea.md` 加「设计探讨层」横幅 + 纠正上述结论；`AGENTS.md` 子系统索引与进展各加一行。

---

## 2026-09-22

- ✅ 修复：**大当量/超级爆炸后光照不刷新（坑里一片漆黑）**。根因：这两条路径为性能**直接调
  `LevelChunkSection.setBlockState()`**，绕过 `LevelChunk.setBlockState()` —— 原版把光照更新写在那里
  ⇒ 光照引擎不知道方块没了（`NORMAL` 走 `level.setBlock()`，不受影响）。
  ⇒ 新增 `ExplosionComponent.refreshLightAfterBulkEdit()`：重建天光柱高图 → 逐 section 上报空态
  → `propagateLightSources()` → 被炸掉的发光方块逐个 `checkBlock()`。
  **两条红线**：柱高图重建**必须早于**重算（反了 = 没修）；增光与减光是两条路径（后者只管**现存**光源）。
  新增 `ExplosionComponentLightTest`（5 项）。详见 `living-tnt-tech.md` §4.3「⚠️ 光照刷新」。
- ✅ 修复：**大当量爆炸坑不圆、边缘残留整块区块地形**。根因 `ExplosionParams.affects()` 用「区块**中心**
  在半径内」当判据 —— 区块 16×16，"中心在外、边缘在球内"的区块**建档时就被标记完成、永不处理**
  ⇒ 每场爆炸整块跳过 **32~86 个区块**。改为「**区块 AABB ∩ 球体**」（该判据在**三处**生效 = 单点根因）。
  ⚠️ 它曾被 `ExplosionParamsTest` 按"与旧口径一致"**钉住** —— **把既有行为当规格前，先确认它是对的**。
  守卫：边界回归 + **球体全覆盖** `affects_coversEveryBlockInsideSphere`
  （断言"半径内每个方块所在区块必命中"，**已验证在旧判据下会 FAIL**）。
  详见 `living-tnt-tech.md` §4.3「⚠️『哪些区块受影响』的判据」（含漏块量化表）。
- ✅ 定性：玩家报的「方形区域还画着旧方块、**过一会自己消失**」= **客户端重建排队，不是数据没同步**。
  整区块包会让客户端对**每个 section** 调 `setSectionDirtyWithNeighbors`（每次标 3×3×3=27 个），
  一次爆炸改动**上万个 section** ⇒ 排队期间仍画旧几何，排完即恢复（数据其实**立刻**就对了）。
  **判据：会自己消失 = 正常；一直都在 = bug（= 上面 `affects` 那条）。**
  同时把方块同步改到光照刷新**之前**发（方块是用户直接看得见的）。
  ⇒ `living-tnt-tech.md` §4.3 + `living-tnt-testing.md` §5「两种要分清」表（免群友误报）。
- ✅ 文档：**修掉归档规则里的「空档 ≤1 天」**（`docs/README.md` §4/§7 + `changelog.md`/本文件节首）。
  该规则**假设"每天都有开发"** ⇒ 间歇性开发必然误报，且与「保留 3 批次」**互相矛盾**（永远无法归档）。
  改为只校验**边界单调**（`changelog 最新` 必须早于 `AGENTS 最早`）；防丢历史交给**连续迁出 + 守恒校验**；
  相隔 >30 天仅提示。`doc_check.py`：`CONTINUITY_MAX_GAP_DAYS=1` → `ARCHIVE_WARN_GAP_DAYS=30`。
  并借此归档 `2026-09-16` 批次。
- ✅ 资源：**纹理去原版化（56 张）** —— 与原版字节/像素相同的纹理改为**直接引用 `minecraft:` 路径**
  并从包内删除，不再随模组再分发原版资源。**附带收益：这些图标会跟随玩家自己的材质包。**
  涂蜡铜块家族（24 张）规律实测 24/24 零例外 = **原版纹理 + 外圈 60 px 改 `(232,160,62)`**
  ⇒ 改为**模型双层**（`layer0` 引用原版 + `layer1` = 一张自绘 `wax_ring.png` 覆盖全部 24 张）。
  ⚠️ **涂蜡不能用 `IItemDecorator`** —— 装饰器只在 GUI 绘制，而涂蜡是物品身份、必须到处可见。
  `living_item` 纹理数 72 → **17**。详见 `icon-system.md`「纹理约定」+ §5.2「③ 多层叠加」。
- ✅ 文档：**图标「渲染上下文覆盖范围」写成显式约束**（原只在 §2 架构图里暗示）。
  两条机制均为**有意设计**：① `GenericContextAwareModel` 非 GUI 返回 `vanillaModel`
  ⇒ 掉落物/手持/展示框**显示原版外观**（影响**所有**走变体路径的活物品）；
  ② `IItemDecorator` **只在 GUI 内被调用** ⇒ 依赖叠加层的物品（漏斗箭头 · 雕文箭头 · 地图缩略图 ·
  耕地种子 · 红石连线）**在 GUI 之外叠加层消失**。
  ⇒ 组合后果：可能出现「只有基础层」（如活漏斗只剩中心圆）。
  「全形态一致」的 A/B/C 三方案已记录但**暂不实施**（B/C 需游戏内验证图层混合能否复现 `blit` 叠加）。
  详见 `icon-system.md`「渲染上下文覆盖范围（重要约束）」。
- ✅ **箱子/末影箱图标定稿**（2026-09-22 晚，三轮：实验 `20d6ab9` → 误判回退 → 用户拍板）：
  用 `item/chest_3d` / `item/ender_3d`（`parent: builtin/entity`，`gui rotation [0,0,0] scale [1,1,1]`）
  ⇒ GUI 显示**正面视角 14px** 3D 箱子（`ChestRenderer` 物品分支强制 FACING=SOUTH、锁扣在 +Z、
  箱体宽 14 单位）；spec **不挂装饰器**（3D 自身足以辨识「活」）；旧平面图标资源**已删**。
  ⚠️ 1.21.1 判据是 **`isCustomRenderer()`** 而非 `usesBlockEntity()`（后者此版本不存在）。
  详见 `icon-system.md`「已完成的实验」。
- ✅ **GUI 图标光照统一**（2026-09-22 晚，起因 3D 箱子在 GUI 里发暗）：
  ⚠️ **改 light 值没用** —— `GuiGraphics.renderItem` 传的本就是 `FULL_BRIGHT(15728880)`；
  真凶是 `!usesBlockLight()` 分支（true ⇒ `setupFor3DItems()` 法线漫反射，3D 图标亮度只剩 ~0.7）。
  ⇒ `GenericContextAwareModel.usesBlockLight()` **恒 false**（该属性 1.21.1 只被 GuiGraphics 读）。
  另抽出 `LivingIconRenderHelper.renderBlockIcon()` 作为「容器内手绘方块模型」图标统一入口
  （FULL_BRIGHT + 强制 cutout + 异常隔离，替换 mixin 私有实现）⇒ **新加图标光照零配置**。
  ⚠️ **约定：每个 `BakedModel` 包装类都必须 `usesBlockLight() → false`**（原先三个类各手写一遍，
  活水车漏了 ⇒ 3D 水车在 GUI 里发暗，已修；唯一例外是 `GenericLivingModelWrapper` ——
  它 resolve 后不参与渲染）。
  详见 `icon-system.md`「GUI 图标光照约定」。
- ⚠️ 回归（2026-09-22）：**活红石粉装饰器叠了缺失纹理** —— 原因：去原版化时删了
  `textures/item/redstone_dust_dot.png`，但 `LivingRedstoneDecorator.DOT_TEXTURE`
  仍 `fromNamespaceAndPath(MOD_ID, "textures/item/redstone_dust_dot.png")`，**渲染时
  变成黑紫占位色**。**修复**：DOT_TEXTURE 改为引用 `minecraft:block/redstone_dust_dot`
  （白点），与 `models/item/redstone_dust.json` 的 `layer0` 用同一张纹理 ⇒ 模型层画白点
  + 装饰器 `setColor` 染色 = 可见红色点。`redstone_dust_line0.png` 是去底色优化版（md5
  与原版不同），保留。
  ⚠️ **教训（已写进 `icon-system.md`）**：去原版化扫描必须包含 `fromNamespaceAndPath(MOD_ID, ...)`
  —— 该写法不带 `living_item:` 前缀，按 `living_item:textures/...` 扫是扫不到的。
- ✅ 修复：**大箱子（多方块容器）两套槽位体系错位 ⇒ 活漏斗静默不传输**。症状：大箱子里上下
  摆两个「上传下」漏斗，上方**完全不传**；拿走下方、或往输出槽放一个物品都能恢复；下方换
  **任何朝向**都照样"锁住"（**决定性线索** —— 与方向/连接关系无关，只与那个格子有没有东西
  有关）；同布局在单方块箱子正常。根因：`ContainerContext.getContainer` 只返回 pos 那一个 BE
  的容器（大箱子 = 单半箱 27 槽），而 handler 是合并的 54 槽 ⇒ **错位 27**（Container 槽 22 =
  GUI 槽 49）；`simulateInsertItem` 用它读"目标槽现有物品" ⇒ 读到另一半箱的漏斗 ⇒ 判不可插入
  ⇒ 不传且不设冷却。`PlainSlotAccessor` 只在**目标槽为空**时调模拟插入（非空走合并分支），
  这解释了"放一个物品就好"以及它为何藏这么久。⇒ 新增**槽位体系一致性探针**
  `isSameSlotSpaceAsHandler`（①槽位数一致 ②单槽交叉校验），不过则回退 handler；
  **不**在 `getContainer` 里特判合并容器 —— 合并顺序由各 mod 的 IItemHandler 决定，猜错就是
  一道**新的**错位；探针不假设容器结构，三方块/四块/任意多方块容器通用。回退不丢语义
  （`handler.insertItem` 内部转调 `canPlaceItem/isItemValid`，且模拟与真实由此**同源**）。
  ⚠️ **教训**：多方块容器下"按逻辑槽位"的读写，**先验证两套体系是否同编号再使用**；
  模拟与真实写入必须同源。详见 [living-hopper-tech.md](docs/tech/living-hopper-tech.md) §10.25。
- ✅ 修复：**大箱子跨容器传输的面选取 —— GUI 4 方向 → 世界 6 面**。本质：两个半箱共 8 个侧面，
  贴合的 2 个是内部面，剩 **6 个外部面**；左/右（连接轴）各只对应 **1 个**外部面（另一端是
  贴合面，被现有防护挡掉），上/下（facing 轴）各对应 **2 个**外部面（两个半箱各一个，是
  **两个不同方块**）。旧实现按**方向**选单个基准块（下/右→第一块，上/左→第二块），与漏斗
  所在槽位无关 ⇒ 漏斗在后半箱向下推送时，推出去的是**前半箱正面**的方块（**静默推错面、
  不报错**；而左右选错会被内部防护挡住，反而"看得见"）。⇒ 改为 `getBasePosCandidates()`
  返回**候选基准块列表**：首选 = 发起槽位所属那块（`hostSlot / (size/块数)`），其余作备选，
  **逐个尝试**（用户拍板"两个都试"，顺带让大箱子上下能覆盖两个面）。**附带收益**：有备选面
  兜底 ⇒ 不必再为「槽位段 ↔ 位置顺序」做内容探测，假设反了最多影响优先级（对比容器内路径
  §10.25 —— 那条路没有备选，必须靠体系探针）。另加"源/目标解析到同一邻居则跳过"防自传。
  详见 [living-hopper-tech.md](docs/tech/living-hopper-tech.md) §6.4。

## 2026-09-20

- ✅ 修复：**容器规则数据勘误 + 玩家差异持久化 + 开发期导出通道**。起因「打包后没有配置文件」
  —— 实为 jar 内 `assets/living_item/container_rules.json` 里 7 条 IronChests 规则**全错**：
  命名空间写成 `ironchests`（实际 `ironchest`，取自 `IronChestsItems.MODID`）、槽位数 45/36/54/63/72
  **全是编造**（真值 54/45/81/108/108）、`silver_chest` **该模组不存在** ⇒ 这 7 条**从未生效**。
  真值取自 `libs/ironchest-1.21-neoforge-16.0.7.jar` 字节码 `IronChestsTypes.<clinit>`。
  ⇒ 三条机制改动：① `save()` 只写**玩家差异**（新增/覆盖/删除），不再全量回写内置副本；
  ② 新增 `removed` 字段——**删内置规则必须落盘**，否则 `load()` 会把它「复活」
  （`load()` 同时改为幂等：先 `resetState()` 再重建）；③ `/livingitem container export`
  把玩家规则导出为内置资源同格式 JSON，供合并进 `src/main/resources/` 随包发布。
  **jar 内文件运行时不可写**是 classpath 硬限制 ⇒ 导出是唯一路径，正式环境不参与写入。
  `register` 同时放开为**允许覆盖**（此前拒绝，导致玩家无法修正错误内置数据），
  `inspect`/`list` 显示规则来源（内置/玩家覆盖/玩家注册）。新增 `ContainerRuleConfigTest`（9 项），
  全量 **341 用例全绿**。详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md)
  §5.2/§5.2.1/§5.2.2 + [container-compatibility.md](docs/guides/container-compatibility.md)。

- ✅ 文档：**新增《活TNT测试说明》给测试者用** —— [living-tnt-testing.md](docs/guides/living-tnt-testing.md)。
  18 个编号用例（`T-01`~`T-18`）+ 「提测最小集」。**这是面向测试者的操作说明，不是技术文档**：
  不含实现细节，只写"怎么做 / 期望看到什么 / 看着像 bug 其实不是"。
  重点压在三处**只有真机才能验**的行为：① `T-08` 超级爆炸**服务端不冻结**；
  ② `T-09`/`T-10` **未加载区块走近后补炸**（含存档往返）；③ `T-17`
  `/living_monitor cache` 观测 `loaded区块` 站桩不动**不增长**。
  另附「已知的'看着像 bug 其实不是'」表（分帧扩散消失 / 大当量无掉落物 / 声光先于破坏），
  减少无效反馈。口径以 [living-tnt-tech.md](docs/tech/living-tnt-tech.md) §4.3 为准。
- ✅ 文档：**测试说明按技术文档返工定稿** —— 群友区 `T-01`~`T-13`（原 18 条砍到 13 条，
  删掉"不点燃就不炸""不能重复点燃"等**过于简单/可从代码推出**的条目）；作者区 `A-01`~`A-12`。
  **核心修正（三处此前写错）**：
  * **抗爆逐模式不同**：`NORMAL` 硬编码 `≥100` 跳过（**与半径无关**，黑曜石永不没）·
    `HIGH_YIELD` 判据 `威力 = 半径×(1−距离/半径)×2`，**半径越大越能炸穿**（3456 个时中心威力约 470，
    仍 < 黑曜石 1200）· `SUPER` **不看抗爆值、整区块抹除**（基岩也留不下）。
  * **流体参与抗爆判定**：两种非 SUPER 模式取 `max(方块抗爆, 流体抗爆)`；原版水 = **100**
    ⇒ 普通模式炸不掉、大当量半径够大能清掉。
  * **删掉编造内容**：「活箱子能引爆内部活TNT」「>6912 需活箱子超堆叠」——
    代码依据 `LivingChestFunction.tick()` 是**空实现** ⇒ 活箱子里的活TNT**不被 tick、引信不倒数**。
    超级爆炸的**唯一**触发路径是**铁箱子（模组自带 `container_rules.json` 兼容）塞满**：金箱 81 槽 = 5184、
    钻石/水晶/黑曜石 108 槽 = 6912（覆盖率见 §3 容量对照）。

- ✅ 特性：**容器兼容性社区贡献流程打通（文件级覆盖）**。`export` 改为导出**全量生效快照**
  （内置 + 玩家新增 − 玩家删除，按 ID 排序）而非差异 ⇒ 玩家导出的文件**可直接整文件覆盖**
  `assets/living_item/container_rules.json`，作者无需逐条摘录或写合并脚本，
  重新打包即把兼容性发给所有玩家 —— 「玩家共同完善容器兼容性」从设想变为常规机制。
  配套：`countBundled()`/`countUser()` 统计来源；加载时**重复定义冲突检测**（先到先得 + WARN，
  `differs()` **只比 `containerSize`/`columns`**，描述措辞不同不算冲突，否则被假冲突淹没）。
  解析逻辑抽为 `loadRules(Reader, source, label)`（包级可见），使**作者的覆盖加载路径与内置资源加载
  共用同一套解析+冲突检测**，同时让测试能直接喂入模拟快照验证闭环。
  ⚠️ **修掉一个真 bug**：`save()`/`export` 用 `FileWriter`（平台默认编码，中文 Windows = GBK），
  含中文描述的导出文件拿给 UTF-8 环境会 `MalformedInputException` ⇒ **跨平台交换必锁 UTF-8**，
  三处 IO 全改显式 `StandardCharsets.UTF_8`。
  `ContainerRuleConfigTest` 扩到 **11 项**（新增「导出快照可原样当作内置资源重新加载，无丢失」
  与「合并冲突先到先得、描述不同不算冲突」两条闭环守卫），全量 **343 用例全绿**。
  详见 [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) §5.2.2。

## 2026-09-18
- ✅ 重构：**爆炸破坏改为「按区块分帧 + 待炸账本」**（承上：死锁与强制加载防护之后的第三块）。
  原实现三种模式各写一套"一次性遍历整个球体"，而半径 `= 4.0 × √TNT数`（64 TNT 跨 5×5、
  3456 TNT 达 **31×31 = 961 区块**）—— 球体一部分可能**未加载**：当场读会强制加载
  （单次 289 区块足迹 + 主线程阻塞 + 票据钉住 + 传染其他模组），直接跳过又让**语义残缺**
  （同一场爆炸，玩家站的位置不同、结果不同）。⇒ 采用**原版 TNT 引信模型**「世界只在被观测的
  地方演化」：三种模式统一为**逐区块**执行，唯一入口
  `ExplosionComponent.applyToChunk(level, params, chunk)`；爆炸瞬间只登记
  `ExplosionParams`（参数 + 位图索引映射）+ 声光 + 实体伤害，破坏交给
  **`ExplosionLedger`（世界级 `SavedData`，参数 + `long[]` 完成位图）** —— 已加载的按预算
  每 tick ≤32 个区块分帧应用，**未加载的等它自然加载时补上**（`ChunkEvent.Load` 只登记坐标，
  走 tick 阶段应用，符合两条红线）。顺带修掉两个既有 bug：① 旧
  `PENDING_SUPER_EXPLOSIONS` 服务端关闭**不清理** ⇒ 单人换存档（不重启 JVM）持有旧
  `ServerLevel` 内存泄漏；② `tickAll` 每 tick 只处理 1 个区块、且跳过未加载时无条件推进
  （静默残缺）。新增 `ExplosionParamsTest`（4 项）+ `ExplosionLedgerTest`（9 项），
  全量 **332 用例全绿**。详见 [living-tnt-tech.md](docs/tech/living-tnt-tech.md) §4.3 +
  [living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) §3.2.2 +
  decisions.md `D-tnt-01`。
- ✅ 修复：**Create 跨区块传送带导致服务端线程死锁**（1.3.2 + Create 6.0.10，进世界即卡死：
  GUI 能动、不能合成/传送、玩家掉虚空）。根因：`ChunkEvent.Load` 回调里做能力查询 →
  Create 传送带 provider 去查**另一个区块**的 BE → 同步区块加载 → 自等自（同区块不卡，
  因为原版 `currentlyLoading` 旁路只覆盖正在加载的那个区块）。修复：`onChunkLoad` **只登记
  坐标**，扫描推迟到 `ServerTickEvent.Pre`（每 tick 限量 64）。详见 §3.2 + `D-core-04`。
- ✅ 新增：**强制加载防护（处理侧限定 ticking 区）+ 可观测性**。被动路径同样会强制加载：
  活物品读**相邻一格**（红石 `getSignal` / 活漏斗邻居容器 / 大箱子另一半 / 活水车下方一格），
  而框架取 **loaded 区（≤33）** ⇒ 容器在"已加载但不 tick"的**最外一圈**时邻居在**未加载**区
  ⇒ 单次要凑 **289 区块**足迹、且票据续期会把邻居**永久钉住**在视距外。修复：
  `getProcessableChunks(ServerLevel)` 处理前过 `isPositionTicking`（差别**恰好只有危险带那一圈**；
  ticking 区块的 **3×3 邻域必为 FULL** ⇒ 本模组入口**可证明安全**、外扩链条被切断）。
  ⚠️ **扫描不做此过滤**（区块提升到 ticking 没有对应事件）。新增 `describeCacheStats` +
  调试命令 **`/living_monitor cache`**（缓存/可处理/loaded 区块/视距基准）⇒ 钉住与外扩可测量。
  详见 §3.2.1 + `D-core-05`。⏳ 未做：**爆炸路径**（§3.2.2）、**第三方 provider 伸远**（不可控面）。
  顺带修掉 §3.2 两处旧口径与一处错位/重号小节。


> 由实际踩坑沉淀的行为准则 —— **每次会话都适用**，不是某个子系统的细节。

### ⭐ 严谨要保留，但别把简单问题做复杂（2026-09-19 用户反馈）

> 用户原话：*"你的细心很好，就是容易把很简单的问题想得复杂了。"*

**症状**：为了「更精确 / 更一致 / 更健壮」，在**渲染侧**堆出第二套逻辑 ——
而这套逻辑既不影响功能正确性，也从来没人要求过。

**同一个毛病犯了两次**（活工具射线，见 `docs/tech/living-tool-tech.md` §11.3）：

| 轮次 | 我多做的事 | 用户回应 |
|---|---|---|
| 1 | 在客户端**复刻**服务端的三层命中判据（宿主黑名单 / 流体 / 形状求交） | "根本不需要呀" |
| 2 | 拿到结果后又要求**精确交点**，把"格子中心"细化成"表面点" | "客户端渲染根线要算什么交点" |

**根因**：把「渲染」当成「逻辑的第二份实现」，而不是「已有数据的呈现」。

**动手前先自问三句**：

1. 我要用的这几个值，是不是**现成的**？（是 → 直接用，别推导）
2. 我多加的这一层，**错了有没有后果**？（渲染画错**没有**功能后果 → 不值得加）
3. 这个需求是**用户提的**，还是**我自己发明的**？

**分界线**（活工具射线 `L48` 定案，可作同类问题的模板）：

> **判据归服务端（唯一权威），几何归深度测试（免费），客户端只做「两点连一条带子」。**

**另一个要警惕的惯性**：修 bug 时倾向**加固**，而不是**回退到更简单**。
出错后先问「**是不是根本不该做这件事**」，再问「怎么才能做得更像」——
顺序反了就会在错误的前提下越优化越复杂。

---

## 2026-09-16
- ✅ 新增：**活锄头跨模组兼容 + 可耕土扩展**——原先「活锄头」是**写死的 6 种原版锄头**
  （注册侧枚举物品 + 校验侧 `instanceof HoeItem`，两侧口径还不一致），其它模组的锄头
  活化后**耕不了活泥土**（客户端根本不拦截）。改为语义判定：新增
  `domain/farmland/Tillables`（唯一真源）—— 锄头只认
  `canPerformAction(ItemAbilities.HOE_TILL)`（NeoForge 对自定义工具的官方口径：原版
  锄头经 patch 自动满足，模组锄头重写 `canPerformAction` 即自动兼容；**不做**
  instanceof / 标签 / 配置兜底）；可耕映射对齐原版 `IBlockExtension#getToolModifiedState`
  的 HOE_TILL 分支（以 neoforge-21.1.249 源码为准：**泥土/草方块/土径 → 耕地，砂土/
  缠根泥土 → 泥土**，再耕一跳才变耕地；灰化土/菌丝原版不可耕，不纳入；「上方必须是
  空气」与「缠根泥土掉垂根」属世界副作用，物品层 GUI 交互不做）。注册处 6 条精确规则
  → **按可耕目标逐条注册通配条目 + `Tillables::canTillWith` 谓词**（与 plant_crop
  同构）；谓词内**必须**自查 `isLivingItem(trigger)`——通配分支不校验活物品，漏了会吞
  掉原版拿起/分堆。handler 改为查表取产物，且创造模式同样校验光标（只免耐久消耗）。
  新增 `testutil/FakeHoe`（模组锄头替身：⚠️ **测试期物品注册表已冻结，不能 `new Item`**，
  否则静态初始化抛 `Registry is already frozen` 导致整类 17 用例全红且看不出原因——改用
  `Mockito.spy(Items.STICK)` + stub `canPerformAction`）+ `TillablesTest`（8 项）+
  `TillToFarmlandCompatTest`（8 项），全量 **313 用例全绿**。收编
  living-farmland-tech.md §3.1/§10.1/§10.2/§10.3/§11.17/§12.1 +
  gui-interaction-system.md 处理器表 + file-map.md。
- ✅ 修复：**活耕地种子图标在 HUD 快捷栏不渲染**——原实现画在
  `AbstractContainerScreenMixin.render @TAIL`，硬依赖 `leftPos`/`topPos`（只有
  `AbstractContainerScreen` 有），而快捷栏走 `Gui.renderHotbar` → `renderSlot` →
  `GuiGraphics.renderItemDecorations` → `ItemDecoratorHandler`，**从不经过该屏幕**。
  迁到 `IItemDecorator`（`client/render/LivingFarmlandSeedDecorator`，自抬 z=200）：
  装饰器在快捷栏/容器 GUI/创造物品栏都会被调用，**一份代码全覆盖、只画一次**；
  容器 Mixin 里那段重复 blit 已删除（生长槽大图保留——它需要「同容器正上方一格槽位」
  的邻居关系，装饰器拿不到容器槽表）。新增 `LivingFarmlandSeedDecoratorTest`（4 项），
  全量 **297 用例全绿**。收编 icon-system.md（三层架构 + §5 表格补活耕地行 + 新增
  「种子图标改走装饰器路径」小节，含坐标/z/渲染状态三处契约）+ living-farmland-tech.md
  v1.11 §8.2/§10.1/§10.2/§10.3/§12.3。
- ✅ 新增：**放置活耕地回世界时自动种下自带作物**——把一块「已种植」的活耕地物品
  放置到世界里，放置出的耕地上直接长出那株作物。**做法：模拟玩家右键**
  （构造 `UseOnContext` 调 `ItemStack.useOn`），**不是**自己 `setBlock`——后者会绕过
  模组在 `canSurvive` / 覆写 `useOn` 里的校验（如「水稻只能在水下种」），种出非法状态。
  改用 `useOn` 后连 `CropClassifier`、`canBeReplaced` 检查、`is(Blocks.FARMLAND)`
  检查都不需要了（种子自己的 `canSurvive` 会校验下方是耕地），代码反而更短。
  入口：`mixin/BlockItemMixin`（`@Mixin(BlockItem.class)`，注入 `place` 的 `consume`
  **之前**——注在 `@At("RETURN")` 会因空栈 `getComponents()` 返回 EMPTY 而
  **只在单块放置时静默失效**）+ `domain/farmland/LivingFarmlandPlacement`（约 30 行）。
  **这是「软逻辑」**（用户定调）：能种上就好，种不上（含抛异常）一律静默、整段
  try/catch，绝不影响原版放置流程。口径：**一律种成幼苗**（不保留成熟度，用户定调）；
  多格作物上部件不放置、交给原版长；`pendingDrops` 掉落不丢。新增
  `LivingFarmlandPlacementTest`（5 项，含「异常不冒泡」红线与**端到端 Mixin 接线**用例），
  全量 **293 用例全绿**。
  收编 living-farmland-tech.md v1.10 §3.5 + §10.1/§10.2/§10.3 + §11.16（三坑：
  `RETURN` 注入 / `isClientSide` 字段不可 mock / 别 `setBlock`）+ §12.1 实测清单。

## 2026-09-15
- ✅ 新增：**活漏斗自动施肥（骨粉 → 活耕地）**——活漏斗按 WASD 方向传输时，
  货物是骨粉且目标槽位是活耕地 → 绕开通用插入（活耕地是活物品非存储容器，
  SlotAccessorFactory 必然 null——通用路径每 tick 空转），改走施肥消耗 1 个
  骨粉触发一次生长 tick（forceGrowthTick：未成熟 +1 / 成熟待输出空 → 冻结
  产出）。**触发物口径有意区分**（用户定稿）：手动 = 活化能力（GUI 右键要活
  骨粉），自动 = 物流集成（**普通骨粉**即可，骨粉生成器/原版漏斗物流可直接
  对接；活骨粉也放行）。equals 零空转：对着已冻结成熟耕地不烧骨粉。节奏 =
  漏斗冷却（8t 随堆叠加速，一次施肥 = 一次传输）。实现三处：
  `LivingFarmlandFunction.tryFertilize`（入口）+ `TransferPipeline.executeInContainer`
  施肥分支（流程图 [3.5]，置于 isTransferableSource 之前放行骨粉）+
  `CrossContainerTransfer.pushToNeighbor` 跨容器版（tryFertilizeToNeighbor
  邻居槽位迭代；getStackInSlot 实时引用改组件即刻生效，无需回写 handler）。
  回归测试 FertilizeTransferTest（6 项），全量 268 用例全绿；收编
  living-hopper-tech.md §2.2 流程图 [3.5] + §6.2.1 跨容器施肥小节 +
  living-farmland-tech.md v1.7 §7.1（口径对照表）+ §12.2 验证项 + §10.3
  ⚠️ 本条的「活骨粉也放行」与实现三处之说已于同日修正/收编，见下方两条（口径修正 + 注册式分发）
- ✅ 修复 + 重构：**跨容器施肥「推送生效、拉取失效」**——施肥分支原先只内嵌在
  推送方向与容器内管道，拉取方向 `pullFromNeighbor` 走通用路径，而
  `SlotAccessorFactory.create` 对非箱类活物品直接 return null（活耕地正是
  「活物品 + 非存储容器」）→ 目标槽必然失败，骨粉送不进去也永远不施肥。
  **修复 + 结构性收编**：方程抽成注册式槽位交互
  `SlotInteraction` / `SlotInteractions`（内置条目 `FarmlandBonemealInteraction`），
  三处传输分支只调分发器（`tryInteract` 已知货物 / `tryInteractFromNeighbor`
  拉取方向）——**今后新增同类交互 = 1 个实现类 + 1 行注册，零传输代码改动**。
  顺带完成跨容器能力全量审计（`living-hopper-tech.md` §6.2.2 覆盖矩阵 +
  结构规则「特殊槽位识别只在 containerCtx 一侧生效」）。回归测试
  `CrossContainerTransferFertilizeTest`（8 项，两个入口各覆盖），全量 276 用例全绿。
  ⚠️ 该文件于同日口径修正轮扩至 **15 项**（补推送方向隔离守卫 + 活骨粉三入口全拒），见下条
- ✅ 自查修复：**重构自引入的「活骨粉施肥失效」**——交互源槽起初用
  `SlotAccessorFactory.create` 拿 Accessor，而它开头就拦非箱类活物品（活骨粉正是），
  导致 `tryInteract(null, ...)` 恒 false（普通骨粉不受影响，故只测普通骨粉看不出来）。
  新增 `SlotAccessorFactory.createForInteraction`（不拦活物品，只读源槽自身物品，
  不展开活箱子虚拟存储），容器内交互改用它；活物品隔离规则本身未动。
  新增 `SlotInteractionFactoryTest`（4 项）钉住该边界，全量 **280 用例全绿**。
  ⚠️ 本条的 `createForInteraction` 与 `SlotInteractionFactoryTest` 已于同日随口径修正移除，见下条
  附带统一：三处交互源槽都带黑白名单过滤（原先容器内路径在过滤检查之前）。
  新增 `SlotInteractions.canInteract` 廉价筛选谓词（分配 Accessor 之前先筛，
  避免每次传输尝试白分配两个小对象；拉取方向每轮最多省 27 次），全量 281 用例全绿。
- ✅ 修正口径（用户定案）：**漏斗只认普通骨粉，活骨粉不施肥**——上一版把「活骨粉也放行」
  统一到四个方向，方向错了：施肥的语义是「活漏斗用**传输能力**把骨粉送进活耕地」，
  属传输语义 ⇒ 必须受漏斗自身的货物规则（**活物品不作货物**）约束，不能因为
  「反正要消耗掉」就开洞。规则收在**唯一定义点** `SlotInteractions.isEligibleCargo`
  （活物品不作货物，活箱子/活末影箱除外），传输层 `isTransferableSource` 直接委托，
  交互层两个入口共用——**调用点顺序变更也绕不过**；`tryPushToNeighbor` 另留循环自守
  （非合法货物绝不进入通用插入/合并）。同时移除已无用途的
  `SlotAccessorFactory.createForInteraction`（那是为「活骨粉放行」加的）。
  口径 = **手动要活化、自动要普通**。新增 `SlotInteractionCargoGateTest`（5 项），
  同期 `CrossContainerTransferFertilizeTest` 由 8 项扩至 15 项、移除
  `SlotInteractionFactoryTest`（4 项），全量 **288 用例全绿**
  （测试树与基线数字已按实测同步：`288 passed / 0 failed / 0 skipped`）。

---

## 2026-09-14

- ✅ 终审（活耕地，用户实测九轮全过后全面代码审查：三路并行审计——服务端逻辑/客户端
  渲染/文档测试一致性）——**唯一确认功能性 bug：部分合并丢物品**：生长槽同种半堆
  空间不足整份产出时旧实现塞部分即推进索引，`outputCount-toAdd` 差额静默消失
  （玩家留半堆产物即触发）。修复：合并仅当 space ≥ outputCount，放不下本 tick
  等待（与「不同种占据」同语义零损失）+ 回归测试 LivingFarmlandFunctionTest
  （5 项：部分合并等待/整份合并/空槽全放/堆叠上限钳制/边界含等号）。
  渲染两缺陷：**同帧生长槽双重认领**（同列双耕地隔空行 → 中间槽双画，per-frame
  认领表先到先得）+ **多格模式无互斥**（上部件与柱状段双注册同槽双画，命中其一
  即跳过其余）。加固四项：renderSingleBlock 异常隔离（第三方作物颜色处理器 NPE
  只跳过该槽不崩屏）+ 留种改总量 -1（多池同种子只扣一份）+ 存量 maxAge 自愈
  （注册表修正后旧组件重冻结+age 钳制，maxAge=7 时代耕地不需铲掉重种）+
  清洁收尾（注释漂移 3→4、agePropertyOf 去重、重复 import、死代码删除）。
  设计取舍明示：「留种后全空原样输出」= 种子-only 作物每周期净产 1 种子
  （防零产出循环的有意行为）；超大堆叠耕地（count>64）因等量种子消耗不可种。
  文档同步：AGENTS 勾选项三处五轮前旧口径修正（种子消耗/骨粉语义/湿润 4 级
  BFS）+ 全绿基线 262 + 测试索引 6 处计数修正 + 补列 4 个缺类 + network/ 补
  LivingItemSyncPacket；技术文档五轮残留清理（§1.1/§1.2/§1.3/§2/§5.2 内部
  自相矛盾六处）。全量 262 用例全绿
- 🐛 修复（活耕地，第九轮实测：KC 水稻三格只渲染一格）——**第三种多格作物形态**。
  KC（KaleidoscopeCookery）水稻既非原版半部件（DOUBLE_BLOCK_HALF 两值）也非 FD 的
  两个独立方块，而是**同方块 + IntegerProperty 分段**：`rice_crop` 带 age(0~7)+
  location(0=下/1=中/2=上)+waterlogged，三格同放同长（updateShape 从下方邻居拷贝
  age）、每段每 age 独立模型。我方 stateForAge 默认态只出第一格（中/上空白），
  两种既有上部件模式全不命中。修复：**COLUMN_PARTS 注册表**（作物注册名 → 各段
  属性覆盖列表，getColumnParts 在 stateForAge 基础上应用覆盖、属性缺失段静默跳过）
  + 渲染循环从生长槽正上方逐格向上画各段（空槽才画、到顶/被占自动截断）；注册名
  延迟解析软依赖安全。产出侧零改动（KC getDrops 只在 location=down 段滚表，我方
  默认态恰好命中——「产出正常」的实测印证）。教训：多格作物实现方式至少三种
  （半部件翻转/独立上部件方块/同方块属性分段），互相不可推导，渲染按「属性结构
  +注册表」双轨解析。CropClassifierTest 补柱状段 3 项（10 项），全量 257 用例
  全绿；收编 living-farmland-tech.md v1.5 §8.2.1 三模式收齐 + §9.1 + §11.13
- 🐛 修复（活耕地，第八轮实测：火把花零产出 + 瓶子草无法种植，原版同型怪癖）——
  ① **火把花**：原版 TorchflowerCropBlock 的 AGE 属性是 AGE_1（值域 0~1）而
  getMaxAge()=2，成熟态 getStateForAge(2) 直接变花方块（作物方块没有 age=2 状态）；
  战利品表任何 age 只掉种子×1（与 FD 下部表同型：真实收获在花方块表）。我方双断点：
  matureStateFor 值域不含 2 → 冻结失败零产出、stateForAge(,2) null → 成熟渲染空白。
  修复：HARVEST_BLOCKS 补 torchflower_crop→torchflower（每轮产火把花×1）+
  **displayStateFor**（渲染专用：age 越界且已成熟 → 回退收获形态方块默认态）。
  ② **瓶子草**：PitcherCropBlock extends DoublePlantBlock 而非 CropBlock，白名单
  四 instanceof 全不中 + pitcher_pod 不在 c:seeds 标签 → 三层准入全漏。修复：
  白名单补 instanceof PitcherCropBlock 精确类（不用 DoublePlantBlock 兜底——
  玫瑰/牡丹/向日葵全是其子类）；其余路径零改动（maxAge 兜底=4、HALF 默认
  LOWER 恰好命中产出池条件、age 0~2 的 top 模型原版就是空几何）。教训：
  「种植入口 = CropBlock 子类」假设对双格作物不成立。新增 CropClassifierTest
  （7 项，纯逻辑），全量 254 用例全绿；收编 living-farmland-tech.md v1.4
  §4.1/§6.1/§9.1/§11.12/§12.2/§10.3
- 🐛 修复（活耕地，第七轮实测：FD 稻米/番茄成熟后零产出）——**下部战利品表只掉
  种子 + 留种扣空死循环**。根因双叠加：① 冻结实³滚的是种植入口方块自身的战利品
  表，而 FD 下部表只掉种子本身（rice.json → 稻谷×1、budding_tomatoes.json →
  番茄种子×1——FD「挖掉作物」的保底就是种子，真实收获在抽穗/结果藤上）；② 留种
  -1 把唯一一叠扣成 0 → 「留种后全空」按回退点重置 → 重长再扣空无限循环。
  修复：**收获形态注册表 HARVEST_BLOCKS**（作物方块 → 收获形态方块，CropClassifier
  新增：稻米→rice_panicles、番茄→tomatoes，注册名延迟解析 FD 缺席回退自身行为
  不变 + registerHarvestBlock 扩展点）——冻结时滚覆盖方块的成熟态战利品表：
  稻米每轮产稻穗×1（空工具原版语义，稻穗≠种子留种不扣）、番茄每轮产番茄×1~2 +
  种子×1（留种恰扣种子）+ 5% 烂番茄；番茄藤 VINE_AGE 属性名就是 "age" +
  ROPELOGGED 默认 false，matureStateFor 现有逻辑直接命中产出池条件零特判。
  守卫加固：「留种后全空」从按回退点重置改为**原样输出未扣减产出**（重置空转
  只会零产出循环）。教训：种植入口/成熟渲染/产出来源是三个独立关注点
  （getBlockFromSeed / getUpperCompanion / HARVEST_BLOCKS 各自承担），
  「自身表 = 收获形态」假设对多阶段作物不成立。全量 247 用例全绿；
  收编 living-farmland-tech.md v1.3 §6.1 收获形态解析 + §11.11 踩坑 +
  §9.1 FD 两行 + §12.2 验证项

---

- ✅ 修复：**活漏斗黑白名单链 tooltip 显示为空（同款 b064865 后遗症）**——
  「tooltip优化，nbt数据简化」删除了 tick 里 `data.withFilter(filter)` 的
  DataComponent 写回，但 tooltip 仍读 `LivingHopperData.filter()` → 恒 EMPTY，
  黑白名单永远显示无规则（用户实测「nbt里的数据没了」）。**功能链路经排查完好**
  （过滤本体是快照派生数据：HopperFilterBuilder 每 tick 从容器布局+物品重建，
  经修订计数失效机制保持新鲜，与 DataComponent 无关）。修复（熔炉燃烧标志同款
  方案）：过滤链迁至独立组件 `LIVING_HOPPER_FILTER`（FilterData 自带
  Codec+StreamCodec），tick 在快照重建结果变化时回写 + syncSlotToClients（稳态
  零写入；搬运后旧规则过期下一 tick 自愈）；tooltip 两处改读新组件；组件进
  `getIgnoredComponentTypes()`——过滤链是容器环境派生数据（同一容器里两个漏斗的
  规则必然不同），不忽略会破坏漏斗堆叠；`LivingHopperData.filter` 降级遗留兼容
  字段。回归测试 HopperFilterSyncTest（5 项：黑名单/白名单语义、稳态零同步、
  货物移走自愈、堆叠兼容），全量 247 用例全绿；收编 living-hopper-tech.md §2.4.2
  （存储位置沿革）。**追加（同日）**：`LIVING_HOPPER_FILTER` 与 `LIVING_FURNACE_BURNING`
  改为**不落盘**（只 `networkSynchronized`，vanilla `MAP_POST_PROCESSING` 先例）——
  两者都是每 tick 可从运行时状态重建的派生数据，持久化无正确性价值（玩家打开 GUI 前
  容器必然已 tick），徒增存档体积；组件照常随槽位同步包到客户端，展示链路不变。
  **再追加（同日审计后）**：全活物品审计确认无第三处同类问题（运行时缓存仅
  熔炉/漏斗/红电使用；红石家族/水车/水桶/耕地/箱子/地图全部「变化检测写+同步」配对，
  拉杆/按钮走 broadcastChanges 兜底）；`LIVING_FARMLAND_MOIST` 顺势统一为不落盘

## 2026-09-13

- ✅ 优化（活耕地，两格高作物上部件渲染）——**FD 稻米成熟后上方渲染满穗稻穗模型**
  （稻米 = RiceBlock + 上方独立 RicePaniclesBlock 两个方块，上部件无法推导 →
  CropClassifier 新增 UPPER_CROPS 注册表：字符串查找软依赖安全、FD 缺席自动跳过）
  + **原版半部件通用模式**（DOUBLE_BLOCK_HALF，瓶子草等：同方块 HALF=UPPER 同 age
  同步生长常驻）。渲染：主模型画完后解析上部件（getUpperCompanion）→ 生长槽正上方
  空槽 blit 其粒子图标；上部件槽位被占/顶行自动让位，纯视觉不影响 tick/产出。
  配套重构：stateForAge 迁入 CropClassifier（common）、findSlotAbove/blitSprite
  提取复用
- 🎚️ 优化（活耕地，第五轮实测反馈四连）——**种子图标可见**（耕地槽叠加 renderItem
  固定画在物品层 z=150 与耕地图标同层被覆盖，pose 抬升 z+150 到 300——map 渲染
  「需高于物品 150/堆叠数 200」同款经验）+ **骨粉简化**（一切行为由生长 tick 决定：
  骨粉=强制触发一次必定成功的生长 tick，未成熟 +1/成熟触发产出，无变化不消耗；
  +2~5 双语义已删）+ **种植消耗回归**（消耗与耕地堆叠数等量的活种子，数量不足
  无法种植；triggerFilter 升级 BiPredicate<trigger,target> 组合过滤，种子数不足
  不拦截原版交换，canPlantWith 双端共用）+ **湿润传播原版化**（水源相邻活耕地
  =3 级沿相邻活耕地逐跳 -1，≥1 即湿润 f=3.0——一个水源湿润 4 直接相邻+间接
  扩散 2 层，参考活红石粉信号传播 BFS 形态；computeMoisture 每 tick 从 fluidData
  现算派生态；水桶源槽位也算相邻）。技术文档 v1.2 同步
- 🎚️ 优化（活耕地，第四轮定稿：生长/产出节拍重设计）——**输出不限速**（待输出冻结后
  每 tick 推进进生长槽，不再 10 秒一项；生长槽被占只阻塞输出不影响生长，BLOCKED
  age 归零语义删除）+ **概率驱动获取**（成熟不着急滚战利品表，概率 tick 成功才冻结）
  + **留种**（冻结时 cropSeed 产出项数量 -1 变相自动补种，小麦种子 2→1）+
  **浆果丛采后回退 age=1**（查证原版 SweetBerryBushBlock：右键采摘后重置 1 保留
  2/3 进度，产出由概率 tick 模拟自动采摘；isBerryModeCrop → getHarvestResetAge）+
  **骨粉双语义**（未成熟 +2~5 到 maxAge 即时冻结；已成熟直接触发产出=确保冻结，
  输出下 tick 送达；组件无变化不消耗骨粉）。配套：耕地槽叠加改**种子物品图标**
  （renderItem 原版种子纹理，类型一眼区分）+ **湿润图标**（LIVING_FARMLAND_MOIST
  布尔组件每 tick 检测翻转写入+主动同步，图标 moist/dry 双变体切原版
  farmland_moist 深色纹理，零新 PNG；进 ignored 组件湿润/干燥可堆叠——燃烧标志
  同款先例）。状态机/参数表/验证清单全量重写 living-farmland-tech.md v1.1
- 🎚️ 优化（活耕地，第三轮实测反馈）：**裸通配条目吞掉原版右键操作**——plant_crop
  的 trigger=null 让客户端拦截活耕地的**所有**右键（空手分堆/拿起/放置全被吞，
  handler 静默返回时原版点击已取消）；对照活TNT（精确触发器 FLINT_AND_STEEL）
  从无此问题。修复：`InteractionEntry` 新增 **triggerFilter 谓词**
  （`Predicate<ItemStack>`），plant_crop 挂 `CropClassifier::isSeedPlantableOnFarmland`
  ——拦截面从「任意光标」收窄到「可种植种子」，其余光标不匹配不发包，原版操作照常；
  服务端 handler 校验保留为双重防线。规则收编 gui-interaction-system.md §5.1：
  特定物品交互→精确触发器；「一类物品」交互→triggerFilter；真·任意光标自交互
  （按钮/拉杆）才留裸通配。踩坑记录 living-farmland-tech.md §11.7
- 🐛 修复（活耕地，同轮）：**取消活化残留 farmland_plant 组件**——clearLivingData
  是取消活化的统一清理点（javadoc 明确要求新功能补 remove），实现时漏了 FARMLAND_PLANT
  → 取消活化 tooltip 仍显示旧作物、再活化旧状态复活。已补
  `stack.remove(FARMLAND_PLANT.value())`（§11.8）
- ✅ 新增：**活耕地**（设计 idea.md → 技术文档 living-farmland-tech.md v1.0，全量实现 + 三轮实测修复）——
  获取（活锄头耕活泥土，6 锄头变种规则）/ 种植（活种子右键，**种子不消耗**=类型标记，
  数量与堆叠数解耦）/ 骨粉催熟（+2~5，Mth.nextInt 双闭=原版公式）/ 生长（世界轴
  时间戳节拍 200t，湿润左/右/下活水流 f=3.0 vs 干燥 f=1.0，稳态零组件写入）/
  产出（round-robin 逐项：战利品表 Block.getDrops 首轮冻结进组件 pendingDrops，
  每冷却周期一项 ×耕地堆叠数到 E_UP 生长槽，标准模式产完重长/浆果模式持续产出）/
  渲染（双槽：耕地槽 16×16 作物小图 + 上方空槽阶段大图；CropTextureResolver 走
  blockstate→模型→粒子图标查表，覆盖胡萝卜 8-age→4-stage、下界疣共用模型等原版
  映射）+ 茎作物产出来源=果实战利品表（StemBlock.fruit AT）+ 甜浆果手动映射
  （SWEET_BERRIES 非 BlockItem，三层准入全漏）
- 🐛 修复（交互，09-12 第一轮实测）：**按下拦截后释放阶段原版 PICKUP 二次执行**——
  原版对「光标非空」的放置/交换发生在 mouseReleased，按下取消拦不住；三个 Screen
  mixin 拦截成功处置位原版 skipNextRelease 让释放自我跳过。手持触发型交互（锄头/
  种子/骨粉）交互生效的同时不再与槽位交换；ignite 老交互的打火石换位副作用一并治好。
  收编 gui-click-interception.md 坑 10 + 经验总结第 10 条
- 🐛 修复（生长，09-13 第二轮实测）：**活骨粉不能催熟 = 通配条目遮蔽精确条目**——
  plant_crop（trigger=null）与 bonemeal 同 target/button 注册，findInteraction 注册
  顺序首配 → 骨粉永远命中 plant_crop，BonemealHandler 死代码。根修：
  findInteraction 改两趟匹配（精确触发器优先于通配，通配语义=兜底而非抢先）+
  bonemeal 条目改精确触发器 BONE_MEAL；顺修骨粉 +2~4（RandomSource.nextInt 上界
  排除）→ +2~5、催熟到 maxAge 即时冻结战利品表（不等下个冷却周期）、创造模式
  种植跳过可种植性校验（carriedTag 已恢复可校验）。守卫：InteractionRegistryTest
  （5 项，故意按最坏注册顺序断言精确优先）
- 🐛 修复（渲染，同轮）：**作物不生长不渲染**——顶行耕地 growthSlot<0 被误算进
  slotBlocked → 永久 BLOCKED age 恒 0（与规则②矛盾）；修为仅「生长槽存在且被占」
  才 BLOCKED，顶行正常生长、产出挂起（搬到有生长槽位置自动开始产出）。双槽渲染
  补上方生长槽大图（坐标匹配 y-18 同容器空槽即画，自动覆盖生长中/BLOCKED/产出/
  取走全状态）。定界收编 living-farmland-tech.md §11.3/§8.2 +
  gui-interaction-system.md §5.1 通配遮蔽坑
- ✅ 文档：**idea.md 完成使命收口**（内容转化进 living-farmland-tech.md 后保留
  历史指向）；活耕地三交互的实测坑链（释放二次执行/通配遮蔽/顶行 BLOCKED）全部
  收编。全量 240 用例全绿（235 基线 + InteractionRegistryTest 5 项）

---

## 2026-09-11

- 🐛 修复：**φ 重进漂移第三轮：坐标系换轴（游戏二次实测定位真根因）**——玩家
  反馈前两轮修复后 tooltip 的 φ 仍重进即变（16 周期 φ=14 → φ=12），三元件
  （雕文/切制/格栅）基于 φ 的派生随之漂移。真根因：φ = lastRisingTick mod P
  锚在**容器本地 tickCounter**（重进从 0 起步），而中继器物理相位（delayTimer
  落盘）重进后续跑——两个原点互不相关，首跳落位随机 → φ 重锚。前两轮防的是
  「数据被污染」，没发现**坐标系本身重进就换**；旧回填 `base−sinceRise` 跨轴
  平移在轴差 ΔW 下 φ 平移 (ΔW mod P)。修复（三处）：① 相位时钟换世界轴
  `resolvePhaseClock`（level.getGameTime()，跨会话连续，与振荡器物理相位
  同源；Level 不可达回退本地轴保持单测可驱动），capture/restore 同轴适配；
  ② 回填锚定数学改 **φ 反推锚** `anchor = now − ((now−φ) mod P)`——offset()
  恒等于快照 φ 且首跳 interval 恰为 P（手算双验证；方向踩坑记录：必须减
  now 的余数，减传入锚的余数会推向过去同余点使 interval 变 2P）；sinceRise
  从此只用于活性窗口。③ 测试 mock Level 补递增 getGameTime（未 stub 恒 0
  → 跳变挤 tick 0 → 派生空，新增 mockServerLevel helper）。新增世界轴快照
  往返守卫（capture@世界W → restore@W+1000 → φ 不变，旧轴必失败）；两个
  旧「负锚」断言按新锚定数学重写（负锚是旧平移公式产物，已退役）。全量
  235 用例全绿；教训链（防污染下游→防污染入口→换坐标系）收编
  living-power-tech.md §6.5 第三轮复盘小节。**游戏实测确认（用户验证）**：
  原版红石线路 + 活红石线路（含跨容器）重进相位稳定 ✓；残留边界——第三方
  模组红石元件作跨容器信号源时相位仍可能漂移（该元件相位由其 mod 方块
  内部状态驱动，重进重建时序不受我方控制，无 API 可介入）→ **定界为我方
  契约边界而非缺陷**（我方保证「信号进入活红石网络后跨会话稳定」），已记
  living-power-tech.md §8 已知限制表 + 红电系统.md §3.11 生态兼容实证第 4 条

---

## 2026-09-09

- 🐛 修复：**相位重进洗牌第一轮修复无效（游戏实测复盘）→ 三 bug 根修**——第一轮
  warmup+PhaseSnapshot 双修复存在覆盖漏洞：① warmup 只拦 PhaseEvent 注入不拦
  `tracker.onRisingEdge`——首拍假沿把回填平移的负锚覆盖成 0，快照恢复的 φ 当场
  被毁，周期 EMA 随后被拉偏、相位域散裂（warmup 结束后注入的全是污染数据）；
  ② `restoreInto` 里 `clearWarmup()` 把剩余防线也拆了；③ `worthSaving` 的「时间轴
  错乱防御」误拒回填负锚（lastRisingTick<0 是回填合法状态）——窗口内二次退出
  即丢相位。根修：`RedstoneSensor.hasEdgeHistory()` 接口 +
  `ContainerRedstoneData` 本会话首次 calculate 置位，`runBfs` 无历史时**整段跳过
  边检测**（跟踪+注入都跳，假沿从源头不进系统；负锚存活到首个真实跳变，
  interval=(P−d)−(−d)=P 精确续接）；测试 seam `setIncomingEdgeForTest` 声明
  历史；`restoreInto` 不再 clearWarmup（宽限与快照互补）；`worthSaving` 只拒
  未来锚。测试影响：E2E 驱动循环补首拍热身（对齐真实时序），PhaseInterpretation
  3 处 φ 断言按热身时间轴 +1 修正（相对关系不变）。全量 234 用例全绿；
  复盘记录收编 living-power-tech.md §6.5（根修复盘小节）
- ✅ 修复：**退出重进后容器内线路相位洗牌（调相布局失效）**——相位账本是纯内存
  缓存，账本死亡（LRU 120s 回收 / 退出重进 / 跨存档搬运）后所有振荡器从零重锁，
  多路相对相位被「重载时刻」重新锚定：玩家调好的满相布局（n=P）不再适配，冗余
  线路只能缓解。双修复：① **首拍无沿宽限（warmup）**——新账本默认前 8 tick
  电力层不注入 PhaseEvent（照常跟踪边信号重建周期估计），防「prevEdgeGrid 空 →
  稳态电平全伪装成上升沿」的假沿风暴；② **PhaseSnapshot 相位快照落盘**——
  `CONTAINER_PHASE_SNAPSHOT` BE 附件（带 Codec serialize 真正写入存档，全 mod
  第一个跨会话容器级附件——流体/应力附件均无 Codec 仅会话内存）每 tick 末冻结
  已锁相边的 (P, φ, sinceRise, δ)，账本重建时平移回填（整数周期下 φ 不变），
  回填成功跳过 warmup。只存慢变量（锁相结果），快变量（EMA/注册表）从真实
  跳变重学——改线后旧快照 2~3 周期自愈，无磁盘说谎面。顺带修
  `SignalTracker.offset()` 负 tick 的 `%` → `floorMod`。测试
  PhaseSnapshotWarmupTest（5 项），全量 226 用例全绿；收编 living-power-tech.md
  §6.5（账本重生的相位连续性）+ §6 测试表
- ✅ 修复：**超大堆叠灯堆对外读数 int 回绕**——C=1M 标定后 count ≥ 2,148 盏
  （2³¹/1M = 2,147.48）的堆总量越 IEnergyStorage 的 int FE 公约（上限 21.4 亿），
  `(int)` 强转回绕成负数 → 外部 mod（如 Mek 电缆 `max−stored` 缺口判定）读到
  巨大正缺口往死里灌的怪行为。修复：两个读数出口（`ContainerEnergyStorage`
  容器面 + `BulbItemEnergyStorage` 物品面）`Math.min(真值, Integer.MAX_VALUE)`
  clamp，语义「至少 21.4 亿 FE」；内部 long 账本/充放电/守恒全程无损（本次
  只治读数出口）。回归测试 BulbItemEnergyStorageTest.oversizedStack_
  readsClampToIntMax（2,148 盏满堆断言双读数 = Integer.MAX_VALUE），全量 221
  用例全绿；oversized-stack-audit §2.8 🟠→🟢（已修复）。顺带修正前日文档
  阈值笔误 2,142 → ≥2,148（四处 + javadoc）
- 🎚️ 标定：**铜灯每盏容量 C 10,000 → 1,000,000 FE**（`PowerMath.BULB_UNIT_CAPACITY_FE`，
  标定史 1k→10k→1M，游戏实测「10k 不太够用」驱动）。一堆(64) = 64M FE，对齐
  科技生态中高档单格电池（Mek 能量立方 / Flux 储存器档位），充电宝物流彻底实用
  （一盏 = 一个满配方块电池）。耦合面：2 处容量断言修正（BulbItemEnergyStorageTest
  满容读数 16M；WaxedCopperStorageTest 半满基准 500_000_000 保持 2:1 本意），
  全量 220 用例全绿。**int 收窄警戒线升级**：对外 IEnergyStorage 是 int FE，
  单堆读数溢出需 count ≥ 2,148 盏（2³¹/1M = 2,147.48）——原版 64 安全，超大堆叠容器（抽屉类）已可
  触达（读数回绕，long 内部账本无损，fail-safe），oversized-stack-audit §2.8
  由「纯理论」升「已可触达」并附修复方向（读数 clamp + 分批，待需求驱动）。
  标定记录收编 红电系统.md §3.6 / living-power-tech.md §4 / oversized-stack-audit.md §2.8
- 🐛 修复：**铜灯往返凭空造电（零头回收记账 count 倍放大）**——两个装满铜灯的原版
  容器用 Mekanism 电缆互传，总电量缓慢上升（实测每 tick 约 2 mFE，1000t ≈ +2043 FE）。
  排查：翻 Mekanism-1.21.x 源码确认电缆侧教科书守恒（SIMULATE 探测→convertFromAndBack
  钳制→EXECUTE、网络按返回值记账、FE↔J 双向防增益），锁定我方
  `ContainerEnergyStorage.receive` 零头回收循环：给灯堆写 `q+1` 实充是
  count mFE（每盏模型），账面只 `distributed++` 记 1 mFE——实充 = 记账 × count，
  差额被电缆按返回值记账后全部变成净增益。复现测试 `RoundTripConservationIT`
  （4 项：Mekanism 传输协议逐条复刻的 1000t 往返仿真——先红后绿；拉侧/推侧
  单侧诊断定位到推侧；extract 记账契约最小复现）。修复：零头回收按 count 记账 +
  完整步进保护（count>leftover 跳过，不越过 accept）+ 残余 ≤63 mFE 保守丢弃
  （宁损勿造）+ `BulbItemEnergyStorage.receive` 声明改 ≥实充（ceil，同族缺陷，
  直接 EXECUTE 的调用方按返回值记账时旧口径可白拿 count−1 mFE）。全量 220
  用例全绿（211 + 4 新增 + 数值巧合）；根因与修复记录收编 living-power-tech.md
  §6 测试表（历史 BUG 注记）
- ✅ 修复：**活水车/活水桶在大箱子中动画与 tooltip 停留在开箱快照**——应力/
  水流变化后旋转动画不实时播放、改变水流方向后旋转方向不翻转、tooltip 数字不动，
  必须关闭再重开容器界面才恢复；单箱一切正常。根因双通道失效：主动通道
  `SimpleContainerContext.syncWorldContainer` 的容器归属匹配是纯实例比较，而原版
  大箱菜单槽位容器是 `new CompoundContainer(左半BE, 右半BE)` 包装对象——匹配永远
  不命中，`syncSlotToClients` 的组件同步包从不发给大箱查看者；被动通道原版
  `broadcastChanges` 又因 `ItemStackMixin` 忽略 `LIVING_WATER_WHEEL_DATA`（堆叠
  兼容）对这些组件失明。即 v19.1 修 `ContainerRuntimeCache.isViewingContainer`
  （大箱遥测 tooltip）时漏掉的**平行断点**。修复：归属判断抽成 `slotBelongsTo()`
  照既有修法补 CompoundContainer.contains(be) 分支；影响面覆盖所有走
  syncSlotToClients 的活物品数据在大箱中的实时同步（水车应力/水桶水流/熔炉进度等），
  全量 216 用例全绿；踩坑记录收编 living-water-wheel-tech.md §9.23 +
  living-item-infrastructure.md §8.5（大箱匹配补丁）+ tooltip-system.md §7 坑清单
  第 3 条升级为双先例通用规则 + living-water-bucket-tech.md 验证清单补大箱项
- ✅ 修复：**活熔炉图标不切换 active/idle**——熔炼时图标永远停在 furnace_idle.png。
  根因：图标谓词 `LivingFurnaceFunction.isBurning(stack)` 读物品 DataComponent，而
  b064865（09-03「tooltip优化，nbt数据简化」）把 burnTime 迁到了运行时缓存
  （ContainerRuntimeCache + LivingItemSyncPacket），该链路唯一消费方是 tooltip，
  客户端 ItemStack 上燃料恒为默认值 → isBurning() 恒 false（活TNT 图标正常是
  因其数据未迁缓存，反向印证）。修复（方案2，不动运行时缓存架构）：新增轻量布尔
  组件 `LIVING_FURNACE_BURNING`，tick 在燃烧状态**翻转时**写标志 + syncSlotToClients
  （稳态零写入零同步；跨容器搬运后过期标志下一 tick 自愈）；`isBurning()` 改读标志；
  组件进 `getIgnoredComponentTypes()`（该机制第一个真实使用者）——燃烧中/熄灭
  熔炉仍可堆叠。回归测试 FurnaceBurningFlagTest（5 项：点燃写入/熄灭移除/稳态
  零同步/自愈/堆叠兼容），全量 216 用例全绿；修复记录收编 living-furnace-tech.md
  §8.11（§1.4 存储结构、§2.1 tick 示例同步更新为运行时缓存架构现状）

---

## 2026-09-08

- ⛔ 暂时关闭：**雕文移相链（读邻居注册表派生相位）**——链式组合允许任意频率信号
  堆出任意偏移，单台雕文 + 足够长的链即可凑满相（n = P），满相增益的成本退化为
  纯材料堆叠，绕过「真多相要靠布局与时序理解」的核心设计，超模。关闭点：
  `interpretShifter` 的「输入二：读输入方向邻居的注册表驻波」整段注释停用
  （代码保留，见方法体内注释）；关闭后雕文与切制/格栅同口径——只读真实边信号，
  单级移相（φ+1）保留，「链而非环」的结构性防环论证仍成立（重启无需重审）。
  测试：`shifterChain_twoLinks_n3` 改造为关闭态回归守卫（断言链断：B 无派生、
  n=2；原断言留注释备重启恢复）。重启前置条件：先设计增益约束（δ 衰减 /
  n 折算上限 / 链长入解锁度等）。全量 211 用例全绿；文档标注收编
  living-power-tech.md §3.8（含跨锈级读取小节）+ 红电系统.md（顶部 v19.1 修订、
  形态对照表 ×2、采样拓扑表、跨锈级小节、§3.12 矩阵）
- 🎚️ 标定：**铜灯每盏容量 C 1,000 → 10,000 FE**（`PowerMath.BULB_UNIT_CAPACITY_FE`，
  与 K 并列的第二个硬数，纯储能改动不动 K/发电口径）。依据：① 生态对标——旧值一堆(64)
  64k FE 低于科技生态最低档单格电池（TE 能量格/IE 电容 LV 约 10 万），新值 640k FE
  对齐基础档；② 充电宝物流（充满铜灯拔下搬运）旧值下搬 1M FE 需 16 堆超背包，
  新值 1.6 堆即可用；③ 满溢反馈环保存——典型功率下一堆 0.5~2 小时充满，「已满」
  信号仍能触发（更激进的 100k 档该信号消失，故不取）。q 按 mFE 绝对值存储，
  扩容对存量灯白赚头寸、无迁移；int FE 收窄阈值 214 万 → 21.4 万盏（仍远超容器
  上限，fail-safe）。耦合面：2 处容量相关断言同步修正
  （BulbItemEnergyStorageTest 满容读数 ×10；WaxedCopperStorageTest 充电比例
  分配的半满基准重设 5_000_000 保持 2:1 本意），全量 211 用例全绿；
  标定记录收编 红电系统.md §3.6 / living-power-tech.md §4 /
  oversized-stack-audit.md §2.8
- 🔍 调查：**Flux Networks 取电方块（Flux Plug）不能从铜灯容器取电——上游固有设计，非我方缺陷**。
  该 mod 存电方块（Flux Point，推送方）充电正常；取电方块 Plug 从不主动拉取邻块电量，
  只暴露「可被充入」的电池面等邻块推电（源码验证：拉取 API
  `receiveFrom`/`canReceiveFrom` 在 1.21 源码全库零调用，纯死代码；1.20.1 原版 jar
  字节码交叉验证同样无拉取点 → 非移植丢功能）。我方 `ContainerEnergyStorage`
  是标准被动电池面（`canExtract=true`），对纯被动等待的 Plug 天然不可见；
  Mekanism 电缆双向皆主动故全通。结论收编至 living-power-tech.md §8 已知限制表 +
  红电系统.md §3.11 生态兼容实证；如需兼容 Plug 需让容器主动推电，
  与「限流职责归用电侧」原则相悖，待需求驱动再议
- ✅ 修复：**堆叠活箱子取消活化只返还一份内容**——组件相同才可堆叠 + 堆叠期间存取
  关闭（count>1 拒绝一切操作），故 count=N 的堆叠活箱子语义上是 N 个各含一份
  相同内容的箱子；而 `dropAllItems` 只掉一份 `CONTAINER` 内容，N−1 份凭空蒸发。
  该场景**原版背包即可触达**（两个内容相同的活箱子自动堆叠），并非超大堆叠容器
  专属（超大堆叠审计 §2.4 的「count>1 = 语义开关」结论在掉落路径上的漏网）。
  修复：返还清单抽成纯函数 `collectDeactivationDrops()`（每槽总量 = count × N，
  超物品堆叠上限拆满堆），`dropAllItems` 复用。回归测试 LivingChestFunctionTest
  （6 项），全量 211 用例全绿

---

## 2026-09-07

- ✅ 审计：**超大堆叠表现探查**（[oversized-stack-audit.md](docs/system-design/oversized-stack-audit.md)）——
  模组容器（抽屉等）堆叠上限 > 64 场景下，全库 202 处堆叠敏感调用点逐领域源码分析 +
  原版语义对照。结论：传输基础设施层干净（统一 min(slotLimit, maxStackSize)，吞吐按
  模组上限走）；14 项设计内缩放（TNT √count / 熔炉 count 倍速 / 红石 count² 信号上限——
  原版消费端 j>=15 早退钳制天然饱和 / 铜灯 mFE long 定点）；1 个真实缺陷（超堆叠岩浆桶
  燃料整槽替换吞 N−1 个——原版同吞但不可达，模组容器使其显形，修复方向待决策）；
  2 个理论 int 溢出（铜灯对外 int FE 收窄 count>214 万盏 / TNT 累加 >21 亿——均 fail-safe
  不崩不刷）；2 个吞吐观察项（漏斗单次固定 64 不随目标容量自适应）

- ✅ 修复：**活熔炉岩浆桶整桶被吞**——`tickFuel` 消耗燃料只 `shrink(1)`，漏掉 crafting
  remainder；燃料消耗收拢到 `consumeFuel()`，对齐原版熔炉点燃语义（带残留物的燃料
  整槽替换为残留物：岩浆桶→空桶；普通燃料扣 1 个、耗尽清空）。回归测试
  LivingFurnaceFunctionTest（4 项），全量 205 用例全绿（基线 183 + 红电工作区未提交用例）

- ✅ 重组：**活潜影箱从红电系统剥离为独立基础设施**——红电系统.md 第 2 章（Phase 2）
  整体迁出（原章节留编号存根，§3.x 与外部锚点稳定）；总览/里程碑/风险表/复用表同步更新；
  idea.md 的活潜影箱创始定义一并合并。新文档 **活潜影箱实现细节.md**（v2.0）：
  基础设定（兼容**所有活物品**而非当前已实现集合——通用活物品基础设施，随新活物品
  逐个补充嵌套实现细节）+「最伟大的活物品」愿景 + 创始定义 + 边界连续性/芯片封装
  （原红电 §2.2~2.7）+ 局部演化+边界交换模型（旧全局视图方案否定）+ 2D↔3D 桥接
  （世界引脚面）+ 与红电的单向协同说明 + **§8 各活物品嵌套细节台账**（活物品实现
  时的补充主线：漏斗/红石/熔炉/水桶/TNT/箱子/末影箱/水车/铜块/避雷针逐格待补）

- ✅ 新增：**红电不变量清单与四层测试方案**（[power-invariants.md](docs/system-design/power-invariants.md)）——
  把红电系统.md 的每条设计原则翻译成 **31 条可执行不变量**（记账 5 / 相位 6 / 共振 5 /
  储能 5 / 集成 7 / 显示 3），每条标注断言入口（真实类与方法）与断言草图；
  附**历史 bug ↔ 不变量映射表**（16 个已修 bug 逐一对应可拦截的不变量，证明
  「没有一个能靠多测一个场景拦住」）+ 四层测试实施方案：
  L1 jqwik 属性测试（工具定选 jqwik，自带 shrink；先过 FML 冒烟门）/
  L2 种子驱动 ScenarioBuilder 场景生成（processContext 生产路径）/
  L3 蜕变测试（频率×k / 平移 / 复制 / 幅度缩放四关系）/
  L4 运行时监控（PowerInvariantMonitor 每 tick 断言 + 违反即 ERROR 快照，
  玩家 = 模糊测试器；前置 distributeToBulbs 返回 boolean→long 小重构）。
  本轮只交付文档（定稿待实施），代码步骤见文档 §6 路线图

---

## 2026-09-05

- ✅ 新增：**记账跳变门控**——入账只看「本 tick 有跳变」的最佳域，能量 = 合因子 × P × 跳变路数
  （按 offset 去重）。修复旧「每 tick 无条件入账 ×P」的频率中性化反转
  （慢时钟按 P 线性碾压快时钟，整数倍谐波可无限放大）与停机虚能量（域存活窗口内白拿）
- ✅ 新增：**相位解读三元件**（形态从「听什么」到「造什么」，普通铜块退役为纯基准）——
  雕文 = 移相器（派生 φ+1，可链式 = 任意偏移延迟线，解锁奇数偏移制造）/
  切制 = 裂相器（下降沿反相解读，一个方波 2 个反相相位，P=2 满相可达）/
  格栅 = 相位加法器（同周期多路 Σφᵢ mod P 求和，加法子群涌现）
- ✅ 新增：派生相位注册表（`DerivedPhase` + 两阶段提交 + 存活窗口修剪 +
  移相环无种子自熄——结构性防环，无检测代码）
- ✅ 重构：拓扑统一至锈级单维度（TopoKey 的 axis/inEdge/outEdge 退役，
  切制 H/V 双通道拆除，`channelSecondary` 删除）
- ✅ 新增：AccountingGateTest（4 项）+ PhaseInterpretationTest（8 项），
  全量 183 用例全绿
- ✅ 移除：红石稳态跳过优化（v19.1）——三闸门模型吞掉火把振荡器的「翻转后果 tick」
  后靠 rev/sig/timer 不变自维持死锁，游戏内振荡线路集体静止（SteadyState 删除，
  calculate 恢复逐 tick 全量重算；回归守卫 torchNotRing_oscillates）
- ✅ 修复：**大箱子 tooltip 不显示遥测**——原版大箱菜单容器是 CompoundContainer(be1,be2)
  而非 BE 本体，`ContainerRuntimeCache.isViewingContainer` 按实例匹配永远失败 →
  LivingItemSyncPacket 从不发给打开大箱的玩家 → 客户端遥测缓存为空（发电本身正常，
  铜灯正常充电；改用 CompoundContainer.contains(be) 匹配）
- ✅ 修复：**高频振荡下 tooltip FE/t 高频闪烁**——跳变门控记账是脉冲式的，快 EMA
  纹波超过量化精度；显示读数改用「与偏好周期对齐的窗口均值」（GeneratorState 窗口 =
  ceil(32/pref)×pref，ContainerPowerData 32t 固定窗口；稳态零纹波，记账 EMA 不动）
- ✅ 修复：**F3+H 仪器面板（波形图/锈级柱状图）消失**——面板挂载点仍在读
  DataComponent，而发电遥测已改走运行时缓存+网络同步；改按悬停槽位定位运行时缓存
- ✅ 修复：**小功率发电（<1 FE/t）EMA 不显示**——显示均值链路改毫 FE（mFE）定点：
  窗口均值去 long 整除、遥测字段 emaPowerMilliFe/levelEmaPowerMilliFe/levelPowerMilliFe
  （codec 同步改名），tooltip 功率行两位小数显示（如 0.94 FE/t）
- ✅ 修复：**玩家背包里 tooltip 不显示遥测**——背包容器的遥测包被 isViewingContainer
  的空实例匹配/inventoryMenu 排除双重拦截，从不发送；改为「player_」前缀 key 直发本人 +
  客户端独立 player 缓存（与 BE 容器缓存分离，防串台），InventoryScreen/面板挂载点分流读取
- ✅ 修复：**创造模式背包 tooltip 不显示**——CreativeModeInventoryScreen 的菜单槽
  结构特殊（槽位索引与 Inventory 不对齐），悬停定位失败；补「物品引用匹配玩家背包」
  兜底（wrapper 索引 = Inventory 索引，引用必然相等），覆盖创造模式与特殊 mod GUI
- ✅ 修复：**高频下共振平衡度/增益闪烁**——共振读数口径（平衡度 s、增益 R²、活跃锈级数 N）
  从快记账 EMA 切到显示窗口均值（32t，零纹波）；三条铁律结构不变（窗口只吃基础值、
  单遍前馈、结算即精确归零），首个窗口（32t）为共振建立期
- ✅ 修复：**活漏斗锁定/活 TNT 点燃失效**——v15 出边模型下漏斗/TNT（非红石组件）
  槽位的出边恒 0，`getSignal`/`getSlotSignal` 读自身出边永远拿不到信号；
  改读四方向入边（邻居朝本槽的出边，与电力层采样同语义）
- ✅ 修复：**涂蜡雕文感应方向恒为上方**——`pos2dToEdgeDir` 用引用比较（`== Pos2D.X`），
  而 Pos2D 经组件序列化/反序列化后是值相等的新实例 → 所有配置过的方向全部落入
  fallback 恒 UP（只有上方信号被感应）；改值比较（x/y 判断）根治
- ✅ 重构：**RedstoneSensor 感知端口**——电力层采样/漏斗锁定/TNT 点燃的信号读取
  收口到统一接口（依赖收窄到接口，edgeGrid 边模型后续重构只改端口实现）；
  演进路线（事件流/元件接口化）沉淀至 redstone-evolution-roadmap.md
- 📄 技术文档：living-power-tech.md §3.8 / 红电系统.md v19.1 修订

---

---

## 2026-09-04

- 🐛 **修复：游戏崩溃 —— `living_item_sync` 包方向注册错误** —— 打包 jar 里注册成了 `playToServer`，但它是**服务端 → 客户端**包（`flushToClients`/`sendSnapshotToPlayer` 都在服务端调 `player.connection.send()`，`handle()` 更新客户端缓存 `LivingItemClientCache`），被 NeoForge `NetworkRegistry.checkPacket` 拦截抛 `UnsupportedOperationException: Payload living_item:living_item_sync may not be sent to the client!`。当前源码里该包连注册都没有（调试时被删）。修：`LivingItem` 加 `registrar.playToClient(LivingItemSyncPacket.TYPE, STREAM_CODEC, LivingItemSyncPacket::handle)`（放在 `LivingChestAccessPacket` 之后）
- ✅ **全包方向审计（防同类复发）** —— 10 个包逐个核对发送点与 `handle()` 归属，结论**只有 `living_item_sync` 一个错**：→服务端 6 个（`LivingTag`/`HopperDirection`/`SlotDirection`/`GuiInteraction`/`LivingMapGuiTeleport`/`LivingChestAccess`，均在 `client/**` 经 `PacketDistributor.sendToServer`）、→客户端 4 个（`CarriedUpdate`/`EnderChannelSync`/`LivingMapMetadata`/`LivingItemSync`）。反编译产物验证 playToClient×4 + playToServer×6 = 10 个注册已进 jar
- ✅ **测试基线修正** —— `AGENTS.md` 原写「146 项」是**方法数**（141 `@Test` + 5 `@ParameterizedTest`）；实际执行 **169 用例**（参数化展开 +23）。基线 `169 passed / 0 failed / 0 skipped`
- 📄 排查套路留档：① `grep -n "Exception\|Error\|FATAL" latest.log | tail -30` 直接跳崩溃段；② 崩溃栈**行号要回源码核对**（对得上=jar 与源码结构一致，可放心按源码修）；③ NeoForge 方向报错话术「may not be sent to the client」= 注册成了 `playToServer`；判方向看两点——谁调 `send()`（`player.connection.send` = 服务端发）、`handle()` 更新哪侧缓存

---

## 2026-09-03

- 🐛 **Bug A 修复：比较器链到第 4 个就死** —— 根因三者叠加：`reset()` 每 tick `edgeGrid.zero()`（无跨 tick 累积，链断不自愈）+ `powerConductiveNeighbor` 对元件位（含 `BIT_COMPARATOR`）直接 return（上游比较器不把下游入队）+ `comparatorSlots` 是 `HashSet`（迭代顺序是**哈希桶序而非槽位序**）⇒ 能亮几级 = HashSet 顺序碰巧与链几何吻合的长度，换摆放/方向就变。修：`phase4PowerConductors` 改**迭代到稳定**（`for (iter=0..size+2)`，仅 output 变化才 `edgeGrid.set` + 下游入队，无变化 `break`）。稳态无依赖变化时一轮即停、零额外开销
- 🐛 **Bug B 修复：无外接信号时容器内信号层全死** —— 根因：`bumpContainerRevision` 只被本模组写入路径（`SimpleContainerContext.setItem` / `syncSlotToClients`）调用，**原版玩家点击/漏斗/掉落物拾取直接改底层容器、绕开它们** ⇒ 修订计数停滞 → `calculate` 稳态跳过永不打破 → 快照缓存永不重建 → 信号层集体死。修：`ContainerLivingItemHandler` 新增 `CONTAINER_CONTENT_SIG`（逐槽位 物品 id+数量 的内容签名，**空槽参与混合**保证槽位移动可检出）+ `computeContentSignature` / `syncContentRevision`，在 `processContext` 槽位扫描后调用（复用已有扫描、零新开销）
- ✅ **P1-4 落地：7 张静态 Map 合并** —— 六张同键 Map 合成 `Map<String, ContainerEntry>` + 嵌套 `ContainerEntry`（fluid/redstone/power/revision/contentSig/cachedSnapshotRevision/cachedSnapshot）；`cleanupStale{Fluid,Redstone,Power}Data` 三法合一为 `cleanupStaleData`；`clearAllCaches` 7 次 clear 收敛为 2 次；删约 120 行
- ✅ **P1-5/6 落地** —— `processContext` 主路径 + 空容器分支用 **try/finally** 包住（原先功能 `tick()` 抛异常会跳过 `flushDirtySlots`（客户端脏槽不同步）+ `setTickContext(null)`（stale 上下文泄漏），且 `onServerTick` 无 try/catch 向上抛）；`scanAndGroupLivingItems` / `runContainerDataTicks` 安全提取为私有静态
- ✅ **代码屎山复查（用户以为很脏，实为文档过时）** —— P0 三处中**两处是误报**：`setItem`「Container 优先 / 丢物品」实际自 2026-08-17 起**故意**统一走 `IItemHandler`（大箱子左右两半共享同一 handler 但各是独立 `Container`，走 `Container.setItem` 会**双记账 → 物品复制 bug**）。**⚠️ 千万别照旧文档 §7.2 的伪代码去补 Container 优先写入，会重新引入复制 bug。** 已改文档并加警告。第三处（`syncWorldContainer` 用 `==` 引用比较匹配槽位）是**真问题**但缠双箱子索引映射，需单独正经修
- ✅ **自由巡检（资源完整性 + 文档漂移扫描）** —— 修真 bug：`tooltip.livingitem.transform.no_recipe` 在 `zh_cn.json` 缺失（en_us 有）⇒ 中文客户端直接吐原始 key，已补；修复后 en/zh 均 169 key 零差异。排除 4 处误判：8 个 `*_lit.json` 带 BOM（Gson 2.10.1 无条件消费 BOM，不是 bug）、`getLastTickTime` 每 tick 刷新（非「120 秒重置」）、`ContainerMonitor` 首行短路（零开销）、lang 拼接 key 的正则误报
- ✅ **项目整洁化清理** —— 删 `_lit_broken_backup/`；修 8 个 `*_lit.json` 的 BOM + 混合换行。**关键发现：BOM 从不存在于 git**（`core.autocrlf=true` 把工作树 CRLF 归一为仓库 LF，HEAD 版本头字节无 BOM），只在工作树由某 Windows 工具改写产生且从未 `git add` ⇒ `git diff` 为空是预期、不是改动丢失。同步文档测试索引（146）+ 5 个文档的旧字段名 `*_DATA_CACHE` → `CONTAINER_DATA`。撤销「取消跟踪 `.trae`/`.zcode`」（用户要求保留）
- ✅ **BOM 深挖结论：不是 bug** —— gson `JsonReader.fillQueue()` 无条件消费 BOM 且**与 lenient 开关无关**，任何解析路径都跳过。**更有价值的副产品**：那 8 个文件**换行是混的**（6 个换行里只 1 个 CRLF），与同目录其余文件（规整 CRLF 无 BOM）不同生成路径 ⇒ **只清文件不找生成源会复发**
- 📄 技术文档：`living-power-tech.md` v4.2、`living-redstone-tech.md` v15、`living-item-infrastructure.md` §7.2（加「别加回 Container 写入」警告）

---

## 2026-09-02

- ✅ **红电「网络级共振」设计定稿 + 实现完成** —— 锈蚀级 = 共振「**声部**」，共振发生在**网络之间**而非铜块之上（把块级感应机制原样升维）。`N = |{k : ema[k] > 0}| ∈ [1,4]`、`s = (Π Aₖ)^(1/N) ÷ (Σ Aₖ / N)`（几何平均 ÷ 算术平均）、`R = 1 + (N−1)×s ∈ [1,4]`、总发电 = `(Σ baseRe[k]) × R^exp`（`RESONANCE_EXPONENT = 2`，满共振 ×16）。**铁律：共振只读「共振前」基础值、单遍前馈、绝不回代**（否则形成 `R↑→EMA↑→s变→R变` 回代环）；**每锈蚀级 EMA 只跟踪基础出力**。声部单位 = **锈蚀级**（不是 BFS 连通块）——同锈蚀级多个连通块出力**直接相加**，这是 `R ≤ 4` 封顶的结构性前提（否则拆簇即可刷 N，退化为 N×(N−1) 膨胀）。**测试中发现的关键修复**：EMA 指数衰减永不到 0 ⇒ 停发的锈蚀级会永远顶「活跃声部」把 s 压死、共振无自愈 → 加 `EMA_EPSILON = 1e-6` 归零截断。新增 `NetworkResonanceTest`（16 例：GM/AM 标度无关、R² 场景、凑弱网不划算、500 tick 收敛有界、停发声部 EMA 归零自愈）
- ✅ **活涂蜡铜块 tooltip：共振显示 + 排版分区 + 示波器面板** —— `LivingWaxedGeneratorData` 新增 4 字段（`resonanceGain`/`resonanceBalance`/`activeVoices`/`voicePower`，`buildTelemetry` 从 `ContainerPowerData` **只读** EMA 基础值填充、绝不回灌）；tooltip 分 4 区（`core` 常显 → `resonance` 共鸣概览 → `settlement` F3+H 公式+短板诊断 → `network` 容器级）+ `§8─── 暗紫小标题 ───` 分隔线。新增 `LivingWaxedCopperTooltipComponent`（纯数据 record，零额外同步）+ `LivingWaxedCopperTooltipRenderer`（172×62 深色示波器面板）。**关键发现**：NeoForge `ClientHooks.gatherTooltipComponents` 把 `ItemStack.getTooltipImage()` 返回的组件插在**索引 1**（物品名之后），无法满足「置底」⇒ 改用 `RenderTooltipEvent.GatherComponents` 向 `event.getTooltipElements()` 追加
- 🐛 **示波器相位波形修复（用户反馈「一直满格看不出波形」）** —— 根因：`phaseDeltas` 取的是 `DomainSnapshot.deltaByOffset().values()`，即「偏移 → |Δ|」的**离散映射**（本质是柱状图数据源、不是随时间变化的连续波形）；网络对称时各偏移 |Δ| 相等 ⇒ 按 `maxDelta` 归一化后每根柱满高。修：左半区改画**多相交变正弦波形**（按相数 n 画 n 条相位错开 `2π·ph/n` 的正弦，横轴覆盖 `SCOPE_CYCLES=2` 个周期），字段 `phaseDeltas` → `phaseCount`。`DomainSnapshot.deltas()` 仍保留（F3+H 文本诊断用）
- 🐛 **信号层 bug 修复：比较器/中继器输出只能传一格** —— 根因：`powerConductiveNeighbor` 的早退掩码 `MASK_REDSTONE` 含 `BIT_DUST|BIT_COPPER`，把比较器输出进 dust/copper 网络的路掐掉，且其后 `if (is(n2, BIT_DUST)) secondQueue.add(n2)` 是**死代码**（到不了）⇒ 输出只写到比较器自身共享边（紧邻一格亮），下游不入队不续传。修：放宽掩码为仅挡元件位（TORCH/BUTTON/LEVER/REPEATER/COMPARATOR/BLOCK）+ 把 dust/copper 邻居加进 `secondQueue` 二次传播。**幂等**（只增不强、无死循环），中继器同类 bug 一并修复
- ✅ **信号层边模型重构 v15：共享边 → 每槽自有出边** —— **Step A**（新增 `inputAt`/`maxInputOfSlot`/`anyInputOfSlot`/`oppositeDir` 抽象层，把所有「读某槽某方向受到的信号」调用点全部迁移；`inputAt(slot,dir) = edgeGrid.get(neighbor, oppositeDir(dir))`；迁移后 16 例全绿证明行为等价）；**Step B**（`EdgeGrid` 由 `hEdges[height*(W+1)] + vEdges[(H+1)*W]` 改 `edges[slotCount*4]`，写方无需改动）。配套**删掉 phase3 比较器 `!=` 兜底**（共享边下会误压下游粉的 -1 衰减信号；每槽出边下该边只归比较器自己所有、无污染，且关闭时出边归零 → 下游粉下一 tick 经 `maxInput` 自然衰减，避免「卡在高电平」回归）。`living-redstone-tech.md` 升 v15
- ✅ **信号层稳态跳过优化（steady-state skip）** —— 物品修订计数 + 外部输入签名（`Arrays.hashCode(faceInput)`）+ 无在途倒计时定时器三者都不变时，复用 edgeGrid 跳过整段 `calculate`（含 BFS）。**关键坑（已修）**：`recordSteadyState` 原先记录方法**开头采样**的 rev，但传播中 phase5/phase3 经 `syncSlotToClients` 会 bump 修订计数 ⇒ `lastRev` 落后一拍、下一拍开头采到的 rev 永远不等、**跳过几乎无法触发**。修：改为重采样**重算后**的 `getContainerRevision`，与下一拍开头采样对齐。安全性不变量：物品变更 bump rev（打破）、邻居/原版红石变化改变 externalSig（打破）、唯一不受两者驱动的逐 tick 演化是定时器倒计时（用 `lastHadActiveTimers` 保险，倒计时在跑就强制重算，绝不冻结时序）
- ✅ **电力层「按网络组件遍历」重构：回归测试 + 文档同步** —— 新增 `NetworkTraversalTest`（3 例：`copyFrom` 深拷贝独立性 / 同连通块两机共享历史且总发电 = 单机×2 / 相邻不同氧化级互不共享）。**测试 seam（生产小改动）**：`ContainerRedstoneData.setEdgeForTest`/`setPrevEdgeForTest` + `ensureTestGrid()`，供电力层单测绕过完整传播直接注入上升沿驱动 `tickContainerData`（`tickContainerData` 只读 `getEdgeValue`/`getPrevEdgeValue`，原无写边 API）。`living-power-tech.md` 升 v4.1，全量文档把「逐发电机 BFS」改为「按网络组件遍历（锚点 BFS + 其余 copyFrom 共享）」
- ✅ **活末影箱 tooltip 高级模式增强（F3+H 路由明细）** —— 每条路由显示**维度 + 坐标**；玩家背包注册的路由优先显示玩家名（服务端按 UUID 解析 `getGameProfile().getName()`），离线回退 UUID。约束留档：活物品体系**不 force-load 区块**，路由只在源/目标区块同时加载时完成，源卸载时条目被丢弃、重载后自愈
- ✅ **涂蜡铜灯发光图标像素修复** —— 4 个 `waxed_*_copper_bulb_lit.png` 黄框完全丢失 + 内部乱码。约定（已确认）：**涂蜡 vs 未涂蜡的唯一区别 = 外圈 60 像素黄框 `(232,160,62,255)`，四种锈蚀级的边框掩码完全相同**；修法 = 内部像素取正确的未涂蜡发光图 + 外圈填黄。破损原图备份于 `_lit_broken_backup/`
- 📄 技术文档：`living-power-tech.md` v4.1（§3.6.1 / §3.7 共振公式 + 铁律 + 场景表；§3.2 周期估计扩写「盲测 + EMA α=0.5 + 长周期为何慢」；§3.3.1 网络涌现多相 + §3.3.1.1 相位错开的真实来源）、`红电系统.md`（§3.2 过期「耦合强度 1.0/0.7/0.5/0.35」改「共振声部」+ v17.1 弃用警示）、`living-redstone-tech.md` v15、`living-ender-chest-tech.md`

---

## 2026-09-01

- 🔍 **活末影箱「玩家绑定频道」需求 —— 架构分析（分析完成，尚未实现）** —— 需求：绑定玩家的活末影箱，堆叠数 = 1 时直连玩家末影箱；堆叠数 **≥ 2** 时变为该玩家专属频道（阈值用户已拍板，非 >2）。核心结论：本质是**频道键从 `int` 升维为复合键** `EnderChannelKey(@Nullable UUID owner, int count)`（`owner == null` = 公共频道），牵连约 8 个文件
- ⭐ **用户关键澄清（重大简化）：专属频道是「命名空间隔离」，不是「权限隔离」** —— 原话「公共频道是一块黑板，专属频道就是另一块黑板，其实并不需要防着其它玩家。玩家 a 有玩家 b 的专属活末影箱，也是可以使用的。」⇒ 绑定信息在 DataComponent 里跟着物品走，**谁持有物品谁就能接入那个频道**，不做持有者鉴权。**该澄清直接砍掉了整条同步层改造**：无需定向投递、无需 `PlayerLoggedIn` 全量补偿、无需处理离线黑洞，`flushDirtyChannels` 保持广播即可
- 🔍 **读码确认的坑（留档待实现时处理）** —— ① `LivingEnderChestAccessor.tryCreate()` 绑定分支把 `filterData` 硬设为 null（旧逻辑「绑定=直连」）⇒ 专属频道是路由模式、过滤要靠 `registry.peek(channel, filterData, ...)`，**不修则黑白名单在专属频道完全失效**；② `EnderChannelRegistry.shouldRemoveRoute()` 只问「槽位上还有没有末影箱」不问「是不是同一个频道键」⇒ 末影箱 count 变化（如 `(A,3)→(A,1)`）后旧频道路由三条检查全通过、**永久残留**（修法：`validateRoutes` 第三参数升级为 `Map<Integer, EnderChannelKey> targetKeysBySlot`）；③ 客户端缓存 `EnderChannelClientCache.clear()`/`removeChannel()` **全项目零调用者**；④ `totalRoutes` 语义会被污染（现在是全服所有频道总和，加专属频道后是噪音且泄漏私有频道规模）⇒ 用户定案**砍掉**，Tooltip 只显示本频道条数
- ✅ **顺带发现（有利）** —— `EnderChannelRegistry` 的 `getChannels`/`getActiveChannelCount`/`getChannelSnapshot`/`getEntries`/`getChannelSize` **全项目无外部调用者** ⇒ 改签名零成本，可直接删掉前两个
- ✅ **已验证的边界** —— target 末影箱**永远**在注册者容器内（`CrossContainerTransfer.pullFromNeighbor` 从 `containerCtx` 取 `targetStack`）⇒ 改法覆盖全部路径；拆堆一半去别的容器也被覆盖（反向索引按源/注册者容器索引）
- 📄 已定案：行为跳变允许（Tooltip 明确标注当前是「直连」还是「专属频道 N」），理由是本 mod 一贯哲学「**堆叠数 = 配置旋钮**」；文档 `living-ender-chest-tech.md` 待升 v12

---

## 2026-08-30

- ✅ **新增：红电发电阶段一~三** —— `domain/power` 包（`PowerMath` / `ChannelState` / `GeneratorState` / `ContainerPowerData`），双因子模型落地：合因子 = n^(1+解锁度)，解锁度 = 调谐效率 × 规律度
- ✅ **新增：`LivingWaxedCopperFunction`（priority=3，晚于红石）** —— 涂蜡全家族 20 件活化，逐方向采样 edgeGrid 事件，跳变即能量事件入账（RE 自然单位，K=1/16 边界换算）
- ✅ **新增：阶段三+四** —— 感应拓扑（线圈分组：铜块全向/雕文 V+H/切制单方向）+ 感应耦合（管径加权守恒、不回传防环、多跳中继）+ 绝缘修复（涂蜡排除出充能导体，杜绝信号泄漏）+ 储能：铜灯 = 唯一储存（发电直存、无容器池），容量 = count×C 线性涌现 + 对外能量：**显式注册+让位**（10 种原版容器 BE，三层判定不劫持已有能源；方块级双向 canReceive=true）+ 铜灯物品 = 通用电池（双向：放电 + 外部充电，跨系统能量等量转换）
- ✅ **新增：表现层** —— Tooltip 仪表盘（检测值写回组件 → 槽位同步 → 客户端渲染，双语 key）
- ✅ **新增：39 项电力层测试**（数学 5 + 状态机 6 + 线圈分组 4 + 储能 16（含仪表 2）+ 电池 6 + 集成 2），全量 146 项测试通过
- 📄 技术文档：`docs/tech/living-power-tech.md`

---

## 2026-08-27

- ✅ **重构：活水车应力状态机提取**（`StressStateMachine` 纯 Java 类，从 `KineticBlockEntityMixin` 中提取）
  - 将原 Mixin 内联的 `livingItem$generatedRPM`、`livingItem$stressCapacity`、`livingItem$refreshedThisTick`、`livingItem$pendingReattach` 字段管理迁移到 `StressStateMachine`
  - 状态机可独立测试，不依赖 Mixin 框架
  - `applyStress(rpm, cap)` 统一入口：同时设置 RPM 和 SU 容量，消除 `setGeneratedRPM` 必须在 `setStressCapacity` 之前调用的隐式依赖
  - `getGeneratedSpeed()` 改为纯 getter（移除副作用——原先在 getter 中清零 RPM 和 capacity）
  - **修改文件**：`StressStateMachine.java`（新建）、`KineticBlockEntityMixin.java`（简化）

- ✅ **重构：应力输出入口合并**（`StressOutputManager` 替代 `ModCreate` + `CreateIntegration`）
  - 将常量定义（`BASE_RPM`、`BASE_SU_CAPACITY`）、Create 检测、白名单过滤、方向兼容性检查合并到一个类
  - 直接 import Create 类型（`StressOutputManager` 在 `compat/create` 包中，Create 已确认加载），简化 `instanceof` 检查
  - 调用方从 `ModCreate.updateStressOutput()` → `StressOutputManager.apply()`
  - **修改文件**：`StressOutputManager.java`（新建）、`ContainerLivingItemHandler.java`（更新调用方）

- ✅ **修复：`deferredSync` 延迟同步导致客户端空状态**
  - **根因**：重构引入 `needsSync` 标记 + `deferredSync` TAIL 注入模式，将 `sendData()` 从 `applyStress()` 延迟到 `tick()` TAIL。但 `tick()` HEAD 先执行清理逻辑，`deferredSync` 发送的是可能被清理后的状态
  - `ServerTickEvent.Post` 模式下，`applyStress()` 在 `tick()` 之后执行，`tick()` HEAD 发现 `refreshedThisTick=false` 触发清理，`deferredSync` 发送空状态。接着 `applyStress()` 设置 `needsSync=true`，但 `tick()` 已结束，`needsSync` 永远得不到处理
  - **修复**：在 `applyStress()` 和 `tick()` 中直接调用 `self.sendData()`，移除 `needsSync` 字段、`deferredSync` 方法和 TAIL 注入
  - **修改文件**：`StressStateMachine.java`（移除 `needsSync`、`deferredSync`）、`KineticBlockEntityMixin.java`（移除 TAIL 注入）

- ✅ **修复：`applyStress` 中多余的 `isRemoved()` 检查**
  - 移除 `applyStress()` 中 `self.isRemoved()` 检查，与工作版本对齐，避免生命周期过渡期状态被跳过
  - 移除 `tick()` 中 `self.isRemoved()` 守卫，与工作版本对齐
  - **修改文件**：`StressStateMachine.java`

- ✅ **文档：活水车技术文档更新至 v10**
  - 更新类职责表（新增 `StressStateMachine`、`StressOutputManager`）
  - 更新软依赖架构图（反映新统一入口和即时 `sendData()`）
  - 新增 `StressStateMachine` 和精简后的 `KineticBlockEntityMixin` 代码文档
  - 新增 9.21 踩坑记录：`deferredSync` 延迟同步导致客户端空状态
  - 更新 Tick 时序图（反映 `ServerTickEvent.Pre` 和 `StressOutputManager`）
  - 更新验证清单
  - **修改文件**：`docs/tech/living-water-wheel-tech.md`

---

## 2026-08-25

- ✅ **提升：红石传播时间分辨率从 2 tick 改为 1 game tick**，取消跳帧。容器内最快振荡周期从 4 tick 降至 2 tick，为红电系统的高频档位提供基础。中继器/按钮延迟统一以 game tick 计数（中继器档位 N = 2N tick，保持原版红石刻语义）
- ✅ **优化：传播热路径改用槽位类型位图（`slotMask`）**，替换 18 处 `Set<Integer>.contains`，消除装箱与每 tick 的临时 `HashSet`。实测单次传播开销降 40~76%（满载 54 格 9.4μs → 4.9μs），1 tick 传播总成本与原 2 tick 持平

---

## 2026-08-22

- ✅ **新增：活TNT红石信号点燃**（活TNT收到红石信号自动点燃，无需打火石）
  - **实现**：`LivingTntFunction` 新增 `HasContainerData` 接口实现，`tick()` 中通过 `ContainerRedstoneData.getSlotSignal()` 检测槽位红石信号，信号 > 0 时自动点燃 TNT
  - `getPriority()` 返回 1，确保红石数据在 TNT tick 之前计算完毕
  - `tickContainerData()` 调用 `redstoneData.calculate()` 触发红石信号传播
  - `getSlotSignal(slot, size, width)` 同时检查 `edgeGrid.maxOfSlot()` 和边界 `faceInput`，确保跨容器信号也能点燃 TNT
  - **修改文件**：`LivingTntFunction.java`

- ✅ **新增：ContainerRedstoneData.getSlotSignal() 方法**
  - 查询槽位有效信号，合并内部边信号和外部 `faceInput` 信号
  - 边界槽位额外检查对应面的 `faceInput`，支持跨容器及外部世界信号检测
  - 供 `LivingTntFunction`、漏斗等消费方使用
  - **修改文件**：`ContainerRedstoneData.java`

- ✅ **优化：ContainerRedstoneData 无参构造**（移除 `size` 参数）
  - `edgeGrid` 在首次 `calculate()` 时按需创建，无需构造时预先分配
  - 移除 `slotCount` 字段（不再需要）
  - 所有创建位置同步更新为无参构造
  - **修改文件**：`ContainerRedstoneData.java`、`ContainerLivingItemHandler.java`、`TickContext.java`

- ✅ **修复：容器输出信号不消失**（容器内信号源移除后，容器外仍残留旧信号）
  - **根因**：`reset()` 中 `edgeGrid` 与 `prevEdgeGrid` 交换后，新的 `edgeGrid` 继承旧数据。`edgeGrid.zero()` 被移除后，旧边信号泄漏到当前帧
  - **修复**：`reset()` 中重新加入 `edgeGrid.zero()` 调用，确保每次传播重算前边网格从零开始
  - **修改文件**：`ContainerRedstoneData.java`

- ✅ **修复：processLevelContainers 并发修改崩溃**（TNT 爆炸时修改方块实体导致迭代崩溃）
  - **根因**：`processLevelContainers` 遍历 `chunk.getBlockEntities().values()` 时，TNT 爆炸的 `setBlock()` 修改了同一集合，触发 `ConcurrentModificationException`
  - **修复**：遍历前创建快照 `new ArrayList<>(chunk.getBlockEntities().values())`，避免迭代期间集合被修改
  - **修改文件**：`LivingItem.java`

- ✅ **文档：跨容器红石信号传输数据流详解**
  - 新增完整数据流图：输出路径（容器→世界）和输入路径（世界→容器）的逐步说明
  - 新增 `CrossContainerTransfer.worldToGrid()` 方向映射说明
  - 新增 `BlockStateBaseMixin` 拦截机制说明
  - 新增 `injectExternalInputs` 直读邻居容器 `getBoundarySignal()` 的时序说明
  - 文档版本更新至 v14
  - **修改文件**：`docs/tech/living-redstone-tech.md`

- ✅ **文档：活TNT红石点燃功能文档更新**
  - 新增 3.3 红石信号点火章节
  - 更新数据流总览图，加入红石信号点燃路径
  - 更新关键类表，新增 `ContainerRedstoneData` 引用
  - 文档版本更新至 v5
  - **修改文件**：`docs/tech/living-tnt-tech.md`
- ✅ **修复：红石传播节拍改为对齐全局游戏时钟**，消除因容器加载时机不同导致的跨容器信号错位半拍问题
- ✅ **新增：单元测试基建** — MDG `unitTest` 配置，测试可在 FML 环境引用 Minecraft 类
- ✅ **新增：64 项单元测试**（红石传播 21 + 容器兼容性 14 + 地图坐标 29）
- ✅ **修复：客户端 Mixin 从双端 `mixins` 移至 `client` 数组**（专用服务器启动崩溃）
- ✅ **修复：`EdgeGrid.get/set` 缺少边界检查**，容器尺寸变化时会越界崩溃
- ✅ **修复：容器级数据缓存键补齐维度**，消除跨维度同坐标容器串数据
- ✅ **修复：`grouped.isEmpty()` 分支门禁失效**（`getSize()` 恒为 0），残留边界红石信号现可正确归零
- ✅ **优化：新增位置→缓存键反向索引**，mixin 热路径（`getSignal` / `getConnectingSide`）从正则全表扫描降为 O(1)
- ✅ **修复：`APPLICABLE_CACHE` 改用 `ConcurrentHashMap`**，消除单人游戏双线程并发写风险
- ✅ **新增：`ServerStoppedEvent` 统一清理静态缓存**，避免跨存档状态残留
- ✅ **优化：`PerfMetrics` 全面改用纳秒累计**，修复亚毫秒耗时被整数除法归零的问题
- ✅ **清理：删除 `TickContext` 未使用的泛型扩展点**

---

## 2026-08-19

- ✅ **优化：传送区块加载从 ChunkStatus.FULL 降为 LIGHT**（远距离传送到未探索区域时 MSPT 峰值 16200ms → 大幅降低）
  - **根因**：`ensureChunkLoaded` 使用 `level.getChunk(chunkX, chunkZ)`（默认 `ChunkStatus.FULL`），要求区块经历完整 11 个生成阶段。`FULL` 的 `ChunkPyramid` 依赖链中 `STRUCTURE_STARTS` 半径 8，导致 `ChunkGenerationTask` 需覆盖 17×17 = 289 个区块。主线程通过 `managedBlock()` → `LockSupport.parkNanos()` 阻塞等待（spark 报告 205.84%），同时 `FULL` 阶段的 `runPostLoad()` 在新生成区块上触发大量 mod 事件（`twilightforest` 22.82%、`sable` 66.17%）。
  - **修复**：`findSafeY` 仅需 `MOTION_BLOCKING` 高度图，该数据在 `LIGHT` 阶段即已就绪。将 `ensureChunkLoaded` 改为 `level.getChunk(chunkX, chunkZ, ChunkStatus.LIGHT, true)`，跳过 `SPAWN`（出生点生成）和 `FULL`（ProtoChunk→LevelChunk 转换 + `runPostLoad()`）两个阶段。`ChunkLoadResult` 和 `findSafeY` 参数类型从 `LevelChunk` 改为 `ChunkAccess`（`LIGHT` 返回 `ImposterProtoChunk`）。
  - **修改文件**：`TeleportHelper.java`

- ✅ **优化：零区块加载传送——ChunkGenerator.getBaseHeight 替代 ChunkStatus.LIGHT**
  - **根因**：v47 的 `ChunkStatus.LIGHT` 仍依赖 `STRUCTURE_STARTS` 半径 8（`LIGHT` → `FEATURES` → ... → `STRUCTURE_STARTS`），仍需 289 个区块的生成任务。spark 报告 `parkNanos` 3.89% self = 3760ms 纯阻塞等待。`ChunkStatus` 体系中任何能提供高度图的状态都必须经过 `LIGHT`，无法绕过 289 区块依赖。
  - **修复**：完全绕过区块加载，使用 `ChunkGenerator.getBaseHeight(x, z, MOTION_BLOCKING, level, randomState)` 从噪声密度函数直接计算地表高度。`NoiseBasedChunkGenerator.getBaseHeight()` 内部调用 `iterateNoiseColumn()` 遍历噪声柱，在第一个不透明方块处停止并返回 Y+1，耗时 < 1ms。移除 `ensureChunkLoaded`、`findSafeY`、`ChunkLoadResult` 方法和相关 import。`teleportToBanner` 也移除 `ensureChunkLoaded`（`changeDimension`/`teleportTo` 内部通过 `POST_TELEPORT` ticket 异步加载）。子位面传送保留 `ensureChunkForSubLevel`。
  - **修改文件**：`TeleportHelper.java`

- ✅ **修复：未打开战利品容器触发战利品表生成导致 processLevelContainers 耗时过高**
  - **根因**：`processLevelContainers` 遍历所有世界容器时，`RandomizableContainerBlockEntity.getItem()` 内部调用 `unpackLootTable()`，触发战利品生成。战利品表（如 `minecraft:chests/shipwreck_map`）中的 `ExplorationMapFunction` 搜索结构，在未探索区域耗时极高。
  - **修复**：
    - `LivingItem.processLevelContainers()`：遍历时跳过 `RandomizableContainer` 且 `lootTable != null`（未打开）的容器
    - `ContainerLivingItemHandler.processContainerAt()`：同上，添加战利品容器过滤逻辑
  - **修改文件**：`LivingItem.java`、`ContainerLivingItemHandler.java`

- ✅ **修复：活中继器延迟计数器不减少**（活红石中继器的延迟计数器每 tick 被重置，导致永远无法输出信号）
  - **根因**：`SimpleContainerContext` 实例每 tick 重建，其内部的 `redstoneData` 字段始终为 null，`getOrCreateRedstoneData()` 每 tick 创建新实例。这导致 `edgeGrid` / `prevEdgeGrid`（边信号状态）和 `tickCounter`（延迟计数器）每 tick 丢失，中继器功能无法正常工作。
  - **修复**：
    - `ContainerLivingItemHandler`：新增 `REDSTONE_DATA_CACHE` 静态缓存（`LinkedHashMap`），通过 `containerKey` 关联，确保 `edgeGrid` / `prevEdgeGrid` / `tickCounter` 跨 tick 持久化
    - `SimpleContainerContext.getOrCreateRedstoneData()`：改为从 `ContainerLivingItemHandler.getRedstoneData()` 获取数据，而非新建实例
    - `ContainerRedstoneData`：新增 `lastTickTime` 字段，在 `calculate()` 中更新，支持过期清理
    - `ContainerChunkCache.onBlockBreak`：新增 `removeRedstoneDataByPos()` 调用，事件驱动清理
    - `ContainerLivingItemHandler.cleanupStaleRedstoneData()`：每 120 秒清理一次 120 秒内未访问的红石数据
  - **清理机制**：事件驱动清理（`removeRedstoneDataByPos`）→ 定期过期清理（`cleanupStaleRedstoneData`）→ 容器销毁清理（`removeRedstoneData`）

- ✅ **修复：区块重新加载后活物品不工作（残留问题）**（2026-08-17 的修复未完全解决，离开区块一定时间后返回容器中活物品仍停止 tick）
  - **残留根因**：`ChunkEvent.Load` 在 `MinecraftServer.waitUntilNextTick()` 的 `runAllTasks()` 中触发，而 `processLevelContainers` 在 `ServerTickEvent.Post` 中执行（早于 `runAllTasks`）。`onChunkUnload` 立即从缓存中移除区块 → `ChunkEvent.Load` 来不及在同一 tick 加回缓存 → `processLevelContainers` 找不到该区块。
  - **修复**：
    - `ContainerChunkCache.onChunkUnload`：不再从缓存中移除区块，改为由 `cleanupStaleEntries` 统一清理
    - `ContainerChunkCache.cleanupStaleEntries`：首次调用时仅初始化 `lastCleanup` 时间戳并返回，避免立即清理刚卸载、即将重新加载的区块
  - **修复后流程**：卸载 → 缓存保留 → 重新加载 → 下一 tick 正常处理（最多延迟 1 tick）

- ✅ **修复：活中继器输入端有信号但显示无信号**（红石火把等信号源正常输出，但中继器始终显示"unpowered"，无法检测到输入信号）
  - **根因**：`SimpleContainerContext` 实例每 tick 重新创建，`setTickContext()` 中 `resetProcessedFlag()` 的调用被 `redstoneData != null` 条件守卫。由于新实例的 `redstoneData` 字段始终为 null，`resetProcessedFlag()` 永远不会被调用。`ContainerRedstoneData.processedThisTick` 在第一 tick 后被设为 true 后永不重置，导致 `calculate()` 从第二 tick 起直接返回，完全跳过红石计算。
  - **修复**：
    - `SimpleContainerContext.setTickContext()`：改为主动调用 `getOrCreateRedstoneData()` 从静态缓存获取已持久化的 `ContainerRedstoneData` 实例，再调用 `resetProcessedFlag()`，确保每一 tick 开始时 `processedThisTick` 被正确重置为 false
  - **相关知识**：`calculate()` 是一次性处理所有红石类型（火把、中继器、比较器、红石粉、灯、按钮、拉杆）的综合方法，`processedThisTick` 标志的作用是防止多个 `HasContainerData` 函数在同一 tick 重复调用 `calculate()`，而非区分不同红石类型的处理顺序。因此调整各功能 `getPriority()` 的方案并不对症——真正的问题在于该标志从未被重置。

- ✅ **修复：活中继器不能延迟熄灭**（输入信号消失后应延迟对应时间再停止输出，而非立即熄灭）
  - **根因**：`phase0CountdownDelays` 中 `!hasInput` 分支立即将 `powered` 设为 false 且 `delayTimer` 归零，`phase3RecheckInputs` 中 `!hasInput && data.powered()` 分支同理。中继器只有"上升沿延迟"（ON_DELAY），没有"下降沿延迟"（OFF_DELAY）。
  - **修复**：利用 `delayTimer` 的正负号区分两种延迟方向，无需修改 `LivingRepeaterData` 记录结构：
    - `delayTimer > 0`：ON_DELAY（等待开启，不输出信号）
    - `delayTimer = 0`：ON（正常输出）
    - `delayTimer < 0`：OFF_DELAY（等待关闭，**继续输出信号**）
  - **具体变更**：
    - `phase0CountdownDelays`：`delayTimer > 0` 时递减（ON_DELAY 倒计时），`delayTimer < 0` 时递增趋近于 0（OFF_DELAY 倒计时）。ON_DELAY 期间若输入消失则取消（`powered=false`）；OFF_DELAY 归零时转为 OFF（`powered=false`）
    - `phase1CollectSources` 中继器输出条件：`delayTimer() != 0` → `delayTimer() > 0`，确保 OFF_DELAY（`delayTimer < 0`）期间继续输出信号
    - `phase3RecheckInputs`：新增三种过渡——`hasInput && powered && delayTimer < 0`（OFF_DELAY 期间输入恢复，取消延迟回到 ON）；`!hasInput && powered && delayTimer == 0`（ON 状态下输入消失，启动 OFF_DELAY，`delayTimer = -delay`）
    - `LivingRepeaterFunction.addToTooltip`：OFF_DELAY 期间用 `Math.abs()` 显示正数

- ✅ **新增：活红石块**（`Items.REDSTONE_BLOCK`）
  - 常亮信号源，向四个方向输出信号强度 15（受堆叠数衰减），无状态数据
  - 新增 `LivingRedstoneBlockFunction`：实现 `LivingItemFunction` + `HasContainerData`，`canApply` 匹配 `Items.REDSTONE_BLOCK`
  - `ContainerRedstoneData.phase1CollectSources`：新增 `redstoneBlockSlots` 参数，红石块始终向 4 方向写边信号
  - 无 DataComponent：红石块无状态，无需持久化数据

---

## 2026-08-17

- ✅ **修复：区块重新加载后活物品不工作**（离开区块一定时间后返回，容器中活物品停止 tick；重新进入游戏恢复正常）
  - **根因**：`processLevelContainers` 使用 `getChunkNow()` 获取区块，当 `FULL` 状态的 `CompletableFuture` 尚未完成时返回 null，导致区块被 `toRemove` 从缓存中移除。区块从磁盘完整重新加载时存在 `ChunkEvent.Load`（触发缓存加入）与 `ServerTickEvent.Post`（触发缓存遍历）之间的时序窗口，加载后同一 tick 内被意外清除。
  - **为什么短时间内回来正常**：短时间内区块未完全卸载，`getChunkNow` 仍返回有效值，不会被移除。
  - **修复**：
    - `LivingItem.processLevelContainers`：`getChunkNow` 返回 null 时不再立即移除，只跳过本次处理，保留到下次 tick
    - `ContainerChunkCache.cleanupStaleEntries`：新增定期清理方法，每 6000 ticks（约 5 分钟）清理一次真正已卸载的区块，作为兜底机制防止内存泄漏
    - `ContainerChunkCache.clear()`：同步清理 `lastCleanupTick` 时间戳
  - **清理机制三层保障**：`onChunkUnload`（即时移除）→ `!hasContainer` 检查（自清洁）→ `cleanupStaleEntries`（兜底清理）
  - **注意**：此修复在 2026-08-19 发现残留问题——`onChunkUnload` 即时移除与 `ChunkEvent.Load` 晚触发之间存在时序窗口，见 2026-08-19 记录。
- ✅ **重构：接口化设计** — `HasDirection` 接口统一 WASD 朝向配置，`HasContainerData` 接口统一容器级数据计算
- ✅ **重构：`TickContext` 每 tick 新建**（对象小、生命周期短，JVM 年轻代可高效回收）
- ✅ **优化：新增活物品从修改 6 个文件减少到 2 个文件**
- ✅ **更新：全部文档同步至 v8.1 架构**

---

## 2026-08-16

- ✅ **优化：Mixin 兼容性重构**（移除高风险 Mixin，改用 NeoForge API 或低风险替代方案）
  - 删除 `MapItemMixin`（`@Inject` 到 `EmptyMapItem.use`）→ 改用 `PlayerInteractEvent.RightClickItem` 事件（`LivingMapEventHandler.handleLivingMapCreation`）
  - 删除 `MapItemUpdateMixin`（2个 `@Redirect` 替换 `Level.getChunk`）→ 依赖原版 `MapItem.update`（玩家附近区块通常已加载；原 `@Redirect` 与暮色森林魔法地图冲突导致服务端卡死）
  - 删除 `BlockEntityMixin`（接口注入 + 字段注入 `livingItem$stressData`）→ 改用 NeoForge `AttachmentType`（`LivingItemManager.CONTAINER_STRESS_DATA`，框架级支持、自动序列化、类型安全）
  - 删除 `StressDataProvider` 接口（不再需要，`be.getData()`/`be.setData()` 替代）
  - `RecipeBookComponentMixin` 移除 3个 `@Redirect` → 新增 `RecipeBookPageMixin`（3个 `@Inject HEAD cancellable`，低风险，允许多模组链式共存）
  - `ContainerLivingItemHandler` 应力写入从 `StressDataProvider.livingItem$setStressData()` → `be.setData(CONTAINER_STRESS_DATA, stressData)`
  - 更新 `living_item.mixins.json`（移除 `MapItemMixin`、`MapItemUpdateMixin`、`BlockEntityMixin`）
  - 更新 `living_item.client.mixins.json`（新增 `RecipeBookPageMixin`）
- ✅ **重构：包结构重组** — `data/` 删除，`function/` 精简，所有活物品内聚到 `domain/`
- ✅ **重构：传输管道统一** — `TransferPipeline` 统一传输入口，解耦 `CrossContainerTransfer` 与 `LivingHopperFunction`
- ✅ **重构：活末影箱路由解耦** — `EnderRouteManager` 集中管理路由，`LivingEnderChestAccessor` 精简
- ✅ **重构：活熔炉同步生命周期统一** — 改用 `SlotAccessor`，支持活箱子作为输入/输出
- ✅ **新增：`HopperFilterBuilder` 过滤链构建**（从 `ContainerSnapshot` 提取）
- ✅ **新增：脏槽位批量同步机制**（`TickContext.dirtySlots` + `SimpleContainerContext.flushDirtySlots()`）
- ✅ **更新：全部技术文档同步至 v8 架构**

---

## 2026-08-09

- ✅ **新增：活地图传送系统**（三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇兼容）
- ✅ **新增：GUI 扩展地图渲染 + 十字光标 + 展示框十字光标 + 活地图图标**
- ✅ **新增：元数据同步包 + 客户端缓存**
- ✅ **修复：跨维度提示重复**（`MapTeleportExecutor.execute()` 统一发送）
- ✅ **修复：副手活末影珍珠未统计/消耗**
- ✅ **优化：渲染性能**（`hoveredGroup` 缓存，O(N²)→O(N)）

---

## 2026-07-30

- ✅ **重构：包结构按领域聚合**（消灭 `core/` 万能垃圾桶，消除 `capability/` 专属小包，`create/` 提升为 `compat/create/`）
  - `api/` — `LivingItemFunction` + `LivingItemManager` 从 `living/` 根提升
  - `domain/ender/` — 末影箱+活箱子领域聚合（`EnderChannelRegistry`、`EnderChannelEntry`、`LivingChestAccessor`、`LivingEnderChestAccessor` 等 8 个文件）
  - `domain/water/` — 活水领域聚合（`ContainerFluidData`、`ContainerStressData` 从 `container/` 移出）
  - `domain/map/` — 活地图传送领域聚合（`MapTeleportExecutor`、`TeleportHelper`、`MapCoordHelper`、`LivingMapEventHandler`、`ItemFrameMapTeleportHandler`、`LivingMapClientCache`）
  - `compat/create/` — Create 兼容层从 `create/` 提升（含 `CreateMixinPlugin`，已更新 mixin JSON 路径）
  - `compat/sable/` — Sable 飞艇兼容层（`SableCompat` + `ModSable` + `SableIntegration`，三层软依赖）
  - `transfer/` — 传输基础设施（`SlotAccessor` 体系 + `SlotResolver` + `ContainerCompatibilityConfig`，从 `core/accessor/` + `core/config/` 合并）
  - `interaction/` — GUI交互从 `core/interaction/` 提升
  - `model/` — 配置模型从 `core/model/` 提升
  - `components/` — 无状态工具组件从 `core/components/` 提升（`LivingChestTooltipComponent` 归入 `domain/ender/`）
  - `container/` — 纯容器抽象（移除业务数据 `ContainerFluidData`/`ContainerStressData`）
  - `client/render/` — 渲染类从 `client/` 根 + `client/tooltip/` 合并
  - `client/input/` — 输入处理从 `client/` 根移出
- ✅ **优化：活水桶和活水车代码审查**
  - 修复 `ContainerLivingItemHandler` 中 `stressData.calculate()` 缺少第三个参数的编译错误
  - 移除 `LivingWaterBucketFunction.tick()` 中冗余的 `syncSlotToClients` 调用（`postTickSync` 统一同步）
  - `LivingWaterBucketFunction.postTickSync()` 添加 flow 变化检测，避免无变化时冗余网络同步
  - `ContainerLivingItemHandler` 合并两次 `grouped.entrySet()` 遍历为一次
- ✅ **新增：活水车系统**（`LivingWaterWheelFunction` + `LivingWaterWheelData` + `WaterWheelData` + `ContainerStressData`）
- ✅ **新增：力矩计算模型**（二维叉积：位置向量 × 水流方向向量，CW/CCW 方向判定，水流强度权重）
- ✅ **新增：Create 软依赖集成**（`CreateCompat` 检测 + `CreateMixinPlugin` 条件加载 + `ModCreate` 安全调用 + `try-catch` 双重防护）
- ✅ **新增：应力输出逻辑**（`CreateIntegration`：白名单过滤 + 方向兼容性检查 + RPM/SU 设置）
- ✅ **新增：KineticBlockEntity Mixin**（`KineticBlockEntityMixin`：应力输出 + 自过期机制 + 白名单过滤 + `attachKinetics()` 重连）
- ✅ **新增：容器底部/玩家脚底应力传递**（`ContainerLivingItemHandler` 扩展，背包中活水车从玩家脚底输出）
- ✅ **新增：物品栏 3D 旋转渲染**（`ItemRendererWaterWheelMixin`：BakedModel + PoseStack 旋转变换，仅在有应力时旋转）
- ✅ **新增：漫反射光照修正**（`RenderSystem.setShaderLights()` + `combinedLight` 修改，解决物品贴图过暗）
- ✅ **新增：BlockEntity 应力存储**（NeoForge `AttachmentType`：`CONTAINER_STRESS_DATA`，替代原 `BlockEntityMixin` + `StressDataProvider` 接口注入，框架级支持、自动序列化、类型安全）
- ✅ **修复：旋转方向与物品栏不一致**（RPM 公式添加负号 `-sign(netStress)`）
- ✅ **修复：复杂组件崩溃**（白名单策略，仅允许 `SimpleKineticBlockEntity` 和 `BracketedKineticBlockEntity`）
- ✅ **修复：方向冲突导致方块销毁**（`isDirectionCompatible()` 软侵入检查，方向相反不注入）
- ✅ **修复：取消应力后齿轮不恢复**（`attachKinetics()` 重连邻居网络，自动被原发电机接管）
- ✅ **修复：应力源消失后残留**（`refreshedThisTick` 布尔标记替代时间戳，1 tick 响应 + 客户端同步）
- ✅ **修复：`validateKinetics()` 60 tick 后应力消失**（移除 `source` 字段依赖，注入 BE 作为旋转源）

## 2026-07-29

- ✅ **新增：贪心提取策略**（`preferredItemType` 偏好提取，输出槽有铁锭时优先继续提取铁锭可堆叠，而非轮询到金锭导致传输停止，回退正常轮询）
- ✅ **重构：统一路由验证**（`validateRoutes()` 替代 5 个独立清理方法，一次遍历完成注册者/目标/源物品三项检查，新增 `registrarKeyIndex` 反向索引）
- ✅ **修复：活末影箱被拿走后路由不清理**（`registerRoute()` 接收 `targetSlot` 参数，路由条目正确记录活末影箱所在槽位）
- ✅ **修复：活漏斗被拿起到鼠标后路由不清理**（`LivingEnderChestFunction.tick()` 现在也扫描活漏斗槽位，空集触发清理）
- ✅ **新增：TickContext.functionSlots 缓存**（`processContext()` 分组时一次性填充各功能活跃槽位集合，功能类 O(1) 读取，无需遍历容器）

## 2026-07-28

- ✅ **优化：双重扫描合并**（`processContext` 内部扫描后 `grouped` 为空时提前 return，`processEnderChest` 和 `processContainerAt` 不再做预扫描，每容器每 tick 省一次全量槽位扫描）
- ✅ **优化：Snapshot 懒加载**（`TickContext.getSnapshot()` 按需构建，闲置容器和只有活熔炉的容器零开销，`reset()` 不再预构建 snapshot）
- ✅ **优化：容器缓存自清洁**（`ContainerChunkCache.removeChunk()` + `processLevelContainers` 自动清理已卸载区块和无容器区块，零额外扫描开销，缓存自动收敛）
- ✅ **优化：直连模式 InvWrapper 缓存**（`LivingEnderChestAccessor` 构造时缓存 `cachedInvWrapper`，所有操作复用同一实例，避免每 tick 重复创建 `InvWrapper`）
- ✅ **优化：直连模式轮询提取**（`nextExtractSlot` 指针记录上次提取槽位，末影箱所有槽位公平轮询，解决特定槽位物品无法被提取的问题）
- ✅ **重构：EnderChannelRegistry Deque 替代 List+nextIndex**（`ArrayDeque` + `poll()`/`reoffer()` 天然实现轮询调度，消除手动指针管理，`peekEntry()`/`advancePointer()`/`peek()` 三个方法合并为 `poll()` + 条件 `reoffer()`）
- ✅ **新增：RouteKey 内部类 + 反向索引**（`routeKeys` 集合 O(1) 路由去重检查，`entryToChannel` 反向索引 O(1) 查找条目所属频道）
- ✅ **重构：removeIf() 收集后删除模式**（先收集符合条件的条目再批量删除，避免在遍历中混用 `poll()`/`offer()` 导致的 `ConcurrentModificationException`）

## 2026-07-27

- ✅ 重构：DataComponent 直接管理架构迁移（功能类直接管理类型化 DataComponent，替代 `ComponentState` + `LivingFunctionData` 中转层）
- ✅ 重构：组件无状态化改造（`ProgressComponent`、`FuelConsumeComponent` 等从有状态组件变为无状态工具类，接收类型化数据返回新数据）
- ✅ 新增：`data/` 包 — 不可变 Record 数据模型（`LivingTntData`、`LivingWaterBucketData`、`LivingFurnaceData`、`LivingHopperData`、`LivingEnderChestData` 及其子数据）
- ✅ 新增：`DirectionSlotsData` / `DirectionTransferData` 不可变方向数据模型，替代 `DirectionModeComponent` + `ComponentState`
- ✅ 重构：`LivingItemFunction.addToTooltip()` 移除 `CompoundTag` 参数，功能类直接从 `ItemStack` 读取 DataComponent
- ✅ 重构：`LivingTntFunction`、`LivingWaterBucketFunction`、`LivingFurnaceFunction`、`LivingHopperFunction`、`LivingEnderChestFunction` 迁移到新架构
- ✅ 重构：客户端渲染层迁移
- ✅ 重构：`LivingItemTooltip` 简化
- ✅ 重构：`ItemFilterComponent.inheritFilter()` 从 `getFunctionData` 迁移到 `LivingItemManager.getHopperData()`
- ✅ 新增：`SlotMapping.fromDirections(Pos2D, Pos2D)` 工厂方法
- ✅ 修复：`LivingWaterBucketData.DEFAULT` → `LivingWaterBucketData.EMPTY`
- ✅ 修复：`ExplosionData.ignite()` 无参重载方法（默认 80 刻引信）
- ✅ 兼容：`ProgressComponent` 实现 `ILivingComponent` 接口 + `ComponentState` 适配器方法，保持旧编排器编译兼容
- ✅ **优化：TickContext 对象池**（`TickContextPool` 复用 tick 实例——已废弃，大负载场景下池化收益为负，改为每次 tick 创建新实例，由 JVM 年轻代 GC 回收）
- ✅ **优化：SlotAccessor 注册式工厂**（`SlotAccessorFactory.registerProvider()` 开放扩展，第三方模组可注册自定义 Accessor）
- ✅ **优化：LivingItemFunction 接口职责拆分**（5 个逻辑模块：匹配/标识/Tick/Tooltip/组件过滤，可选方法默认空实现）
- ✅ **优化：活箱子精确字节计算**（`LivingChestFunction.calculateExactByteUsage()` 替代粗糙估算，NBT 序列化获取真实大小，Tooltip 显示百分比）
- ✅ **新增：性能监控指标系统**（`PerfMetrics` 收集 Tick 耗时/活物品数量/功能调用/传输成功率，每 60 秒自动打印报告）

## 2026-07-24

- ✅ 重构：SlotAccessor 模拟优先传输模式（`simulateExtract` → `simulateInsert` → `extract` → `insert` → `rollback` 安全兜底 + WARN 日志）
- ✅ 新增：`SlotAccessor` 接口新增 `simulateExtract()` 和 `simulateInsert()` 模拟方法
- ✅ 优化：`SlotAccessor.transfer()` 改为"先模拟确认再真实操作"
- ✅ 优化：`rollback` 修复部分插入场景
- ✅ 优化：`NeighborSlotAccessor.rollback()` 优先放回原槽位
- ✅ 优化：配方缓存（`ItemTransformComponent.resolveRecipe()` 公共方法）
- ✅ 优化：容器位置缓存（拉取模型）替代活跃列表（推送模型）
- ✅ 新增：`BlockEvent` 通用监听兜底
- ✅ 优化：GC 压力降低（`LivingItem` 复用 `IdentityHashMap` 和 `HashSet` 实例字段）
- ✅ 优化：惰性 Tick 去重集合复用

## 2026-07-23

- ✅ 新增：活末影箱系统（`LivingEnderChestFunction` + `LivingEnderChestAccessor` + `EnderChannelRegistry` + `EnderChannelEntry`）
- ✅ 新增：活末影箱双模式（路由模式 + 直连模式）
- ✅ 新增：玩家绑定机制
- ✅ 新增：直连模式预加载
- ✅ 新增：`EnderChannelComponent` 频道组件
- ✅ 新增：`ItemFilterComponent` 黑白名单过滤组件
- ✅ 新增：`FilteredSlotAccessor` 过滤装饰器
- ✅ 新增：`NeighborSlotAccessor` 邻居容器访问器
- ✅ 新增：`SlotAccessor.transfer()` 统一传输方法
- ✅ 新增：`SlotAccessorFactory.createForNeighbor()` 工厂方法
- ✅ 新增：末影箱容器处理
- ✅ 新增：反向索引路由清理
- ✅ 新增：活末影箱 Tooltip
- ✅ 重构：`CrossContainerTransfer` 使用 `NeighborSlotAccessor` + `SlotAccessor.transfer()` 统一传输
- ✅ 重构：活末影箱组件化改造
- ✅ 修复：末影箱中活物品不工作
- ✅ 修复：活漏斗往绑定玩家的活末影箱输入物品不消耗
- ✅ 修复：末影箱中未绑定玩家的活末影箱无法建立路由
- ✅ 修复：水晶箱子（12×9）中活漏斗黑白名单无法正确生效
- ✅ 修复：大箱子中活物品一 tick 内被处理两次
- ✅ 修复：跨容器注册路由不生效
- ✅ 修复：已绑定玩家的活末影箱仍显示频道信息
- ✅ 修复：活末影箱移走后路由未清理
- ✅ 修复：源物品移走后路由未清理
- ✅ 修复：`EnderChannelRegistry.getChannels()` 遍历时 `ConcurrentModificationException`

## 2026-07-22

- ✅ 重构：全面使用 IItemHandler 统一容器抽象
- ✅ 新增：`ContainerContext.getSlotLimit(slot)` 接口方法
- ✅ 优化：`CrossContainerTransfer.tryInsert()` 使用 `ItemHandlerHelper.insertItemStacked()`
- ✅ 优化：`CrossContainerTransfer.hasAnySpace()` 使用 `getSlotLimit(i)`
- ✅ 优化：`PlainSlotAccessor.insert()` / `isFull()` 使用 `getSlotLimit(slot)`
- ✅ 优化：`ItemTransformComponent.calculateOutputSpace()` 使用 `getSlotLimit(outputSlot)`
- ✅ 优化：`ContainerCompatibilityConfig` 新增 `findOrGenerateRule()` 自动推断标准布局
- ✅ 优化：`ContainerLivingItemHandler.buildChestContext()` 统一使用 IItemHandler
- ✅ 优化：`ContainerLivingItemHandler.processBlockEntities()` 使用 `IdentityHashMap<IItemHandler>` 去重
- ✅ 优化：`ContainerChunkCache` 通过 `Capabilities.ItemHandler.BLOCK` 检测容器
- ✅ 修复：抽屉模组容器满时活漏斗仍传输导致物品消失
- ✅ 修复：精妙背包堆叠上限升级后活漏斗误判槽位已满
- ✅ 重构：活漏斗传输引擎引入 SlotAccessor 统一架构
- ✅ 新增：`SlotAccessor` 接口 + `PlainSlotAccessor` + `LivingChestAccessor` + `SlotAccessorFactory`
- ✅ 删除：4 个旧传输方法，~400 行重复代码
- ✅ 修复：混搭黑白名单链传递失效
- ✅ 修复：过滤器拦截时冷却未设置导致无限循环

## 2026-07-20

- ✅ 新增：方块放置自动填充
- ✅ 新增：三层防护体系
- ✅ 新增：铁砧重命名堆叠修复
- ✅ 统一：UUID 操作方向

## 2026-07-17

- ✅ 新增：活箱子系统
- ✅ 新增：UUID 映射管理
- ✅ 新增：堆叠倍增模型
- ✅ 新增：LRU 缓存策略
- ✅ 新增：快速短路判断
- ✅ 新增：漏斗自动传输
- ✅ 新增：跨容器传输
- ✅ 新增：GUI 拆分/合并 UUID 自动分配
- ✅ 新增：跨 UUID 堆叠支持
- ✅ 新增：被动孤儿文件清理
- ✅ 新增：事务包装器
- ✅ 重构：living 文件夹按职责拆分
- ✅ 修复：堆叠活箱子时漏斗只能访问到一个活箱子
- ✅ 修复：UUID 创建时机
- ✅ 修复：跨包访问权限

## 2026-07-11

- ✅ 新增：活TNT功能
- ✅ 新增：活打火石功能
- ✅ 新增：GUI交互系统
- ✅ 新增：客户端统一交互工具
- ✅ 新增：通用交互网络包
- ✅ 新增：创造模式光标同步
- ✅ 新增：创造模式 SlotWrapper 兼容
- ✅ 新增：ExplosionComponent 双模式爆炸
- ✅ 新增：流体防爆机制
- ✅ 修复：创造模式背包中活打火石无法点燃活TNT
- ✅ 修复：创造模式光标物品消失问题
- ✅ 修复：创造模式打开容器时光标物品消失
- ✅ 重构：所有 Screen Mixin 统一使用 `GuiInteractionHelper.tryInteract()`
- ✅ 重构：引入编排器模式
- ✅ 重构：提取 `BaseLivingFunction` 基类
- ✅ 重构：`LivingFurnaceFunction` 从 317 行 → 82 行（-74%）
- ✅ 重构：`LivingHopperFunction` 从 219 行 → 75 行（-66%）
- ✅ 重构：`FunctionExecutor` 从调度器变为纯工具类
- ✅ 重构：`DirectionModeComponent` NBT 自治
- ✅ 重构：`LivingFunctionConfig` 新增 `withOrchestrator()` 方法
- ✅ 重构：`ComponentContext` 使用 `ResolvedSlots` 替代三个独立字段
- ✅ 重构：`ContainerContext` 新增槽位占用机制
- ✅ 简化：`LivingHopperFunction.canApply()` 移除冗余的物品匹配检查
- ✅ 简化：`LivingItemFunction` 接口新增 `appendComponentTooltips()` 默认方法

## v0.3-alpha

- ✅ 重构：将 `TransferDirection`、`Direction2D`、`HopperModeController`、`LivingHopperInputHandler` 合并为 `DirectionModeComponent` + `LivingItemInputHandler`
- ✅ 重构：提取 `Pos2D` 和 `SlotMapping` 为独立 model 类
- ✅ 修复：活漏斗传输方向修改后 NBT/Tooltip/实际传输未同步更新的问题
- ✅ 修复：活漏斗传输功能失效（`SlotResolver` 网格宽度计算错误）
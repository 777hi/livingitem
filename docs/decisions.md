# 决策记录（Decision Log）

> **本文是「跨领域决策视图 + 翻转留痕」的索引。** 决策**正文**写在各领域文档的
> 「定案记录 / §1.2 核心设计决策」小节，本文只存**一句话 + 指针**。
>
> **AI 读法**：
> - 改代码前 → 在下表 `grep` 相关领域，确认口径没被取代
> - 翻转某个口径时 → 走 §4 的 4 步（**旧行不删**）
> - 想找「为什么这么定」的完整理由 → 按「正文」列跳到领域文档

---

## 1. 为什么是索引，而不是经典 ADR（每条一个文件）

1. **决策正文已有归宿** —— 各技术文档的「定案记录 / §1.2 核心设计决策」小节本来就在写。
   再建一份是**重复**，而重复就会漂移。
2. **真问题不是「决策没地方写」，而是「翻转后没人知道旧结论还在哪」** ——
   需要的是**跨领域可见索引 + 强制留痕**，不是更大的文件树。
3. 单文件索引能一眼扫完、能 `grep`、能进 `tools/doc_check.py` 校验。

> 触发本文诞生的两个真实案例（2026-09-16 一次终审修掉）：
> ① 活骨粉口径翻转后，旧「四方向统一放行」的表述仍留在文档与进展里；
> ② `tryFertilize` 的 javadoc 与代码**完全相反**。

## 2. 体例

**编号**：`D-<领域>-<两位序号>`。领域短码：`core` / `tnt` / `hopper` / `farmland` / `power` /
`redstone` / `render` / `net` / `doc` / **`fluid`**（容器级活流体，2026-10-08 新增）/
**`container`**（容器边界：能力面、写回、身份、嵌套，2026-10-18 新增）。
**序号一经分配永不复用**（删行也不回收）。

**状态**：`生效` ｜ `已被取代`（有接替者）｜ `已废弃`（无接替者）

| 字段 | 说明 |
|---|---|
| ID | `D-领域-序号` |
| 日期 | 定案日期（用户拍板的写用户拍板日） |
| 领域 | 短码 |
| 决策 | **一句话**，可判定、不含理由 |
| 理由要点 | ≤ 20 字；完整理由在正文 |
| 状态 | 见上 |
| 取代关系 | 被取代 `→ D-xxx`；取代别人 `← D-xxx`；可多值 |
| 正文 | 领域文档的小节指针（或 `本文 §5`） |

## 3. 记什么 / 不记什么

**记**（会影响后续实现选择的定案）：语义口径 · 架构归属 · 结构性取舍 · 用户拍板的边界与红线。

**不记**：纯实现细节（代码里能读出来的）· 一次性 bug 修复（属 `changelog.md` 或领域文档踩坑节）·
尚未定案的讨论。

## 4. 翻转流程（4 步，缺一不可）

1. **新增**一行新决策，`取代关系` 填 `← D-旧-xx`
2. **旧行不删**，状态改 `已被取代`、`取代关系` 填 `→ D-新-xx`
   —— **留痕比整洁重要**：下一个人看到「这条被取代了」才不会照着旧结论做
3. **`grep` 扫旧结论**：把旧口径的关键词在 `docs/` + `AGENTS.md` + **代码注释**里扫一遍
   （常用词：`既有边界`、`仍被拦下`、`待复测`、`已知遗留`、`暂不支持`，以及本决策自己的旧说法）
4. 更新受影响文档的正文：旧表述要么改掉、要么标注「已于 D-新-xx 取代」

## 5. 索引

| ID | 日期 | 领域 | 决策 | 理由要点 | 状态 | 取代关系 | 正文 |
|---|---|---|---|---|---|---|---|
| D-core-01 | 2026-09-16 | core | **物品 ↔ 世界**（放置 / 交互世界方块）是独立于传输层的第三条链路，入口是 Mixin | 职责分离 | 生效 | | `living-item-infrastructure.md` §9.8 |
| D-core-02 | 2026-09-16 | core | 项目级规约**必须写在仓库里**；AI 工具侧技能只能做适配层 | 多工具协作 | 生效 | | `docs/README.md` §1 |
| D-core-03 | 2026-09-16 | core | 文档**主要读者是 AI**：写约束不写叙述、只写代码里推不出来的、数字与路径断言必须可复算 | 读者定位 | 生效 | | `docs/README.md` §1 铁律 |
| D-core-04 | 2026-09-18 | core | **区块加载事件回调内禁止任何世界交互**（能力查询 / 方块实体查找）；容器发现一律延后到 tick 阶段 | 曾致服务端线程死锁 | 生效 | | `living-item-infrastructure.md` §3.2 |
| D-core-05 | 2026-09-18 | core | 活物品的**处理**范围限定 **ticking 区**（`isPositionTicking`）；**发现**（扫描）仍覆盖全部已加载区块 | 否则读邻居触发强制加载 | 生效 | | `living-item-infrastructure.md` §3.2.1 |
| D-core-06 | 2026-09-30 | core | **绑定 UUID 是数据，玩家名是显示缓存** —— tooltip 显示统一走 `OwnerNameResolver#displayName`（实时解析 → 显示缓存 → 短 UUID），服务端在能确认名字时刷新缓存 | 离线也要显示人名 | 生效 | | `living-ender-chest-tech.md` §绑定 / `living-tool-tech.md` §5.4 |
| D-core-07 | 2026-10-03 | core | 「容器级逻辑」注册**不新增接口** —— 复用 `LivingItemFunction#shouldTickWithoutOwnItems`（默认 false）+ **注册期静态自维持清单**，不逐功能判定 | 现有 22 功能零影响 | 生效 | | `living-item-infrastructure.md` §8.2 |
| D-core-08 | 2026-10-03 | core | 容器级数据用**类型化 key + 单一存储**（`ContainerDataKey<T>` → `ContainerDataStore` **数组下标**存取），不再三处硬编码 | 新增数据 = 改 1 行 | 生效 | | `living-item-infrastructure.md` §8.3 |
| D-core-09 | 2026-10-03 | core | 容器身份键**不得含对象哈希** —— `containerKey` 第三档（`container_<handlerHash>`）删除，「无背包、无位置」者**显式抛异常** | 落盘后键漂移会静默丢数据 | 生效 | | `living-item-infrastructure.md` §2.5 |
| D-core-10 | 2026-10-03 | core | `ContainerContext` 保持**只读能力**；tick 生命周期抽 **`TickableContainerContext`** 子接口（不污染只读接口） | 职责分离 | 生效 | | `living-item-infrastructure.md` §2.6 |
| D-core-11 | 2026-10-04 | core | 多方块容器边界带「身份解析」收敛到**单一内核**：服务端 `ContainerContexts`（resolve/isViewing/ownsContainer/isSameSlotSpace）+ 客户端 `ClientSlotResolve` | 消除 6+1 处重复匹配 | 生效 | | `container-identity.md` |
| D-tnt-01 | 2026-09-18 | tnt | 爆炸破坏**按区块分帧**；**未加载区块不立即炸、交给持久化待炸账本**，等其自然加载时补上（原版 TNT 引信模型） | 既不强制加载也不残缺 | 生效 | | `living-item-infrastructure.md` §3.2.2 / `living-tnt-tech.md` §4.3 |
| D-hopper-01 | 2026-09-15 | hopper | 活漏斗**只认普通骨粉** —— 活骨粉不给漏斗施肥（四个方向一致） | 施肥属传输语义 | 生效 | ← D-hopper-02 | `living-hopper-tech.md` §6.2.1 |
| D-hopper-02 | 2026-09-15 | hopper | ~~活骨粉放行到四个方向~~（曾以「交互是消耗不是搬运」为由给隔离规则开洞） | 方向错误 | **已被取代** | → D-hopper-01 | 无（正文已删） |
| D-farmland-01 | 2026-09-15 | farmland | 施肥方程收编为**注册式槽位交互**（`SlotInteractions`），三处传输分支只调分发器 | 漏调用点降为一次 | 生效 | | `living-item-infrastructure.md` §9.7 |
| D-farmland-02 | 2026-09-16 | farmland | 放置活耕地回世界：**模拟玩家右键**（`ItemStack.useOn`），**不自己 `setBlock`** | 不绕过模组校验 | 生效 | | `living-farmland-tech.md` §3.5 |
| D-farmland-03 | 2026-09-16 | farmland | 放置时**一律种成幼苗**，不保留物品里的成熟度 | 简单优先 | 生效 | | `living-farmland-tech.md` §3.5 |
| D-farmland-04 | 2026-09-16 | farmland | 自动种植是**软逻辑**：种不上（含抛异常）必须静默，绝不影响原版放置流程 | 用户定调 | 生效 | | `living-farmland-tech.md` §3.5 |
| D-farmland-05 | 2026-09-16 | farmland | 耕地槽种子图标走 **`IItemDecorator`**（不再写容器 Mixin） | 快捷栏不经过该屏幕 | 生效 | | `icon-system.md`「种子图标改走装饰器路径」 |
| D-power-01 | 2026-09-02 | power | 共振发生在**网络之间**，锈蚀级 = 共振「声部」；`R = 1+(N−1)s ∈ [1,4]` | 二值隔离无意义 | 生效 | | `living-power-tech.md` §3.7 |
| D-power-02 | 2026-09-02 | power | 解锁度 `u = 调谐效率 × (n/偏好周期)`；**移除规律度因子** | 玩家自由选难度 | 生效 | ← D-power-03 | `living-power-tech.md` §3.1 |
| D-power-03 | （历史） | power | ~~`合因子 = (log₂\|Δ\| × n)^(1+u)`，解锁度含「规律度」因子~~ | 因子数过多 | **已被取代** | → D-power-02 | `system-design/红电系统.md` 修订历史块 |
| D-power-04 | 2026-09-02 | power | 共振**只读「共振前」基础值、单遍前馈、绝不回代**（否则成永动机） | 铁律 | 生效 | | `living-power-tech.md` §3.7 |
| D-power-05 | 2026-09-11 | power | 份额 / 比例计算**先转 double 再夹上界**（mFE 定点制会 long 溢出） | 曾致服务端冻结 | 生效 | | `power-invariants.md` §2.G |
| D-doc-01 | 2026-09-16 | doc | 入口文档（`AGENTS.md`）**≤ 20,000 字符**，只放路由与不可违反的约束 | 新会话加载预算 | 生效 | | `docs/README.md` §1 |
| D-doc-02 | 2026-09-16 | doc | ~~「开发进展」只保留最近 3 个批次，其余迁 `changelog.md`；先查冗余再动历史~~ | 历史是资产，冗余是负债 | **已被取代** | → D-doc-04 | `docs/README.md` §4 |
| D-doc-03 | 2026-09-16 | doc | 搬运文档时**搬走不删掉**，且**守恒校验必须逐段判定**（不能只验第一块） | 曾误删整段 | 生效 | | `docs/README.md` §5 / §7.2 |
| D-doc-04 | 2026-09-22 | doc | 「开发进展」改**一行式滚动**（最近 10 条「结论 + 指针」），**完整正文直接写 changelog**，**取消归档环节** | 条目均 705 字符占入口 55%；归档高风险手工操作 | 生效 | ← D-doc-02 | `docs/README.md` §4 |
| D-doc-05 | 2026-10-10 | doc | **清单型内容退出文档**：删 `file-map.md` 主树与 `completed-features.md`（可从代码枚举 ⇒ `find` / `gen_code_map.py` 替代），关键类指针下沉各 tech §0「实现入口」，测试树拆 `test-map.md` 保留；被否方案：脚本生成（生成物无注释 = 纯骨架）、就近年清单（必漂无解） | 人工清单必漂（实证缺 112+，同文件测试树有校验则不漂） | 生效 | | `docs/README.md` 铁律 3 / `docs/reference/test-map.md` |
| D-doc-06 | 2026-10-10 | doc | **需求池统一到 `TODO.md`**：tech §0 删「未实现 / 待定」字段（计划迁 TODO 并带 tech 指针；**「明确不做」的边界不随迁**，归位 §0 规则表 / 「边界与留痕」节）；被否方案：收敛到 §0（曾推荐，用户拍板反转 —— 统一一处好查，不翻 15 份） | 计划与契约混排，AI 易把 ⏳ 读成边界、⚠️ 读成计划 | 生效 | | `docs/TODO.md` 头部 / `docs/README.md` §1 §0 定义 |
| D-fluid-01 | 2026-10-03 | fluid | **源只有一种（容器资产）**：取消「桶源」，水要么在桶里（`BucketItem.content`）要么在容器里（源），**互斥**，经倒/汲转换 | 桶源 = 重复计账 | 生效 | | `living-fluid-tech.md` §4 |
| D-fluid-02 | 2026-10-04 | fluid | **桶 = 载体，零私有状态**：内容 = 原版 `BucketItem.content`，形态变换走 `Fluid.getBucket()` 注册映射 | 曾发明私有组件是负资产 | 生效 | | `living-fluid-tech.md` §5 |
| D-fluid-03 | 2026-10-03 | fluid | **转化无独立节拍**（即时转化）；延迟会与漏斗节拍互锁成活锁 | 转化全是蓄意的 | 生效 | | `living-fluid-tech.md` §6 |
| D-fluid-04 | 2026-10-06 | fluid | **统一时钟**：生长类（蔓延/晋升/推动）走该流体自己的节拍；反应类（挤没/源即时/焚毁/前沿反应/转化）**当拍**生效 | 外部输入不该等 30t | 生效 | | `living-fluid-tech.md` §3 |
| D-fluid-05 | 2026-10-07 | fluid | **目标层按「到达时间」抢占**（多源 Dijkstra，每格成本 = 该流体节拍） | 分配与到达同一时钟 | 生效 | ← D-fluid-06 | `living-fluid-tech.md` §3.1 |
| D-fluid-06 | 2026-10-07 | fluid | ~~目标层按「距离 + 入队顺序」抢占~~ | 与实际到达时间无关 | **已被取代** | → D-fluid-05 | 无（正文已删） |
| D-fluid-07 | 2026-10-07 | fluid | **目标层 BFS 跳过「实际层已被异种流体占据」的格**（蔓延不驱逐，接触面交给反应） | 驱逐会留接触前空档 | 生效 | | `living-fluid-tech.md` §3.1 |
| D-fluid-08 | 2026-10-06 | fluid | **倒桶的异种源格替换是对称的**：水浇岩浆源 / 岩浆倒进水格**都替换**（原版倒桶无方向之别） | 单向限制纯属自造 | 生效 | ← D-fluid-09 | `living-fluid-tech.md` §5 |
| D-fluid-09 | 2026-10-06 | fluid | ~~只有「水可替换岩浆源」，岩浆桶倒进水格被拒~~（B 档） | 引错函数（那是蔓延门槛） | **已被取代** | → D-fluid-08 | `living-fluid-tech.md` §5 |
| D-fluid-10 | 2026-10-06 | fluid | **管道抽取 = 整源单位**：请求 ≥1000 给满 1000 并删源，<1000 **一分不给** | 源到处是二进制语义 | 生效 | | `living-fluid-tech.md` §10 |
| D-fluid-11 | 2026-10-03 | fluid | **不做「水流跨容器蔓延」**（物品过境、水不过境；跨容器需求由桶/漏斗/活塞覆盖） | 渲染割裂、复杂度爆炸 | 生效 | | `living-fluid-tech.md` §4 |
| D-fluid-12 | 2026-10-07 | fluid | **活桶无 tooltip 内容行**；「--- 活物品 ---」是**「这是活物品」标记**，零内容行时**照旧输出** | 形态本身就是信息 | 生效 | ← D-fluid-13 | `living-fluid-tech.md` §5 |
| D-fluid-13 | 2026-10-07 | fluid | ~~零内容行则整段不输出（惰性标题）~~ | 会把「是活物品」标记一起丢掉 | **已被取代** | → D-fluid-12 | 无（正文已删） |
| D-container-01 | 2026-10-18 | container | **容器发现只认能力面**（`ItemHandler.BLOCK`）；`instanceof Container` 不作为「这里是不是容器」的判据 | 能力面才是扫描口径，两套口径必错位 | 生效 | | `container-boundary.md` I-C1 |
| D-container-02 | 2026-10-18 | container | **写回 ≠ 插入**：改自己槽位的物品用 `setStackInSlot`；`insertItem` 只表达「放进这个容器」，不表达「放进这个槽位」 | 第三方容器的 insertItem 可忽略 slot | 生效 | | `container-boundary.md` I-C3 |
| D-container-03 | 2026-10-18 | container | **模拟与真实写入必须同源**；模拟结果只是上界，不得当承诺 | 模拟认槽、真实整库 ⇒ 漏斗吞物品 | 生效 | | `container-boundary.md` I-C4 |
| D-container-04 | 2026-10-18 | container | **容器身份与 viewer 匹配按位置键**，不按 `Container` 实例；不得用 handler 实例反查（多层包装 + 每槽匿名 container） | 非 Container 容器否则永远匹配不上 | 生效 | | `container-boundary.md` I-C5 |
| D-container-05 | 2026-10-18 | container | **不采用「容器能力白/黑名单」排除非 `Container` 模组容器**；改为口径收敛（D-container-01/04） | 排除 = 放弃兼容 + 永久适配债 | 生效 | ← TODO 2026-10-10 待决项 | `container-boundary.md` §0 · `container-identity.md` §5 |
| D-container-06 | 2026-10-18 | container | **「扫描展开」与「活物品内部递归」是两套机制，共用一条深度预算**；扫描展开上限 1，内部递归上限由功能声明（活潜影箱 4） | 两个独立上限会叠成 1+4=5 层 | 已被取代 | → D-container-08 | `container-boundary.md` §3 |
| D-container-07 | 2026-10-18 | container | **物品池（AE2 ME / RS 网络）不得作为 tick 宿主**，只能当传输端点（给 `SlotAccessor` 加池实现，不给 `ContainerContext` 加） | 池无槽位无几何，功能无从表达 | 生效 | | `container-boundary.md` I-C8 |
| D-container-08 | 2026-10-19 | container | **容器内物品被 tick 是活潜影箱的专属逻辑，容器边界不做通用「扫描展开」**——框架只 tick「方块形态容器」与「玩家背包」；物品形态容器内部的 tick 由活潜影箱功能自己驱动（功能内部递归）。⚠️ 2026-10-21 补注：框架 tick 宿主**已扩到实体形态容器**（`D-container-10`），本条只约束「不做扫描展开」 | 通用扫描展开要遍历每个容器的每个物品查 `ItemHandler.ITEM`（108 格 × N 容器），性能需额外筛选；而活潜影箱自带「已被活化」这一门槛，展开范围天然有界。且「物品内部自成世界」本就是活潜影箱的核心卖点，泛化到所有背包会稀释它 | 生效 | ← D-container-06 | `container-boundary.md` §3 · `buffer/活潜影箱实现细节.md` |
| D-container-09 | 2026-10-19 | container | **核心不做第三方 API 猜测**：容器兼容只提供机制（结构化解析 + 注册口），**不内置任何「反射第三方方法名」的兜底**；难兼容的模组走**联动模块**（`living/compat/<mod>/` + `ModList` 门控 + 注册），与 Create / Sable 的软依赖同构 | 内置反射等于把核心绑在某个模组的 API 形状上（曾为精妙背包内置 `getBlockPosition` 反射）；模组一改名，失效的是**核心**。下沉到联动模块后，失效范围被关在模块内，核心与其它模组不受牵连。另：精妙背包只是**一个例子**，不该让本模组长成它的模样。**同一原则的第二面（2026-10-20）：规则查找也只按精确 `(id, 容量)`，不按 namespace + 分词模糊匹配**——模糊命中同样是「猜这条规则大概适用于这个 BE」，且多档时只取第一档 | 生效 | | `container-boundary.md` I-C5 · `living/compat/sophisticated/` · `living/transfer/ContainerCompatibilityConfig.java` |
| D-container-10 | 2026-10-21 | container | **实体容器是第四类 tick 宿主**（船 / 矿车 / 带箱驴骡）：身份键 = `entity_<uuid>`（**位置不进键**，每 tick 现取）、`getBlockPos()` 恒 null、数据宿主走 `getDataHosts()`；实体白名单（`ContainerEntity` + `hasChest()`）是「能力面原则」在实体上的**唯一收紧例外** | 位置键对会动的宿主必漂；`ItemHandler.ENTITY` 对全体 `LivingEntity` 有 fallback | 生效 | | `container-boundary.md` I-C1 / I-C5 / I-C9 · `living/container/EntityContainerContext.java` |
| D-container-11 | 2026-10-21 | container | **逻辑槽 ≠ 物理槽**：`ContainerContext` 对外只暴露逻辑槽（只数存储格），宿主 handler 的物理槽靠 `toPhysicalSlot`/`toLogicalSlot` 一对互逆映射转换；新增前置非存储槽时必须一次性枚举**全部跨边界点**（读写 / 对外身份 / GUI 反查） | 只改读写 ⇒ 五条通道各偏一格 | 生效 | | `container-boundary.md` I-C10 · `living/container/ContainerContext.java` |
| D-container-12 | 2026-10-21 | container | **模组自己搬容器时，用「双向交接」补第三个挂点**：核心只提供 `ContainerHandoff`（`capture`/`handoff` 宿主→物品、`stageRestore`/`restoreTo` 物品→宿主、`registerMover`），发现时机由**联动模块的 Mixin** 挂具体调用点，搬运逻辑**必须复用事件路径那份 `moveOut`/`moveIn`**。⚠️ 接口不得留 default 方法（注册点写方法引用时会静默漏掉另一个方向，真机三连失败的真凶，已加反射守卫） | 事件之外的时机不可预测；「认领式兜底」会错认领；精妙拾取与放置**两个事件都不触发** | 生效 | | `container-boundary.md` I-C9 · `living/container/ContainerHandoff.java` · `living/mixin/sophisticated/` |
| D-container-13 | 2026-10-22 | container | **容器级数据的持久化方式由 ctx 自己声明**：`ContainerContext#persist(ContainerDataKey<T>, T)`（default 走 `getDataHosts()` 写附件，**物品形态覆写为写 SavedData**）。领域不得假设有 BE；拿不到 `IAttachmentHolder` 不是异常，是一种正常宿主。否决方案 A（每个领域各自判空 hosts） | 活潜影箱会让物品形态成为常态；B 对扩展开放、对修改关闭，且顺带统一 key↔附件↔CODEC | 生效 | | `container-boundary.md` I-C9 · `buffer/living-backpack.md` §3.4 · `living/container/ContainerContext.java` |
| D-container-14 | 2026-10-23 | container | **「谁在看这个容器」收成唯一入口 `ContainerViewers.of(players, cacheKey, positions)`**：按缓存键种类分派（玩家态 ⇒ 主人 + 末影箱门控 · 物品形态 ⇒ 菜单锚点 · 其余 ⇒ 位置）。两条数据轨（流体快照 / 运行时数据）都只调它，`ContainerRuntimeCache.findViewersByPositions` 删除 | 同一判定原本两条轨各一份 if-chain ⇒ 加新形态漏改一条就静默失效（2026-10-23 一天内连踩两次：流体轨修了、运行时轨没修 ⇒ 活漏斗 tooltip 不显示容器信息）。收成一个方法后「漏一条」在结构上不可能 | 生效 | | `container-boundary.md` I-C5 · `living/container/ContainerViewers.java` |
| D-container-15 | 2026-10-23 | container | **容器世界锚点收成 `ContainerAnchor`（参考物，不是坐标）**：`AtBlock` / `AtPlayer` / `AtEntity` / null。`ContainerContext#getAnchor()` 一处分类，**功能自己决定取哪个点**（射线要眼睛、应力要下方、爆炸要中心） | 「容器在世界里作用在哪」原有 4 份各写一遍的解析（TNT 爆炸 / 水车应力 / 工具射线 / 信标光束），5 种容器形态在每份里处理都不同 ⇒ 同一功能在不同形态下的行为没人有完整视图。若定义成坐标，四处取点差异就无法收拢（而这个差异是对的） | 生效 | | `container-anchor-design.md` §3 · `living/container/ContainerAnchor.java` |
| D-container-16 | 2026-10-24 | container | **宿主锚点的四个消费方各由 SERVER 型玩法开关控制，默认全关**：`tnt` / `water_wheel` / `tool_and_weapon` / `beacon`。配置值缓存在静态字段（加载/重载事件刷新） | 「背包借宿主的位置干什么」是**玩法**不是修复（炸弹/随身发电/自动挖矿/随身光环），默认生效等于替玩家做玩法决定；而 `SERVER` 型是唯一能让客户端知道服务端选择的方式（自动同步 + 可按存档覆盖）。缓存字段是因为 `ConfigValue#get()` 在配置未加载时会抛异常，而这些值在 tick 路径上每容器每 tick 都读 | 生效 | | `living/config/GameplayConfig.java` · `living-backpack-tech.md` §5.5 |
| D-container-17 | 2026-10-24 | container | **放置态列数也由联动来源给权威值**：`ItemFormContainers.Source#columnsOfBlockEntity(BlockEntity)`（与物品形态的 `columns(stack)` 同一套「存储槽数 ÷ 行数」），`SimpleContainerContext#getWidth()` 新增 **Tier 0**，且**来源值优先于规则表** | 方块容器的三层推断（规则表 / 已知类型表 / 启发式）不认识第三方 BE ⇒ 精妙背包装储罐后 30 存储槽被猜成 10 列（真值 5），而物品形态有 `Source#columns` 这条权威路 ⇒ 同一背包装在手里对、放在地上错。来源值从活对象现算、与升级状态同步，静态规则反而会随模组改版过期 | 生效 | | `living/container/ItemFormContainers.java` · `living/container/SimpleContainerContext.java` · `living/compat/sophisticated/` |

## 6. 边界（诚实说明）

- 本索引**不能**自动发现「javadoc 与代码相反」这类漂移（今天那处就是）—— 那靠代码审查与
  `doc_check.py` 的路径检查；本索引只覆盖「文档 / 注释里的旧口径」。
- 索引自己也会漂移 ⇒ `doc_check.py` 已加一条：**校验「取代关系」双向一致**
  （A 说取代了 B，B 必须说被 A 取代）。
- **回填范围**：只收「已知被翻转过的」+「当前生效的跨领域口径 / 架构归属」。
  逐子系统的实现细节决策留在各技术文档的「定案记录」小节，本索引不收录。

## 7. 与其它文档的关系

| 文档 | 关系 |
|---|---|
| 各技术文档的「定案记录」小节 | **决策正文**（本索引指向它们） |
| `docs/archive/changelog.md` | **发生了什么**（流水账）；决策是**为什么这么定** |
| `docs/README.md` §7.2 | 「翻转后 `grep` 扫旧结论」的通用规则 |
| 本文件 | **跨领域决策视图 + 翻转留痕** |

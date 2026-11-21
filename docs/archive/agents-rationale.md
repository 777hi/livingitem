# AGENTS.md 判据的事故依据

> **性质**：归档现场记录，服务 [AGENTS.md](../../AGENTS.md) 的三条铁律（排查顺序 / Mixin 前缀 / 排他注入器）。
> 现行判据与复算命令在 AGENTS.md，**本文不重复规则，只保留「当时发生了什么」**。
> 只有问「凭什么这么规定」时才来；问「现在怎么做」回 AGENTS.md。

## 1. 排查顺序：2026-09-24 无敌帧事故

需要「无视实体无敌帧打出多段伤害」时，grep 到 `LivingEntity#hurt` 里的
`getPostAttackInvulnerabilityTicks()`，**却没深追**，直接去改 `public int invulnerableTime` 字段。
正解是 NeoForge 官方接口：`LivingIncomingDamageEvent#getContainer().setPostAttackInvulnerabilityTicks(0)`
（铁魔法 `DamageSources#preHitEffects` 正是这么做的）。

**代价**：野路子会在 MC / NeoForge 改内部实现时静默失效，且往往比官方接口影响面更大。
**判据**：看到 getter 就该想到有 setter，看到 container 就该想到有事件入口。

## 2. Mixin 前缀：2026-10-11 两次碰撞与一次推断失效

### 2.1 推断失效（裸 `@Accessor` + 带前缀方法名 ⇒ 启动即 FATAL）

Mixin 靠方法名的 `get` / `is` / `set`（Invoker：`call`）前缀**推断目标成员名**。
裸 `@Accessor` + 带前缀方法名 ⇒ 推断失效 ⇒
`InvalidAccessorException: Failed to inflect target name ... supported prefixes: [get, is]`。
**「加前缀」与「显式指定成员名」必须成对出现，缺一即崩。**

### 2.2 两次跨模组碰撞

1. `SlotWrapperAccessor.getTarget()` 与 inventoryessentials（背包必备）的同名 `@Accessor`
   命中同一 `CreativeModeInventoryScreen$SlotWrapper` ⇒ 崩溃。已改名
   `LivingItemSlotWrapperAccessor` + 方法 `living_item$getWrappedTarget()`。
2. `AbstractContainerScreenAccessor.getSkipNextRelease / setSkipNextRelease` 是原版私有字段
   `skipNextRelease` 推导出的通用名，与 `getTarget` 同一类隐患（只是暂未撞上其它模组）。
   已全部加 `living_item$` 前缀。

触发场景：「打开创造模式物品栏并点标签页、加载 `Slot` 时」。单人环境不装联动模组测不出。

### 2.3 `@Shadow` 的反向规则（同日更正）

`@Shadow` 是「别名目标已有成员」，必须与目标成员**同名**、**不能**加前缀
（加了 = 解析不到目标而失效）；且 shadow 成员被剥离、不生成新成员 ⇒ 无碰撞风险。
「建议 @Shadow 也加前缀」是 2026-10-11 更正掉的错误说法。

## 3. 排他注入器：2026-11-14 @Redirect 迁移

`@Redirect` / `@ModifyArg` / `@ModifyArgs` / `@Overwrite` 对注入点 / 方法**独占**，
第三方模组在同一点也注入 ⇒ 加载期 `MixinApplyError` 客户端崩溃。
与 `@Accessor` 撞名同型：「单人永远测不出，装了联动模组才炸」。
2026-11-14 全部 3 处 `@Redirect` 已迁到 MixinExtras 的可叠加注解（`@WrapOperation` / `@ModifyExpressionValue`）。

## 4. 跨原版边界：恢复链「读起来通」≠ 通（2026-11-21 发现缓存误踢）

「走远回来箱子静默停 tick」的根因不在本模组的任何一行代码 —— 在对原版的一个**隐含假设**：
「区块可见（`getChunkNow` 非 null）＝ 方块实体可读」。实际 BE 在 FULL 期间躺在
`pendingBlockEntities`，批量晋升挂在 `prepareTickingChunk` 的 future 链里且**无事件**
（事实清单见 `reference/chunk-pipeline-timeline.md`）。排查中**两次**纯读本模组代码
判定「恢复链是通的」都没用 —— 缺的那环是对端系统的契约。

📌 **教训**：修一条「看起来自洽」的链路时，若现实不服，把怀疑点从链上代码转向
**每个环节的前置假设**（对原版 / 对其它模组承诺了什么），用反编译源码核对假设本身。
同族信号：静默失败 + 平时正常 + 走特定路径必现 + 重进自愈 —— 三样占全就优先查边界契约，别急着补日志。

> 附带的便宜保险：给「曾发现的容器区块被移出缓存」打了观测 WARN —— 这类问题有了它
> 本可分钟级定位。

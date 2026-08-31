好喵，把这条间接链从头到尾展开。它本质上是**一次跨世界的留言**：电缆抽电发生在 tick 框架之外，而“同步给客户端”和“标记存档”这两件事只有 tick 框架里才有上下文去做——所以异步侧只能留下一个标记，让下一个 tick 来收拾。

## 1. 为什么不能当场做完

`extractEnergy` 被调用的时机是**任意的**：Mekanism 电缆按自己的节拍轮询，不在我们容器 tick 的调用栈里。此时异步侧手上只有一个 `BlockEntity`，它做不了那两件事：

- **同步客户端**：发包需要知道“灯在容器的第几号逻辑槽”（`slotIndex`）、要区分玩家背包还是世界容器、要往 `TickContext.dirtySlots` 里投——这些全部住在 `SimpleContainerContext` 里，能力路径碰不到；
- **落盘**：多格箱有两个 BE，`be.setChanged()` 得对**关联的全部 BE** 调用，这个集合（`associatedBlockEntities`）也只有 glue 层知道。

而 `stack.set(组件)` 本身两个都不触发——这正是之前「客户端 NBT 不变小」问题的根源结构。所以：**改组件是异步侧能做的，收拾残局是 tick 侧的职责**，标记就是两者之间的桥。

## 2. 一次电缆抽电的完整时间线

```
【tick N，电缆的任意时刻】
① 电缆查能力：level.getCapability(EnergyStorage.BLOCK, pos, side)
   → resolveProvider 让位判定通过 → 拿到 ContainerEnergyStorage
② extractEnergy(4000, false)：
   - resolveItems → 原版箱子 ItemHandler（与容器 tick 同一条兼容面）
   - getPowerDataByPos → 位置反向索引 O(1) 查 POWER_DATA_CACHE
     ★ 拿到的是 glue 每 tick 用的同一个 ContainerPowerData 实例
③ extract() 逐堆扫灯：newQ = q − perLamp，set 原语替换组件
   （服务端权威值当场生效）
④ 回调 power::markExternalMutation()：
   - externalMutation = true          ← 留言
   - lastTickTime = now               ← 附带效果：账本不会被 120s 过期清理掉
⑤ 返回 floor(got/1000) FE 给电缆

【tick N+1，容器 tick pass，priority 3】
⑥ LivingWaxedCopperFunction.tickContainerData：
   发电 → distributeToBulbs → consumeExternalMutation()
   ★ 消费是原子的：读到 true 的同时清零（一次性）
⑦ externalMutated == true → 遍历 entries 里所有铜灯槽：
   ctx.syncSlotToClients(slot, stack) → dirtySlots.add(slot)
⑧ 同一个 if：(bankChanged || externalMutated) →
   对 associatedBlockEntities 全体 be.setChanged()   ← 落盘信号
⑨ tick 基建收尾：flushDirtySlots() 逐脏槽读当前栈，
   发 ClientboundContainerSetSlotPacket → 客户端 NBT 刷新
```

## 3. 这个设计的几个精妙处

**合并去抖**：一根电缆一 tick 内抽 10 次、甚至 5 根电缆同时抽——标记只有一位，`dirtySlots` 是 Set。十次抽电在下一个 tick 压缩成**一次**批量同步，每槽最多一个包。标记的语义不是“改了哪几盏”，而是“有外部改动，请全量对账”——量小的时候粗一点，换来的就是零上下文传递成本。

**模拟抽取零痕迹**：电缆轮询前普遍先 `extractEnergy(x, true)` 探测。`extract()` 里回调的触发条件是 `changed && !simulate`——模拟不改组件、不回调、不留标记。否则光是电缆的空轮询就会让每个容器每 tick 白白发同步包喵。

**一次订阅，永久有效**：标记不需要“注册哪几盏灯被改了”。glue 收到标记后直接对 entries 里**全部**铜灯槽同步——多抽几盏无所谓，反正 Set 去重、包是幂等快照。

## 4. 已知的薄弱处（上轮提过，这里说全）

- **一 tick 延迟**：客户端看到的 NBT 永远滞后抽电一拍。服务端权威值是即时的，只影响显示。
- **`power == null` 退化**：账本还没建（区块刚加载、第一 tick 未跑）时回调为 null，这次扣减既无同步也无落盘信号。窗口极窄，且下次任何改动都会补 `setChanged`，实际风险趋近于零。
- **崩溃回滚方向**：落盘推迟到下一 tick 意味着“抽完电、下一 tick 前崩服”会回滚成灯满电 + 电缆已拿电——电网侧亏一格，方向安全（不刷电）。
- **一个不对称**：充电路径（receive）的回调是当场 `be.setChanged()` 双保险，取电却完全依赖延迟链。原因上讲取电可以抄同款写法当场落盘，属于历史写法——不是 bug，但如果哪天要给取电也加“当场落盘”，照 receive 的样子改回调就行。



崩溃回滚方向：落盘推迟到下一 tick 意味着“抽完电、下一 tick 前崩服”会回滚成灯满电 + 电缆已拿电——电网侧亏一格，方向安全（不刷电）。
一个不对称：充电路径（receive）的回调是当场 be.setChanged() 双保险，取电却完全依赖延迟链。原因上讲取电可以抄同款写法当场落盘，属于历史写法——不是 bug，但如果哪天要给取电也加“当场落盘”，照 receive 的样子改回调就行。

这两条再细讲一下喵




这两条要细讲的话，得先纠正我上一轮的一处**说反了的结论**喵。

## 0. 先纠错：崩溃回滚的方向不是「安全」，恰恰是刷电方向

上一轮我说「回滚成灯满电 + 电缆已拿电——电网侧亏一格，方向安全」。把这两个状态摆在一起看就会发现矛盾：灯恢复满电（被扣的 q 回来了），电缆电池里那份 FE 又是 Mekanism 自己用 `setChanged` 正常落盘的（重启后还在）——**两边都保留了，世界上凭空多出刚抽走的那份电**。这是刷电方向，不是亏电方向。正确的安全方向应该是「灯减了、电缆那份没落盘」——即回滚发生在充电路径上时才是亏电侧。

下面把整个机制讲清楚，这个结论为什么成立也就清楚了。

## 1. Minecraft 的落盘模型：`setChanged` 是唯一官方信号

- BE 数据改动本身**不会**写盘。`be.setChanged()` 做的事是把所在 chunk 标成 unsaved；
- IO 线程周期性把 unsaved chunk 序列化写盘（每 tick 有配额），卸载/退出时全量保存；
- **崩服没有优雅保存**——磁盘上只有 IO 线程已经写完的那一份；
- 所以漏调 `setChanged` ≠ 数据马上丢，而是「这个 chunk 可能被周期存盘跳过」，漏掉的改动要等到**下一次任何人对它 setChanged** 才会搭车落盘。

## 2. 取电延迟链的崩溃时间线推演

```
T0      电缆抽走 4 FE，灯 q 在内存里已减少
        回调只设标记 → 本 chunk 没有因这件事变脏
        磁盘状态：上一次写盘时的灯 = 满
T0~T1   ⚡ 崩服落在这个区间 ⚡
T1      glue 消费标记 → setChanged → chunk 标脏
T1.x    IO 线程写盘，新电量落盘（窗口正常情况下在这里闭合）
```

崩服发生在 T0~T1.x 之间的后果：重启后灯从磁盘读回**满电**，电缆电池 +4 FE（Mekanism 落盘了）→ **净增 4 FE，凭空产生**。量级 ≤ 单次抽取量，窗口 ≈ 1 tick + IO 写盘延迟。

**这个窗口什么时候真实存在**：如果 chunk 因其它活动本来就是脏的（比如同箱有发电机在发电，tick 路径每 tick `bankChanged → setChanged`），窗口塌缩到正常的脏块存盘延迟，几乎无风险。最坏的典型恰恰是你测试的配置——**纯电池箱**（只放灯、无发电机）：tick 路径 `generatedRe=0` 不触发 `setChanged`，那条延迟链就是唯一的落盘信号。也就是说窗口虽窄，但在「外部充电 → 拔掉充电 → 电缆抽电」的纯存储场景里它是真实敞开的。

## 3. 不对称的确切代码对比

```java
// extractEnergy —— 回调只留标记
power != null ? power::markExternalMutation : null

// receiveEnergy —— 当场落盘 + 留标记（双保险）
power != null ? () -> { be.setChanged(); power.markExternalMutation(); } : be::setChanged
```

注意连 `power == null` 的回退都不对称：充电回退到 `be::setChanged`（没账本也照样落盘），取电回退到 `null`（无任何信号，上次讲过的那个边界）。

而守恒敏感度其实应该**反着排**：

- **取电漏落盘** = 灯回滚 + 电缆已拿 → **刷电方向**，危险侧；
- **充电漏落盘** = 灯回滚（充进去的消失）+ 电缆已付出 → **亏电方向**，安全侧。

现状却是「充电当场落盘、取电走延迟链」——把当场落盘给了安全侧，把延迟链留给了危险侧。这个不对称大概率是历史写法：`markExternalMutation` 机制本来就是为修「取电后客户端 NBT 不变小」而加的，当时顺手把同步修好了，落盘只是通过延迟链**间接**达成，没有像 receive 那样补上当场调用。

## 4. 修法与残余风险

照 receive 的样子改一行回调即可：

```java
power != null
    ? () -> { be.setChanged(); power.markExternalMutation(); }
    : be::setChanged
```

代价是每次取电多一次 `setChanged`（幂等，只是置脏标记，开销可忽略），收益是把危险侧的崩溃窗口从「最坏到下次有人标脏」压到「正常存盘延迟」。要说明的是这**不能消除**窗口——`setChanged` 之后、IO 写盘之前崩服依然存在同样的回滚，这是 MC 存档模型的原生属性（Mekanism 自己也一样）；它只是把取电的残余风险压到和充电路径、和整个模组生态同一条水平线上。

要不要我把这行改了，你说了算喵。
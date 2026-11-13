# 测试文件树（每个测试类守什么）

> **性质**：测试类的**一句话意图说明**（守什么契约）。这是**叙事型**内容 ——
> `find` 能列出测试文件名，列不出「守什么」⇒ 保留在文档里。
> **机械校验**：`doc_check` 第 3 项保证本表与 `src/test` 实际文件一致（多列 / 漏列即 FAIL）
> ⇒ **新增 / 删除测试类必须同步本表**（含 `*IT.java`）。
>
> **用例数不在此维护**：全量数据由 `python tools/test_inventory.py` 生成
> （`build/test-inventory.md`）。本表只回答“每个测试类守什么”，不回答“有多少条用例”。

> 2026-10-10 从 `file-map.md` 拆出（原文件删除）：
> 其**主文件树**（`src/main`）是**清单型**内容 —— 随代码线性增长、无法人工同步
> （自证漂移：09-16 复核缺 39 个、10-10 实测缺 112+，`tools/` 整个领域包都不在树里），
> 改用 `find src/main -name "*.java"` / `python tools/gen_code_map.py --query <类名>`；
> 关键类的职责指针已下沉到各 `docs/tech/living-*-tech.md` §0「实现入口」。
> 同一份文件里主树漂 16% 而本树不漂（第 3 项守着），即「清单型退出、叙事型保留」的对照证据。

```
src/test/java/com/qiqi/li/
├── LivingItemChunkContainerScanTest.java     # 战利品容器不处理但保留区块 / 开箱回到能力路径；水·岩浆真实单箱附件 NBT→单次 Load 延迟可见→重新发现→实际 tick 发包 / 删末源保存重载不复活
├── LivingItemRuntimeContainerPositionsTest.java  # 运行时遥测发包「容器键→位置」反查·非 Container BE(精妙背包/ProjectE)返回位置不抛异常(I-C5 翻转)/原版 Container BE/无 BE 返回 null + 缓存键反查·前缀定向该维度且不越维度找补/未知维度/裸键扫描兜底/背包坏键安全跳过/dimPrefixOf + 实体容器键→实时位置/实体不存在→空（2026-10-21）
├── LivingItemMenuRuntimeTest.java            # 混合实体菜单首包·主实体与玩家保留、附加末影箱不得覆盖单非玩家缓存，普通菜单原路由不变
├── testutil/
│   └── FakeContainerContext.java              # ContainerContext 测试替身（内存数组实现）
├── client/gui/
│   ├── ContainerInfoSessionTest.java           # 菜单信息会话·初次查询/悬停限频重试/菜单与请求双关联/晚包拒绝/ID复用新会话/未请求不接收
│   ├── ContainerInfoTooltipTest.java           # 容器提示·普通几何与末行/启发式普通警示/未知与非运行分开/高级来源和类型 ID
│   └── LivingButtonLayoutTest.java            # 活按钮位置布局·应用位置时的屏幕夹取（屏内不动/越界夹边缘/屏幕小于按钮取 max(0,…)）+ 默认位分流（背包=小人框底居中 42,70 / 其余容器=底部中央）+ 小人框矩形常量对齐原版 (26,8)-(75,78)
├── client/input/
│   ├── FarmlandPlantHintTest.java             # 活耕地种植悬停提示·门槛与交互同源（非活种子/非活耕地/非耕地物品/不可种植/空栈→null）+ 四态文案键与参数（充足消耗数/创造免费/不足差额/已种植）
│   ├── LivingChestDepositHelperTest.java       # Shift+左键收纳判定（批次 3，2026-11-01）·正例（左键+Shift+页签激活+书可见+普通物品）/ 空槽 / 槽内是活箱子（它是容器不是内容物）/ 页签未激活 / 书面板不可见 / 未按 Shift / 非左键 / null 槽位不抛。⚠️ GLFW 鼠标键 0 基（MOUSE_BUTTON_1==0），判据用常量不用数字
│   └── LivingItemInputKeyMapTest.java         # 方向输入键码表·九宫格 8 键映射/未知键为 0/全角字母归一化与转大写链路（2026-10-29）
├── client/icon/
│   ├── WaterWheelRenderStateTest.java         # 水车图标旋转时钟·速度(Create口径 6°/s/RPM)/方向(符号随rpm)/rpm=0恒0/360°回绕连续/大时钟量级不退化(2026-10-29 换源回归)
│   ├── LivingIconSpecTest.java                # 方向getter自声明/内建四向读取/非活不读方向/null与旋转模式冲突/完整namespace缓存键/GUI与非GUI回退
│   ├── DirectionalLivingModelTest.java        # 家族 GUI 角度约定·四向视觉角表pinned(实机调校点)/非四向兜底归位/物品管线相反数与火把家族实机验证值逐项一致
│   └── PistonRenderStateTest.java             # 活活塞伸缩进度时钟·伸出/收回插值与端点钳制/中途翻转从当前进度续接/方向变化吸附不滑动/键独立/活跃键剪枝/头位移线性([0,1]方块单位)/旋转表pinned(实机调校点)
├── client/render/
│   ├── LivingFarmlandSeedDecoratorTest.java   # 种子图标装饰器守卫·普通/未种植/已种植/非耕地
│   ├── LivingPistonOverlayTest.java           # 活活塞叠加层准入与取数·仅活活塞/组件方向/背包槽按container身份判/缓存双路路由含miss/进度键前缀防同号互串（绘制本体不可单测，实机验收）
│   ├── LivingToolModelRendererAnimTest.java   # 活工具动画时钟·转圈(速度/回绕/大时钟量级不退化)+脉冲(首见冻结起点·同步延迟折算·陈旧不重播·gameTime抖动不影响进度)+过窗剪枝（2026-10-29 换源回归）
│   ├── LivingBrewingStandDecoratorTest.java  # 酿造白幕·守卫(原版/非活/未酿造不画)+堆叠时长折算(400/N·与 stepFor 同源)+比例时间线(clamp)+动画起点(首次记/播完不删/新批次重播/跨存档清)
│   ├── ProgressBarDecoratorTest.java          # 通用进度条·守卫(null/比例≤0 不画)+横竖几何(黑底/彩条·原版 Math.round)+燃料刻度(1 次=1px)+越界 clamp
│   ├── LivingItemTooltipTest.java             # 活物品段标题恒在（2026-10-07 定稿）·零内容行仍输出标题/不适用功能同样保留/真实活桶只有标题无内容行/有内容行则空行+标题+行/多功能标题只一次且行序保持
│   └── BeamColorSectionsTest.java             # 光束颜色分段·无玻璃单段白/玻璃纯色/空气加高/同色累计/异色平均/不透明截断/自定义默认色 + 默认白色完整 ARGB/实际原版内芯顶点 alpha=255、外晕=32（无观测/无玻璃/红玻璃/白玻璃）
├── client/render/tlm/
│   ├── SoulSlabGuiModelWrapperTest.java       # 活魂符GUI/HUD模型握手·真实栈identity+槽/光标/HUD pop矩阵双匹配/复制栈不claim/TLM原override/null语义/GUI空模型claim/非GUI与过期token/多位置隔离
│   ├── SoulSlabGuiPoseTest.java               # 活魂符GUI/HUD矩阵·容器槽guiLeft/guiTop+Z100/光标现成Z232/HUD保留当前pop变换/物品中心Z150/翻Y/不污染调用方pose
│   ├── SoulSlabGuiSlotsTest.java              # 槽位准入与光标分类·普通槽稳定引用/StackCopySlot退化；hover只认显示Slot identity；真实carried identity命中并拒绝snapback
│   ├── SoulSlabHotbarSlotsTest.java           # HUD快捷栏归属·seed1-9映射玩家0-8/seed10映射副手40，均只认真实栈identity；null/空栈/副本/越界seed拒绝
│   ├── SoulSlabFeedFeedbackTest.java          # 投喂反馈单调观察·首次只建基线/增长只触发一次/下降重置/非法负值归零
│   ├── SoulSlabFeedPulseTest.java             # 投喂6tick缩放曲线·未激活为1/首尾归1/中点峰值1.12
│   ├── SoulSlabAmbientScheduleTest.java       # Mob环境音后增计数·初值0/命中重置-120/负冷却不触发/隐藏复位
│   ├── SoulSlabEnvironmentSoundTest.java      # 查看者环境选声·跨日晨夜取模/严格边界/雨雪冷热优先级/概率回退
│   ├── SoulSlabBooleanFeedbackTest.java       # sitting同步边沿·首次只建基线/双向变化各触发一次/重复值静默
│   ├── SoulSlabVoiceArbiterTest.java          # 单枚语音仲裁·投喂>回应>待机/低优先级不覆盖/实例间无全局状态（含十枚同时请求）/清理丢请求
│   ├── SoulSlabMaidSettingsTest.java          # maid_info个人设置·气泡/语音频率默认值、保存值恢复、错型回退与0-1钳位
│   ├── SoulSlabPreviewBubblesTest.java        # 图片气泡判定·可见/开关/已有气泡/原检查间隔与UUID相位/图片权重/零总权重/颜文字分支跳过，真实原气泡绘制实机验收
│   ├── SoulSlabPreviewFacingTest.java         # 活魂符展示角度·初始正面/四向身体/停步保朝向/光标模式回正/最短插值/头部限幅/背侧投影/实例隔离
│   ├── SoulSlabPreviewMovesTest.java          # 身份迁移·引用优先/稳定快照排除项/槽↔光标/同快照交换/HUD20px逻辑邻接/副手只凭引用/歧义不共享
│   ├── SoulSlabPreviewScaleTest.java          # 活魂符预览缩放公式·1.0还原旧8倍/默认1.25得10倍/TLM模型因子×可用高度×投喂脉冲各乘一次/非法输入拒绝
│   ├── SoulSlabPreviewSurfaceTest.java        # 预览表面仲裁·仅无容器屏时HUD持帧/任意跨表面SETTLE/同表面同位NONE、GUI槽异位MOVE、HUD/光标异位SETTLE
│   └── SoulSlabPreviewVisibilityTest.java     # 活魂符预览可见帧·未绘制哨兵不因int溢出误判/当前至前2tick可见/更旧与tick极值不可见
├── client/util/
│   └── LivingChestTabSearchTest.java          # 活箱子标签页搜索过滤纯函数·空搜索词原样返回(不清空栈)/非空词剔除空栈不改入参/中文名直配/拼音(全拼·首字母·部分)/英文名大小写/注册ID兜底/空物品+非空词⇒匹配(原守卫分支语义·实际路径不可达,钉住防漂)
├── living/api/
│   ├── LivingItemRegistrationTest.java        # 重复ID先拒绝且零变更/合法注册失效缓存/异常与安装失败恢复原实例顺序及缓存视图
│   ├── ComponentOwnershipTest.java            # DataComponent 归属守卫·每组件必有主/不可重复归属/自声明会被清除/信标配置取消活化即清
│   ├── ActivationHookTest.java                # 活化时机钩子守卫·派发参数原样送达/顺序不变量(钩子内仍带 IS_LIVING)/未认领物品不派发/数据安全否决/箱子无玩家拒绝且内容保住/末影箱不绑定且照常解绑/owner 只由活工具钩子写
│   ├── DroppedItemSupportTest.java            # 掉落物形态准入判据（真机「丢在地上的活 TNT 不 tick、不炸」的回归）·活 TNT ⇒ 支持/活工具 ⇒ 支持（既有形态不能回退）/未声明功能（活箱子）⇒ 不支持/非活物品 ⇒ 不支持（独立作用域夹具，结束恢复正式表）
│   ├── LivingItemActivationTest.java          # 活化门面守卫·零配置全放行/显式开启才拒绝/取消总允许/探针不改原物品
│   ├── ActivationRuleConfigTest.java          # 活化规则 JSON 加载语义·via缺省=player/activate与deactivate独立/先命中先赢/白名单/坏值跳过（含指令侧增删改与持久化往返；tag 路径 @Disabled —— 单测环境不加载 item tags，已游戏内验证通过）
│   └── TickOrderTest.java                     # 功能列表顺序守卫·默认优先级全为0（零行为变化的证据）/稳定排序/优先级生效/只读且非降序（不守容器内功能执行序，见 api-contract §1.3）
├── living/command/
│   ├── ActivationTargetParsingTest.java       # 活化目标参数解析守卫·物品ID/标签/modid 回写契约 + 反向守卫「StringArgumentType 读不进 : # @」
│   └── LivingItemContainerCommandTest.java     # inspect 实际入口·学习值替代独立推断 / 活对象列数与注册规则分列 / 末行向上取整 / 未开战利品明确不处理 / 大箱另一半同源规范化
├── living/compat/
│   ├── tlm/
│   │   ├── IdentityRedemptionQueueTest.java   # TLM好感兑换队列·对象identity而非equals/重复保留首条/drain先清再回调/停服清强引用
│   │   └── PendingMaidFavorTest.java          # maid_info待兑现好感·严格TAG_Int/未知NBT保留/总上限384/长整型防溢出/一次性取出
│   └── iris/
│       └── ModIrisTest.java                   # Iris 光影门面守卫·无 Iris 时 tagBeamSource 原样返回同一实例（几何不经包装）/探测链路在有无 FML 的环境下都不抛异常（渲染主循环不容忍 LinkageError）
├── living/components/
│   └── ContainerUuidsTest.java                # 容器 UUID 读写门面（2026-10-22，活背包）·懒创建幂等（跨 GUI 开合进度不重置）/ofItem 无副作用/复制 remove 范式给副本新身份/strip 空栈与 null 安全/宿主侧 null 安全/keyOf 与 containerUuidOf 互逆且 kindOf=ITEM_FORM/cuuid_ 不被误认世界方块 + **discard 剥 UUID 且删数据/退化输入安全** + **哨兵（全零 UUID）翻译成 null/懒创建写真身份可回读**
├── living/container/
│   ├── ContainerComponentCommitTest.java       # 原数量组件提交·原引用/副本/旧值重验/非空同物同量/无writer拒绝/无抽插截断/快照隔离/拒写与部分恢复/双BE通知/真实revision脏槽/实体前置槽映射
│   ├── ContainerMoveStackTest.java             # **I-C12 一步整栈移动 + 安全撤销**·整栈不拆/超大栈一次搬完（抽屉 512）/目标被占拒绝/整栈放不进拒绝（不搬一部分）/源空/地址非法 + **抽取权限第三维**（只出不进的槽 ⇒ SOURCE_NOT_EXTRACTABLE）/后端无 setStackInSlot ⇒ Unsupported 非 BackendViolation + **撤销两槽一起**（只恢复源会复制）/撤销前校验（第三方改动 ⇒ 不覆盖）/撤销后守恒 + canAcceptAll 纯预检
│   ├── ContainerSlotOpsTest.java              # **槽位数量操作原语**（2026-11-05，I-C12 契约化）·move 六场景 × 双容器实现（整栈守恒/同类合并/部分交付差额留源/异类 TARGET_OCCUPIED/满 TARGET_INSUFFICIENT/undo 恢复合并前）/PHYSICAL=A3（不问准入与抽取权限、容量只问槽位上限、500→64 搬 64 留 436）/违约三态（源写回静默失败 ⇒ 撤销目标防复制、重定向还原、无精确写回退路径可用）/produce 差额留痕不回滚 + 超槽位上限/consume 抽取权限 + 写回违约按「没扣到」/transform 失败还原 + 无变化不写（负向验证三例见类注释）·**swap 原语**（2026-11-12）双非空整栈互换/movedSlots 两槽登记/双向抽取与准入/装不下不部分交换/静默写与重定向恢复双槽/组件保全/无撤销凭据
│   ├── EnderChestContextTest.java              # 末影箱锚点口径（2026-10-27 定案「存放处」D-container-18）·getAnchor 必须显式为 null（默认实现经 getOwnerPlayer 退化成 AtPlayer ⇒ K2 当玩家宿主上报 ⇒ 两端不同源）/ 非物品形态 ⇒ hostAnchor 恒 null、hostSlot=-1
│   ├── ContainerLayoutResolverTest.java        # 共同布局链·子类禁止替换流程/实例先于内建/规则学习家族启发式顺序/多宿主规则优先/首容器回落/家族子类/现取与结果复用/掉落物同链
│   ├── ContainerMenuInfosTest.java             # 当前菜单宿主信息·空容器/背包/状态分离/来源同源/真实无MenuType驴菜单反查/无类型方块与未知菜单分开/末影箱固定布局及改容量不误套
│   ├── ItemFormContainerContextTest.java       # 物品形态容器地基（2026-10-22，活背包）·来源注册口（无来源不认领/注册后可认领/find 返回同一来源/空栈与普通物品不认领）+ ctx 身份（UUID 与 cuuid_ 键互逆/hosts 空/无世界位置）+ **persist 写 SavedData（不是空转的 default）/空值删条目/非 isSaved 不写** + **菜单反查（无活容器 null/唯一一件认它/两件不猜/未注册 null）**
│   ├── ItemFormSlotSyncTest.java               # 物品形态槽位直发通道（2026-10-31，活水车在背包 GUI 转向不刷新的回归）·脏槽刷新发给开该 GUI 的观众（containerId/菜单槽号/组件值对）+ **同号槽挂别的 handler（升级槽替身）不冒领** + 观众快照 remoteSlots 同步跟上/开别的背包或 GUI 关着不收包/无观众不抛
│   ├── ContainerDataKeyTest.java               # 落盘判据 + persist 默认路径（2026-10-22，D-container-13）·savedWith 的 attachment+codec 都有⇒isSaved/persistent 无附件⇒不落盘/tick 级两者都不/persist 写所有宿主/非 isSaved 一个都不写/宿主空不抛
│   ├── ContainerDataSavedDataTest.java         # SavedData 持久层（2026-10-22，活背包）·put→get 往返/save→load 跨重启/空条目不写盘/isSaved=false 拒绝写入/remove(UUID) 只删一个/同 key 后写覆盖/未知 UUID 返回 null/组件注册
│   ├── SimpleContainerContextTest.java        # 容器上下文脏槽同步 + 末影箱稳定键构造器（回归）+ 缓存键推导（世界容器带维度前缀/跨维度同坐标键不同/大箱子两段坐标/背包末影箱裸键/无维度退化）+ 列数推断（启发式/质数退化/未注册类型不借用同 namespace 规则/**Tier 0 来源权威值赢启发式（放置态精妙背包 30 槽→5 列，2026-10-24）/来源不认领⇒照旧回落启发式**）
│   ├── ContainerContextsTest.java             # 边界带共享内核·isSameSlotSpace(槽位数不一致/越界/同空/同物品/一空一非空/异物品) + resolve 末影箱分支(F-1) + isViewingEnderChest 判据
│   ├── EntityContainerProviderTest.java       # 扩展实体宿主·factory与逐槽归属同源/可写范围/负号与隐藏槽/混合菜单只同步存储区与物理槽别名，保留原准入
│   ├── EntityContainersTest.java              # 实体宿主白名单/UUID身份/数据宿主/槽位与鞍偏移/遥测物理键/菜单实体反查/原版车船类型+容量固定布局/非原版或扩容不借固定值/羊驼实例列数/摘箱空存储固定1列（真实驴菜单入口另由 ContainerMenuInfosTest 守卫，移除旧accessor恒真占位）·**三通道过槽位映射**（2026-10-27 审查 P2-1，I-C4/I-C10）：getSlotLimit/isItemValid/simulateInsertItem 曾直接当物理槽 ⇒ 鞍了鞍的驴第一箱格永远写不进（判别测试：鞍位禁插+首槽限流的自定义 handler）·**1.7.0 崩服回归**（2026-11-05）：实体容器准入改「形状白名单 + `ItemHandler.ENTITY` 能力面」双闸，第三方 `ContainerEntity` 无能力 ⇒ 准入跳过 / 其菜单槽 ⇒ `resolveMenuEntity` 为 null（打开 Lootr 矿车 GUI 不炸）/ 有能力面仍照常纳入 / `ContainerContexts.resolve` 不落到位置分支误判成同坐标 BE；构造即抛保留为最后防线
│   ├── ContainerBoundaryContractTest.java     # 容器边界契约 I-C3/I-C4/I-C7/**I-C11** 守卫：SlotIgnoringHandler(insertItem 丢 slot) + ExtractBlockedHandler(extract 被拦) ⇒ 写回落对槽/物品守恒/模拟真实同源（S2 后转绿 **+ I-C11：四种容器形态的列数都 ≥ 1（掉落物形态 = 1，它原先白嫖接口默认 9）**

│   ├── GameplayConfigTest.java                 # 玩法开关的默认值（2026-10-24）·spec 能构建/**四个宿主锚点开关默认全关**（TNT 无锚点熄灭/不发电/不挖矿/不开光）（⚠️ 只测未加载配置时的静态字段，不测 ModConfigSpec 本身）
│   ├── TlmClientConfigTest.java                # TLM客户端配置·spec/专属文件名/未加载默认1.25/0.25-4.0边界钳位/NaN与无穷回退默认/缓存热刷新不重建消费者
│   ├── ContainerAnchorTest.java                # 容器世界锚点（2026-10-23 收编 4 份重复解析）·方块⇒AtBlock/玩家⇒AtPlayer(靠 getOwnerPlayer)/**实体容器⇒AtEntity（必须覆写，否则退化成没锚点）**/**掉落物⇒AtEntity（有实体，不是没锚点）**/物品形态⇒null/**getHostAnchor 与 getAnchor 刻意分开（物品形态有宿主值、非物品形态恒 null）**/roughPosition 三分支/退化不抛
│   ├── ContainerViewersTest.java               # 「谁在看这个容器」唯一判定入口（2026-10-23 收编）·背包只发主人不门控(隐私红线)/末影箱开着 GUI 才发(第③次泄漏修复)/物品形态按菜单锚点认观众(开着别的容器不算)/位置为空⇒无观众/退化输入不抛（末影箱门控两条用例从流体轨与运行时轨合并迁入）
│   ├── ContainerHandoffTest.java              # I-C9 **第三挂点「双向交接」**（2026-10-21，D-container-12）·宿主→物品：捕获→交接搬到目标/无捕获 no-op/空栈不搬且丢捕获/capture(null) 清残留/一个 Mover 抛异常不连累其它/discard + **物品→宿主：暂存→恢复调 moveIn / 暂存是副本（源栈 shrink 后仍能恢复）/无暂存或宿主空 no-op/stageRestore(EMPTY) 清残留** + **反射守卫：Mover 不得有 default 方法**（真机三连失败的真凶）
│   ├── MenuSlotOwnersTest.java                # I-C5 守卫：菜单槽→容器位置解析·**核心不反射第三方方法**(菜单自带 getBlockPosition 也不认,D-container-09)/注册联动解析器后生效/无解析器返回 null/模组容器槽回落菜单位置/原版 BE 槽直取/大箱 CompoundContainer 两半位置(防跨容器虚影)/空位置集恒 false
│   ├── SophisticatedMenuResolverTest.java        # D-container-09 守卫：包名匹配+getBlockPosition → 解出位置；包名不匹配**即使有同名方法也不认**（反射不扩散）（替身 FakeBackpackMenu 故意放在对方包名下）
│   ├── SophisticatedBackpackSourceTest.java      # 活背包 #6 菜单解析·**两个活背包也认菜单锚定的那个**（真机「倒不了」的回归）/锚定物未活化⇒null/非背包菜单⇒null/**锚点读不到⇒退回默认扫描（单背包仍认得出）**/反查与 tick 拿同一内容视图/**权威列数 30槽÷6行=5（启发式给10，错）/27÷3=9/除不尽⇒0**/**放置态同一套算法（BackpackBlockEntity 同形替身）/别人的 BE 不认领⇒0**（替身 BackpackContainer/BackpackContext/IBackpackWrapper/BackpackWrapper/BackpackBlockEntity 均在对方包名下，BackpackItem 用 mock 无构造实例化 + asItem/components 双桩）
│   ├── AvaritiaMenuResolverTest.java             # D-container-09 守卫（无尽箱子）：包名匹配+getTile()返回方块实体 → 解出位置；包名不匹配**即使有同名方法也不认**；返回类型非 BE 不认；无 getter 返回 null（替身 FakeInfinityChestMenu 放在对方包名下）
│   ├── ContainerNeighborsTest.java            # 四邻取数·fillNeighbors 与 getNeighbors 逐格一致（含边界）/ 单行容器 / 复用缓冲不污染
│   ├── PlayerFluidDataPersistenceTest.java    # 玩家路径落盘（CONTAINER_FLUID_DATA_PLAYER）·背包与末影箱各有源写回 / 变空条目移除 / 重进不复活 / 两容器键互不干扰 / 重进从附件回填
│   ├── ContainerChunkCacheChunkLoadTest.java  # 区块加载守卫·事件不碰世界/延后重扫不丢/限量/不主动加载/只处理ticking区/可观测性/每tick维护清理不依赖可处理区块/重扫隔离（能力查询抛异常保守保留，其余 pending 继续）；单次 Load 未就绪重试 / 队列公平性 / 卸载撤销与再加载 / 能力查询期间卸载重入
│   ├── ContainerUuidsDiscardTest.java         # 取消活化**彻底清理**（2026-10-28）·discard 必须清内存层容器数据（旧实现只删 SavedData ⇒ 物品形态无位置可反查 ⇒ 熔炉进度留到停服）/ UUID 被剥掉（重新活化拿新身份）
│   ├── ItemFormLiveViewsTest.java             # 物品形态容器的按 UUID 寻址（2026-10-28，活末影箱路由取货）·登记后可按 UUID 取同一活视图 / 未登记或清空后 null（停服清空、背包没在 tick）/ null 输入安全 / **无人查询也要能收：evictExpired 主动淘汰过期条目（2026-11-01 R-06，惰性 find 回收的补集）**
│   ├── ContainerCacheSweepTest.java           # **跨 tick 缓存的统一回收口**（R-06；2026-11-06退役旧类型心跳清理）·真实processContext连续3600次：海绵EDGE仅真实存在性翻转通知/活物品-only索引保留·查询创建五入口登记且查询不续期/空与修订-only回收/真实tick续期/子数据清空保索引·显式删除清时间记录且sweep不重复/同键重建/32轮创建删除无泄漏·消失阈值/返回窗口/双箱任一半/无ctx盖章/removeSlot空壳
│   ├── ContainerKeysTest.java                 # 容器键解析唯一入口(2026-10-13)·5 种键形各自归位(背包/末影箱前缀相同必须按后缀分开)/缓存键剥维度前缀/未知形态返回 null 不抛异常/ownerOf 末影箱回归(旧实现抛异常被吞⇒从不发包)/isOwnedByPlayer(null 安全)/positionsOf(大箱两段·维度 id 含下划线取末三段)/维度前缀
│   ├── ContainerRuntimeCacheSlotMappingTest.java  # 读写槽位键对称（2026-10-27 审查 P1，熔炉/酿造台/漏斗域）·update 过 toPhysicalSlot、get 不过 ⇒ 马/驴容器（storageOffset=1）读永远 miss ⇒ 熔炉每 tick 烧 1 燃料零产出、酿造台每 tick 空耗烈焰粉 / get(ctx, logical) 对称重载：带偏移上下文读回自己写的值 / 裸 get(key,3) miss·物理槽 4 命中 / 恒等映射两版等价
│   ├── ContainerPersistRoutingTest.java      # 容器级数据**持久层二分**（2026-10-29）·有身份⇒只写 SavedData（附件一次不写）/ 无身份⇒只写附件 / 回填优先级：内存空 + 有身份 ⇒ 从 SavedData 取回
│   ├── ContainerRuntimeCacheLifecycleTest.java # 运行时缓存生命周期(方案A)·物品离开槽位/换物品⇒登记作废·同类持续⇒不作废·drain 取走即空·null 键忽略·整容器作废键==运行时存储键
│   ├── ContainerTickHooksTest.java            # tick 钩子注册表（2026-10-29）·同类去重 / 两阶段按注册顺序 / ctx+tick 透传 / 默认空实现
│   └── ContainerTickIsolationTest.java        # tick 异常隔离（2026-10-30，R-01）·RuntimeException 不向外抛且可重复调用 / 取 key 本身抛异常仍隔离 / Error 不被吞
├── living/domain/maid/
│   ├── LivingSoulSlabClockTest.java           # 概率活跃度I·初始7/成功失败±1/1-49钳位/非法值
│   ├── LivingSoulSlabFunctionTest.java        # 每秒1/I判定·随机四向不重掷/坐下零随机零写且被动位移保坐姿/空槽移动/普通物品整栈换位（装不下失败）/居家餐吃一份换位进入（N=1全吃/N>1剩堆换位）/吃失败换回/活食物与满好感挡路不重掷/技术中性/换槽保I/超堆叠不拆
│   ├── LivingSoulSlabSittingTest.java         # sitting组件·Codec/StreamCodec双态往返/非bool拒绝/toggle true写组件 false移除/Function恰好owned clock+sitting
│   ├── SoulSlabSitHandlerTest.java            # 坐下handler·有效魂符toggle并setChanged+broadcast/非活多件普通物品与缺快照零副作用
│   └── LivingSoulSlabTooltipTest.java         # 活魂符tooltip·I=1/7/49与非法回退/1位小数/pending零隐藏与+N/坐下休息行取代概率/非活或多件不显示/只读不改栈/双语措辞和占位符
├── living/domain/ender/
│   ├── EnderChannelRegistryDimTest.java        # 活末影箱路由表**维度隔离**（2026-10-27 审查待拍板 #2，2026-10-28 修）·跨维度同坐标同槽位都能注册（去重键含维度，旧实现误判重复 ⇒ 第二条注册不进去）/ 按位置+槽位清理只删指定维度 / 区块卸载不删另一维度同坐标路由 / `validateRoutes` 不误删另一维度同键路由
│   ├── EnderRouteExtractTest.java              # 路由**端到端提取**（2026-10-28 链路回归；2026-10-29 口径改为「源解析不到 ⇒ 条目丢弃」）·方块容器按位置取能力并扣源 / 玩家背包按 UUID 找玩家 / **物品形态按容器 UUID 取活视图（新能力）** / 活视图未登记 ⇒ 不取且**条目丢弃**（路由只有注册/不注册两态）/ 方块被搬走 ⇒ 丢弃且频道清空 / **物品形态源 rollback 回活视图（持有提取时 handler，活视图清掉也能回滚）**
│   ├── EnderRouteManagerSourceTest.java        # 路由源可寻址判据（2026-10-28）·方块容器恒可 / 玩家背包（player_）可（提取端按 UUID 反查）/ 物品形态（cuuid_）**活视图已登记才可**（靠 `ItemFormLiveViews`，背包不 tick 时取不到）/ 未登记 ⇒ 不可（注册必丢）/ 未知键形放行（保守）
│   ├── LivingEnderChestAccessorTest.java       # 直连模式接缝（2026-10-29）·tryCreate 只认活末影箱 / 插入改栈 / 模拟不写 / 抽取后回滚回原槽（原槽被异物品占据 ⇒ 只报告，不写其它槽）/ 跳过活物品 / isEmpty+isFull / 离线降级 / 路由模式 insert 返回 0 + markTransferred 不登记
│   ├── EnderDualFormRouteTest.java             # 双形态不变量（2026-10-29）·方块↔物品交替 20 次每步只剩当前形态 / 旧形态在别的频道也清 / 别的 UUID 合法条目不误删
│   ├── EnderRouteMaintenanceTest.java          # 路由回收单钩子（2026-11-01）·注册方槽位按 RouteRegistrar 能力发现判离场（漏斗域零 import）/ 末影箱离场空键表清理 / 写回钩子从 TickContext 自取槽位端到端
│   └── EnderRouteManagerNeighborUuidTest.java  # 邻居源 UUID（2026-10-29）·取邻居 BE 的 ContainerUuids.ofHost / 无身份为 null，不拿注册者顶替
├── living/domain/backpack/
│   ├── LivingBackpackFunctionTest.java        # 活背包（2026-10-22）·认领（无来源不认领=未装模组零影响/来源注册后只认它说是容器的物品）+ 功能 ID 稳定 + **取消活化连 SavedData 一起清**/无 UUID 不抛
│   ├── LivingBackpackTickTest.java            # 活背包 tick 的三道闸（2026-10-24 终审补，此前 tick 零测试）·**宿主锚点传进内层 ctx**（AtBlock 同坐标 / 宿主无锚点⇒null）/深度闸（背包里的背包不再处理）/**逐条目隔离**（一个背包的来源抛异常不带走同批）（⚠️ 环检测在 MAX_DEPTH=1 下不可达，故意不测；探针功能只认 SHULKER_SHELL，避免与既有「无功能」断言串味）
│   └── LivingBackpackTooltipTest.java         # 活背包 tooltip 的容器 ID 行（2026-10-26）·短码固定 8 位（**前导零不能丢，否则 substring 越界**）/未活化不输出空 ID 行/高级提示才给完整 UUID + 重新活化提示/**复制品短码必须相同**/剥掉后重新生成换新身份
├── living/domain/tools/
│   ├── LivingToolHostBlockTest.java              # 活工具「别挖自己容器」黑名单与射线起点**同源**（2026-10-25，TODO 6.2）·背包在箱子里+开关开⇒黑名单含宿主那一格（旧实现位置列表恒空⇒射线第一个穿到的格子就是那只箱子：记过类型⇒停住没反应 / 没记⇒挖自己的箱子）/ 开关关⇒不借锚点⇒黑名单空 / 方块容器保留自己的两格（回归）/ 玩家背包与背包在玩家身上⇒无方块可跳过 / 方块容器不受开关影响（⚠️ Inventory.player 是 public final 字段，Mockito 桩不了⇒反射设值，同 Level.random）+ **实体宿主（2026-11-12，D-container-25）**·实体容器 origin=眼睛位置（开关关/开都非 null = 不设门）/ K2 上报：实体容器⇒ofEntity(uuid,-1)、物品形态借用路径不变（箱内 ofBlock / 实体宿主内 ofEntity 带 hostSlot）
│   ├── LivingToolHostSyncDedupTest.java      # K2 去重键口径（2026-10-26）·**动画状态必须算变化**（只有 progress / 只有 action / 只有 digTicks 不同 ⇒ 判有变化，否则方块容器「有模型、不转圈」）/ 内容全同⇒无变化 / **耐久不进键**（多比一个就刷包）（两个方向都钉）
│   ├── LivingToolHostSyncDimensionTest.java  # K2 跨维度铁律（2026-10-27 审查）·PLAYER 宿主上报在玩家循环（维度循环之前）⇒ **登记表不得在 flush 里抽干**：两维度背包宿主各收各的（旧实现首维度抽干全表 ⇒ 地狱/末地条目全丢）/ 方块宿主**显式按上报维度隔离**（不再依赖抽干顺序）/ beginTick 清残留（取走背包⇒发空包清客户端）/ 稳态零发包（flush 只读去重仍生效）（替身结构同 BeaconPlayerHostSyncTest）
│   ├── LivingToolAssistTest.java             # 辅助挖掘**速度分层**（2026-10-27 用户定，真机翻车那层的回归）·顶层相加+背包只取最高（3+4+9=16，背包另一把 5 不加）/ 空列表⇒0 / 只有背包条目⇒取最高（含客户端 S2C 补的条目）
│   ├── LivingWeaponAssistTest.java           # 活武器辅助的**实体宿主触发判据**（2026-11-12，D-container-25）·准入实体宿主⇒触发（临时 Provider）/ 无准入实体 / ServerPlayer（含 FakePlayer 结构上落不进实体判据）/ null ⇒ 不触发；出手循环（无敌帧压制/直连目标/写回）依赖 ServerLevel+FakePlayer 链 ⇒ 实机清单
│   ├── CarriedLivingItemsTest.java              # 「随身携带」判据（2026-10-27，物品形态）·顶层工具收（innerSlot=-1）/ 已活化容器展开一层（outerSlot=宿主格、innerSlot=内容格）/ **未活化容器不展开**（活化门槛即性能闸门）/ skipMainHand（主手工具不收）/**skipMainHand 仍展开主手背包内容**（原版计入的是手持工具不是手持容器的内容；连展开一起跳 ⇒ 手持背包两端不同源）/ **槽位顺序**（容器条目紧跟宿主槽位，E6 掉落归属取最靠前）/ **写回容器走 setStackInSlot**（否则背包里的工具白干活不掉耐久）/**客户端 S2C 补的条目仍算容器内**（否则两端分层不一致 ⇒ 方块破坏又恢复）/ 写回守卫（目标格被换走⇒放弃不覆盖 / 槽位越界⇒不抛）
│   └── LivingToolMemoryTest.java                # 工具记忆（2026-10-29）·射线/攻击 offset 逐位保留 + 宿主起点回放 / EntityType 匹配 / with/without 只动目标字段 / Codec + StreamCodec 往返
├── living/domain/tnt/
│   ├── ExplosionComponentCountTest.java       # TNT 计数/清空守恒·两满槽 long 求和不回绕且统计不消耗/只清活TNT/无活TNT计数0/非法或不可表示半径及非服务端入口拒绝时保留物品
│   ├── TntRedstoneIgniteTest.java             # 红石信号 → 点燃（2026-10-28，审查测试缺口 #5）·信号 > 0 ⇒ 点燃并写回 / 无信号不点燃（只钉 TNT 侧分支，传播由 `ContainerRedstoneDataTest` 覆盖）
│   ├── IgniteCarriedHandlerTest.java          # 光标持 TNT 右键打火石（2026-10-28，缺口 #2 另一半）·创造活 TNT ⇒ 点燃 + 广播 / 创造空光标拒绝 / 创造未活化 TNT 拒绝 / 生存未活化打火石拒绝 / 生存空光标拒绝（拒绝分支用 `broadcastChanges` 未被调用兜住）
│   ├── TntIgniteHandlerTest.java              # 打火石点燃交互（2026-10-28，审查测试缺口 #2）·创造空光标信任客户端 / 创造活打火石 / 创造错物品拒绝 / **生存空光标拒绝（抓出「空手点燃」真 bug）** / 生存未活化打火石拒绝
│   ├── LivingTntConsumeTest.java              # 爆炸后必须消耗·单颗/多颗槽位清空且不复活/五满int槽真实引爆只存SUPER参数与epoch无位图/掉落物在原地爆炸/宿主锚点默认关熄灭、打开消耗/熄灭后移进有锚点箱子不自爆
│   ├── ExplosionParamsTest.java               # 爆炸参数·位图映射可逆与越界/有限正参数准入/自定义半径不钳制/受检面积与索引算术/巨型SUPER几何不依赖位图/宽区块边界不回绕/AABB∩球体与切线包含/负小数中心既有转换
│   ├── ExplosionLedgerTest.java               # 普通位图账本·相交才进队列/圆外标记/卸载丢弃与自然加载补炸/分帧预算/混合模式重放水印/处理中登记保留/满额拒绝/位图存档往返
│   ├── ExplosionSuperLedgerTest.java          # SUPER 参数与纪元·巨型无位图/保存恢复与重载防重放/新爆炸再破坏/固定旧水印与混合顺序/异常不提前提交/同坐标重入/FIFO等待与32坐标预算/只逐出旧SUPER/降级原READY身份与无未来加载/运行态清理/真实holder就绪查询/附件Codec往返
│   ├── ExplosionBlockEntityCleanupTest.java   # 批量爆炸BE清理·Mixin accessor真实接线/pending-only DUMMY不实例化/live走原版移除链/HIGH仅清实际炸毁位置/SUPER并集清理且pending-only不误判no-op
│   ├── ExplosionComponentHeightmapTest.java   # 真实高度图·SUPER四图归最低高度/不同世界高度/空section旧图修复/HIGH删除顶面与幸存地面/水树叶判据/未改列保留/packed NBT往返与实际发包前更新/单区块perf量测
│   └── ExplosionComponentLightTest.java       # 爆炸后光照刷新·四入口顺序/天光柱高图先于重算/section 空态/sectionY≠索引/发光方块减光
├── living/domain/redstone/
│   ├── RedstoneHandlerSetChangedTest.java     # 交互 handler 就地改写标脏（2026-10-27 审查 P2，与 IgniteHandler P3-4 同类）·拉杆切换成功 ⇒ setChanged 被调，否则开关态在「改完→下次落盘」窗口丢失（四 handler 同构逐一接线）
│   ├── ContainerRedstoneBoundaryTest.java     # 边界采样自反馈守卫（2026-10-27 审查 P1）·双箱两半共用账本 ⇒ 朝另一半查到 this 曾把自己对面 face 输出读回对侧输入（东边拉杆隔空压灭西边火把）/ 邻居是自己 ⇒ 保持世界信号 / 无账本·无朝向邻居 ⇒ 原样（mockStatic 邻居查询）
│   ├── ContainerRedstoneDataTest.java         # 红石信号传播引擎·直接驱动 calculate/**空容器(存储格 0)⇒ 直接跳过，不拿 0 当除数**
│   ├── LivingObserverTest.java                # 活侦测器v2·正面相邻槽/无关槽负向/registry ID+数量+活化身份/未知组件不触发/红石与活塞本体公开状态+最终出边/统一结算后采样/2gt×2/忙碌期严格丢弃/改向与拆合及账本重建只建基线/正面越界/多个侦测器顺序无关/movedSlots 功能索引刷新/公开标签深拷贝
│   └── ContainerRedstoneIntegrationTest.java  # 红石**驱动链路**端到端（走真实 processContext）·自维持注册/守卫放行消费者/守卫拦截无关容器(零开销)/残留归零/prio 顺序
├── living/domain/piston/
│   ├── LivingPistonDataTest.java                # 活塞方向组件·四向值相等/Codec与StreamCodec往返/无效方向拒绝/仅存方向/默认向上且写默认移组件/独立归属声明
│   ├── LivingPistonFunctionTest.java            # 真实但未全局注册的活塞tick适配·信号推拉/状态保持重试/改向不补拉/当前方向数量重读/客户端跳过/runtime物理槽映射与保留其它片段/稳态不重复标脏/候选不假交付/双粘性争抢/侦测公开状态仅 direction+extended
│   ├── LivingPistonTooltipTest.java             # 活塞HasDirection与tooltip·更新判变/无效输入零改动/方向名数组隔离/普通粘性标题与推力/按当前方向读取runtime/中英文键
│   └── LivingPistonIntegrationTest.java         # 活活塞端到端（真实processContext）·普通推拉/持有收回/双活塞/取消活化/链长超限 + N+1活漏斗q=min(C,M,A)/q0重试/送空后方不动/路由末影拒绝不伸出且零登记/部分压送后后续功能与容器级阶段读当前栈且runtime不失效
│   ├── PistonSegmentTest.java                  # 活塞runtime线格式·四向伸缩状态往返/既有同步包携带新片段/全局功能未启用守卫/注册表测试隔离恢复
│   ├── PistonChainResolverTest.java             # 活塞只读有界解析·四方向值相等/N货物+N+1接收候选/漏斗本体不提前接管/空格截断/边缘与不完整末行/零宽拒绝/计划列表不可变
│   ├── PistonActionsTest.java                   # 活塞调用真实moveStack/undo·整链一格/整栈组件与超大数量守恒/拒收后逆序撤销/违约与undo失败留痕/候选不假交付/粘性争同源或同目标先成功得整栈
│   └── PistonControllerTest.java                # 活塞机械决策·成功保持/失败重试/普通断电收回/粘性拉失败仍收回/改向复位不补拉/方向按值比较/数量不参与运行态有效性/缓存丢失允许有电新周期
├── living/domain/power/
│   ├── SignalTrackerRestartTest.java          # A5 停机重启语义（2026-10-28 定案）·上升沿间隔超失活窗口 ⇒ 旧周期作废、恢复沿只当新起点 / 第二个沿重新锁相 / 正常改频（未超窗口）EMA 照常跟随 / 快照回填续接不受误伤
│   ├── PhaseDataMigrationChestSplitTest.java  # A6 拆半清相位（2026-10-28 定案）·双箱拆半 ⇒ 存活半相位快照附件清为 EMPTY（旧锁相不得经回填复活）/ 单箱·非箱子不触发
│   ├── ChannelStateAmplitudeRatchetTest 附录（在 ChannelStateTest 内）# 幅度棘轮（2026-10-27 审查 P1-1，I-E6 下行缩放）·maxDelta 曾只升不降 ⇒ 先高堆叠后降堆叠时冻结历史峰值、发电量长期虚高（「一次高堆叠永久收割」套利）/ 跨 tick 改 latest 覆盖：幅度下行 effDeltaSum 回落 / 同 tick 多事件仍取 max（I-B3 不变）
│   ├── PhaseDataMigrationTest.java            # 相位形态迁移（2026-10-21 补，此前零测试）·moveOut 只对 contentRetaining 搬/非 retaining 不搬/EMPTY 不搬 + moveIn 无组件 noop/**无 ctx 降级也写附件**
│   ├── PowerMathTest.java                     # 发电数学
│   ├── ContainerPowerDataTest.java            # 相位质量状态机
│   ├── AccountingGateTest.java                # 跳变门控记账（零产出/冻结/去重/中性化）
│   ├── PhaseInterpretationTest.java           # 相位解读三元件（移相/链/环自熄/死源/裂相/加法）
│   ├── ChannelStateTest.java                  # 相位域偏移活性
│   ├── CoilGroupingTest.java                  # 形态识别 + 单通道
│   ├── NetworkResonanceTest.java              # 铜块网络共振
│   ├── NetworkTraversalTest.java              # 铜块网络遍历（含 E2E 真实传播）
│   ├── WaxedCopperStorageTest.java            # 储能（含telemetry）·整FE精确交付/超大堆叠/跨堆凑整/不足1FE不动/损耗界；一次规划扫描＋提交核验、抽够提前停；写回后实际槽与单次回调（含返回0的已扣前缀）
│   ├── BulbItemEnergyStorageTest.java         # 铜灯通用电池
│   ├── ContainerEnergyCapabilityTest.java     # 对外能量能力·存在性/边沿/让位/客户端只读探针（Jade取数闸门，零容量且不可充放）；BLOCK原引用与副本写回/只读与拒写/模拟零副作用/比例充电/确认前缀ceil与floor报账及WARN/超大堆叠组件/双箱全部宿主标脏
│   ├── PhaseSnapshotWarmupTest.java           # 相位快照热身
│   ├── RoundTripConservationIT.java           # 往返守恒与拉/推侧账目·精确交付/实扣≥交付/均分损耗有界（对接面模拟，不替代跨模组实机）
│   ├── WaxedGeneratorFeedChainTest.java      # 发电机喂链
│   ├── WaxedCopperOscillatorIT.java           # 振荡器→发电全链路集成
│   └── WaxedCopperCouplingIT.java             # 耦合链集成：多跳中继+防回环
├── living/domain/chest/
│   ├── LivingChestFunctionTest.java           # 取消活化堆叠倍数返还
│   ├── LivingChestInsertByteGateTest.java     # 16KB 字节上限插入点精确把关（2026-10-27 审查 P1）·三参 insert 曾直接丢弃 registries 转调估算（每槽 64B ⇒ 27 槽 1728B 永远够不到上限）/ 精确把关：20 本成书(≈1KB/本)+空槽 ⇒ 三参拒绝且内容不动 / 未超限正常收 / **两参入口也精确**（RegistryAccess.EMPTY 回落 BuiltInRegistries ⇒ 超限拒绝，2026-10-29 收口）/ **回滚豁免**（insertItemUnchecked：撤销不是新插入）
│   ├── LivingChestItemHandlerTest.java        # 能力面接缝（2026-10-29）·全额插入余量为空且箱子只收 1 / 部分插入余量精确 / 字节超限 insert+simulate 同源拒绝 / 抽出不挡
│   └── LivingChestAccessorTest.java           # 访问器接缝（2026-10-29）·tryCreate 只认活箱子 / insert 未超限全额且改栈 / 字节超限 insert+simulate 同源 0 / 回滚豁免 / 满箱子回滚失败只报日志 / 抽取快照门 / markTransferred+sync 接线
├── living/domain/beacon/
│   ├── BeaconFunctionTest.java                 # 宿主判据(协议级 LidBlockEntity)·原版箱/末影箱⇒true/木桶/潜影盒⇒false/null 安全
│   ├── BeaconPyramidTest.java                 # 1D 金字塔判级·各级精确目标/全0/null不足长/差一块掉档/多堆不亏/嵌套单调/L3 缺一列仍 L2/边界钳制封顶/单列无法激活/距下一级差额/范围公式/合计 9·34·83·164
│   ├── BeaconHostDispatchTest.java               # 信标宿主分派与取点**同源**（2026-10-25，TODO 6.2）·背包在箱子里+开关开⇒分派拿到宿主箱子锚点（旧写法问 getBlockPos/getOwnerPlayer 双 null⇒有 buff 没光）/ 背包在玩家身上⇒玩家锚点 / 开关关⇒无宿主 / 方块容器与玩家背包各用自己的锚点（回归）（⚠️ 只测 effectiveAnchor 决策点 —— tick 全链路的金字塔等级靠 BEACON_BASE_BLOCKS 标签，单测环境不加载⇒等级恒 0⇒宿主分支进不去，由 BeaconBaseBlocks 注释与游戏内验证兜住）+ **实体宿主（2026-11-12，D-container-25）**·实体容器锚点 AtEntity（开关关也非 null = 不设门）/ 取点 = 实体位置
│   ├── BeaconColumnSlotsTest.java             # 下方 9 列槽位解析(纯数学)·居中/右边界钳制/左边界钳制/最底行全 -1/顶行居中/单行容器
│   ├── BeaconSegmentTest.java                 # 遥测段编解码往返(等级+9柱高+列存在位图，⚠️再生不进遥测)/id=beacon 线格式契约/equals 按数组内容比(稳态不脏写前提,仅位图不同也判不等)
│   ├── BeaconPanelGeometryTest.java           # 9 柱面板几何纯函数·满程 24=L4 中心契约/柱位 x/刻度 y 四档/柱顶钳位/缺口高度/数字取舍(目标值·达标不画·溢出绝对高)/reachable 可达性(含列存在)/columnExistsFrom
│   ├── BeaconTooltipComponentTest.java        # 面板数据载体 from(BeaconRuntime) 字段直通+record equals
│   ├── BeaconTooltipTest.java                 # tooltip 文本层（2026-11-08）·条目三态靠行色(生效整行青/休眠整行暗灰+红后缀含数字/遥测缺失不染色)+休眠两因是条目末位 arg+满级再生授予行落账本末尾且待喂入菜单之前(＋不占编号·效果名取原版键)+非满级不画/满级恒 II(加倍·满配都不影响)/L0 折叠/满级空配置照授予+状态行分母=承载位 min(堆叠数,5)(堆10配5⇒5/5·堆5配3⇒3/5·堆2配5⇒2/2·L1⇒2/5)+lang 双语键存在且占位符数==递归采样参数个数+渲染级逐字符取色(注入 Language，守「§ 不跨占位符」⇒颜色必须真落到参数文本)
│   ├── BeaconHostPacketTest.java              # S2C 宿主包往返(维度+位置+等级+容器玻璃色列表)/空列表
│   ├── BeaconChestHostTest.java               # 多枚每枚一条·键格式(容器#槽位)/全同条目去重/两枚开一次/取一枚不关盖/全走才关/20tick 节流/关后重开即发/S2C 每枚一条/内容不变不重发
│   ├── BeaconPlayerHostSyncTest.java          # 跨维度回归·两维度各自只发本维度/beginTick 清残留/只发别人(自己本机渲染)
│   ├── BeaconEntityHostSyncTest.java          # 实体宿主联机广播回归（2026-11-12，D-container-25）·维度隔离（getEntity 本维度解析得到/其它维度 null⇒不发）/ 同实体多枚取最高（等级+玻璃色同源）/ beginTick 清残留（发空表清客户端）/ 半径外观察者只可能收空表（替身结构同 BeaconPlayerHostSyncTest）
│   ├── BeaconGlassColorTest.java             # 容器玻璃取色·16 色玻璃/玻璃板/DyeColor 口径/非玻璃与空⇒null/同列扫描(自下而上·非玻璃跳过·顶行越界)
│   ├── BeaconEffectsTest.java                 # 效果纯逻辑·喂锭门控(档位/II 仅 L4/5 槽满/未激活全拒)+生效数 min(堆叠,档内槽数)取前 N+buff 时长(9+等级×2)×20（⚠️原「再生三档 5 案例」随 2026-11-08 满级无条件再生 II 整体删除）
│   ├── BeaconConfigTest.java                  # 配置组件·add/amplify 不可变+5 槽封顶+contains+编解码往返(含空配置)
│   ├── BeaconFeedHandlerTest.java             # 喂锭 handler 可测部分·规则谓词(5 矿石锭通过/非矿石拒绝)+OreType 映射(锭↔效果↔档位，2026-11-07 钻石=抗性 L2/下界合金=力量 L3)+槽位解析列宽取自 context(12 列≠9 列)/size 越界（⚠️ 柱高数值依赖 tag，单测环境不加载 ⇒ 游戏内验证）
│   └── BeaconPlayerHostPacketTest.java        # 玩家宿主包往返(维度+UUID+等级+容器玻璃色列表)/空列表
├── living/domain/farmland/
│   ├── BonemealHandlerTest.java               # 活骨粉 GUI 交互信任边界（2026-10-27 审查 P3）·创造模式曾连「光标是不是活骨粉」都不验 ⇒ 改包客户端空手刷生长（同域口径=照验免扣）/ 创造+骨粉放行免扣 / 生存扣 1 / 普通骨粉拒绝（活化门槛）（pin 手法：未成熟植株 forceGrowthTick 不碰 level ⇒ age+1 即通过判据）
│   ├── CropClassifierTest.java               # 作物分类器·火把花/瓶子草/柱状段回归
│   ├── LivingFarmlandFunctionTest.java      # 输出合并回归·部分合并丢物品守卫
│   ├── LivingFarmlandMoistureTest.java      # 湿润 BFS 与生长冷却门（2026-10-28，审查测试缺口 #4）·水源相邻 4 级→沿耕地每跳 -1（0→9→18 全湿、远处干燥）/ 普通耕地阻断传播 / 冷却门 <200t 不写时间戳 / ≥200t 推进时间戳且概率失败不改 age（`computeMoisture` 私有 ⇒ 走完整 tick + 假 FluidPresence）
│   ├── TillablesTest.java                   # 耕作知识表·锄头判定+可耕映射+真值表
│   ├── FertilizeTransferTest.java           # 自动施肥·骨粉→活耕地零空转守卫
│   └── LivingFarmlandPlacementTest.java     # 放置回世界·落点/幼苗/客户端/异常不冒泡+端到端接线
├── living/domain/brewing/
│   ├── BrewingBatchLogicTest.java             # 批次状态机·启动/完成/作废两判据/堆叠倍率/批次标记生命周期(Start 写·Continue 保持·Complete·Abort·**Idle 自愈**)
│   ├── BrewingSlotEligibilityTest.java        # 槽位准入隔离规则·活物品不作瓶子/材料/燃料 + **I-C13 操作单元**·槽内 >1 瓶整槽不酿 / 单元是常量非容器推导
│   ├── BrewingMaterialConsumeTest.java        # 材料消耗(consumeOne)·⭐龙息不整栈替换回归/空栈变玻璃瓶/并入未满堆/容器满丢弃/空槽 no-op
│   └── LivingBrewingStandDataTest.java        # 数据组件 codec·旧数据(只有direction)可解码/往返保留 fuel 与 brew_start_tick/withBrewStart 隔离改字段/默认即移除锚点
├── living/domain/furnace/
│   ├── FurnaceOutputGateTest.java             # 输出槽判据同源 + 配方缓存（2026-10-27 审查 P1-2/P2-3）·预检曾只比物品 ID、写入走 isSameItemSameComponents ⇒ 输出槽放改名铁锭/活铁锭时进度卡 isComplete、燃料无限烧零产出（原版 canBurn 组件级不点燃）/ 预置燃烧态端到端：组件不同 ⇒ 进度回退不混写；输出空 ⇒ 完成即产出 / 无配方也进缓存（曾只认 cachedResult==1 ⇒ 非熔炼物品每 tick 重查）（transform 真身=运行时缓存，别读组件）
│   ├── LivingFurnaceFunctionTest.java         # 燃料消耗合成残留物语义
│   └── FurnaceBurningFlagTest.java            # 燃烧标志组件·图标切换回归
├── living/domain/hopper/
│   ├── HopperFilterSyncTest.java              # 漏斗过滤链回写·黑白名单展示回归
│   ├── CrossContainerTransferFertilizeTest.java # 跨容器施肥·推送/拉取双入口+活骨粉三入口全拒
│   ├── CrossContainerTransferFaceSelectionTest.java # 多方块容器跨容器面选取·候选排序+推送首选/回退 + 机械压送复用候选面/真实q/无facing安全拒绝
│   ├── HopperMechanicalReceiverTest.java       # 活漏斗机械压送·q=min(C,M,A)/错输入面/锁定冷却原样/黑名单/活容器本体不展开/目标漏斗不成物品管道/输出回源拒绝/末影直连在线·离线·部分容量/路由q=0且不登记
│   └── CrossContainerTransferContainerLikeTest.java # 容器类活物品走通用跨容器路径（2026-11-01 删活箱子拉取特支）·拉取进 CONTAINER 组件箱子本体不动 / 推送隔离门 isEligibleCargo 唯一定义点：活骨粉拒、活箱子放行推内部
├── living/domain/map/
│   ├── TeleportHelperYTest.java               # 落点站立面语义 + 指哪传哪（2026-10-27 审查 P1-1 + A3 定案）·ChunkAccess.getHeight 返回最高阻挡方块自身 Y，destY 曾少 +1 ⇒ 玩家脚落进最高阻挡方块 / 端到端 mock：teleportTo 捕获 (格中心, 71.0, 格中心) / **虚空柱照传不拒**：全空柱高度图返回世界下限，脚下 = 下限+1，不因无地面拒绝
│   ├── MapUpdateSkipHelperTest.java           # 地图刷新节流器的**跨存档**不变量（2026-11-01）·传送后 SKIP_TICKS 内跳过 / 窗口满自动放行并删记录 / **切存档后 tick 从 0 重来时不得把新存档永久锁死**（旧 `cooldowns=500040` vs 新 `currentTick=10` ⇒ `currentTick < expireTick` 恒真）/ 停服清理已登记（`StaticCacheRegistry.describe()` 可断言，防「写了 clear() 没人调」）/ 下线玩家记录被丢掉
│   ├── MapCoordHelperMatchTest.java           # 组件条目与命中装饰的同一处匹配（2026-10-27 审查 P2-2）·Entry.x() 是 double 世界坐标、Decoration.x() 是 byte 像素，曾直接相比恒 false ⇒ 兜底恒传组件里第一条（多条目地图点哪个都被劫持）/ 类型+换算像素一致才用精确值 / 无匹配回退像素反推 / 换算互逆
│   └── MapCoordHelperTest.java                # 地图坐标换算
├── living/domain/water/
│   ├── ContainerDataMigrationChestSplitTest.java  # A6 拆半清流体源（2026-10-28 定案）·otherChestHalf 判定（左半⇒右半 / 单箱·非箱子 null）/ 拆半 ⇒ 存活半流体附件清为 EMPTY（旧源不得经附件回填复活）
│   ├── ContainerFluidConservationTest.java    # **流体搬运守恒**（2026-11-01）·空槽路径不丢溢出（抽屉 500 → 普通槽 64 ⇒ 只搬 64、源留 436）/ 合并按**槽位上限**而非物品上限（缺陷 A2：目标上限 16 已有 10 ⇒ 只进 6）/ 目标已满不搬 / 异类占位不覆盖；**不断言准入**（A3 由 ContainerFluidDataTest 钉）
│   ├── ContainerFluidDataTest.java            # 流体引擎行为快照·单源扩散/上限7/活物阻挡/非活物穿过/源移除/水流推动/二维扩散 + 行为接缝·按maxLevel/静止不扩散 + TickContext 建流体数据回归 + 通用驱动·自维持/驱动BFS + 源查询API + 派生源·独立存活/挤没/共存/同格无豁免/异种覆盖/EMPTY noop + 落盘CODEC往返 + 晋升接缝 + 转化接缝 + **孤儿源对账（2026-11-07）**·源网络删源同拍撤源身份/降级不制造空档/水贴邻不出黑曜石/合法源反向守卫（旧代码2项红）
│   ├── ContainerDataMigrationTest.java          # 形态迁移·破坏时数据交给「本体那件」掉落物(**守卫已下沉测 ContainerDrops**)/无本体退回首项/无掉落返回 null + 组件 CODEC 往返保住派生源/空数据不产条目 + snapshot 独立副本/clearAll 一次清干净 + **相位快照 CODEC 往返保住 (P,φ,d)/EMPTY 不进物品** + **moveOut 共用入口**（contentRetaining 搬+清空 / 非 retaining 不搬 / 退化输入安全）+ **端到端 moveOut 写物品组件（非空快照）/ moveIn 写回宿主附件** + **身份优先：有 UUID 只搬身份、不写数据组件（moveOut / moveIn 各一）**
│   ├── ContainerFluidIntegrationTest.java     # 流体端到端（走真实 processContext）·预置派生源+驱动跑BFS / BE 写回 EMPTY / 驱动进自维持清单 / **能力存在性边沿·精确失效一次（无源不通知/出现源通知/不变不重复/抽干再通知）**
│   ├── FluidTransformTableTest.java           # 流体转化表 JSON 语义·内置装载/玩家差异(覆盖/removed)/坏文件跳过/坏条目跳过/缩容等待/活物品过滤/output_potion（含 output 必须为药水类物品）
│   ├── FluidFlowClientCacheTest.java           # 客户端快照分桶不变量（2026-10-06 第 ③ 次泄漏修复）·ENDER/BLOCK 目标路由 / PLAYER_INV 不翻转非背包组提示 / clear() 三桶全空并复位
│   ├── FluidFlowServerSyncTest.java           # 流体快照同步边沿·数据清空恰好一次清屏/从未激活不发/反复汲倒重新武装；真实单箱稳定每拍发送 / 关闭发空→同坐标新 BE 和新菜单恢复非空 / 无 viewer 后重开且不发给其它玩家；关界面物品形态只问一次、不进逐槽循环
│   ├── LivingBucketFunctionTest.java          # 活桶零私有状态·判定(BucketItem家族通吃)/内容=原版content/形态变换 getBucket 映射/同形态零新对象
│   ├── LivingBucketInteractSupportTest.java   # 倒桶源格替换（A 档对齐原版）·契约 default false（未覆写流体保守拒绝）/ 水⇄岩浆对称替换 + 同种不构成 / 倒桶判定四例
│   ├── BucketInteractRealMenuTest.java        # 倒/汲的**真实原版菜单**路径（2026-10-28，缺口 #6）·真实 `BrewingStandMenu` + 真实 BE 能解析出容器（假菜单测不到）/ 倒水后桶排空 / 汲水后桶装水且源消失（脚手架=真实菜单+BE+capability 替身）
│   ├── ContainerStressDataTest.java           # 水车力矩纯函数（2026-10-31）·直流水无力矩（叉积 0）/ 侧向水 CCW / 堆叠 × 数量 / 无水为 0 —— 抽成纯函数后不再依赖 create:water_wheel 物品
│   ├── ContainerStressWritebackTest.java      # 水车注入锚点双路径（2026-11-12 实体宿主兼容）·实体容器用自己的位置（开关关也非空 = 不设门）/ 开关开不变 / 物品形态开关关 null / 开关开借宿主锚点（D-container-16 原样）
│   └── ContainerFluidPerfTest.java            # 性能量测（@Tag("perf")，默认不跑；`-Pperf` 才计入。200 容器×54 格×200 拍，打印基线 + 病态回归宽松阈值 + 落盘 build/_perf.txt）·引擎 tick / 空容器
├── living/domain/sponge/
│   ├── LivingSpongeDataTest.java              # 活海绵组件形状契约（2026-11-01 第一段）·干/湿不变量归一（无流体或非正存量 ⇒ 干态，幽灵湿海绵在类型层面不可能）/ 容量与总量 long 域（每枚 6.4e7 mmB，×count 线性）/ CODEC 湿·干·岩浆往返 / 未知流体 id 归干态（I-C6 同款宁损勿鬼）/ wither 不可变
│   ├── LivingSpongeAbsorbTest.java            # 吸收行为契约（2026-11-02 第二段，直接驱动 + processContext 冒烟）·一拍吸净可吸四邻（≤4 源）/ 节拍门 gameTime 锁相 / 异种邻居跳过不挡 / 空海绵方向序取首流体 / 快满停拍+腾出恢复 / count 均分损耗有界 / 同拍双海绵账本即时删不双吃 / 斜对角非邻居（27 槽 3 行）/ 首口定流体单次构造
│   └── SpongeFluidRoundTripTest.java          # **守恒主战场**（2026-11-02 第三段，规则 12 红线：不许为了让它过而改它）·附录算例（6mB×7 枚）/ 拆分守恒恒等式 / 整桶往返 ×25 零漂移 / 50 轮部分充排净迁移无凭空产生 / 溢出拒收不越上限 / 异种拒绝零副作用 / 零负请求 / 尘量取不出但可累积 / 一堆一罐语义
│   ├── SpongeContainerCapabilityTest.java     # 容器级能力·存在性/客户端零罐只读探针（Jade取数闸门）/槽序罐/填充/边沿·多堆剩余预算与混种/代表流体/模拟·BLOCK副本计算与原槽提交/原引用和副本后端/拒写不报账/只读不抽插/第二槽失败报先前提交量/超大堆叠与无关组件保留
│   └── LivingSpongeTooltipTest.java           # tooltip 堆口径（2026-11-06 回归）·分子分母同乘 count（旧实现分母写死枚容量 ⇒「128000 / 64000 mB」）/ 与 getFluidInTank·getTankCapacity 逐字一致 / 半满余量 / 干态无内容行
├── living/interaction/
│   ├── InteractionRegistryTest.java           # 两趟优先级匹配·通配遮蔽+triggerFilter 回归守卫
│   ├── InteractionRuleConfigTest.java         # 交互规则 JSON 加载语义·内置全有效/玩家覆盖与removed/坏值跳过/未知字段忽略/version守卫/reload幂等（自带 mock handler 不依赖测试执行顺序）
│   └── TillToFarmlandCompatTest.java          # 活锄头跨模组兼容·模组锄头命中+处理器产物回归
├── living/transfer/
│   ├── ContainerCompatibilityConfigTest.java  # 容器布局推断 + 原版家族表数值（箱9/漏5/发3/投3/熔炉族1/酿造台1，2026-11-12 起为原版唯一来源）+ 内置规则形状（ironchest）+ 多档容量共存（程序化注册：9 列档与 12 列档）
│   ├── ContainerLayoutLearnerTest.java        # 布局学习形状/身份/玩家槽过滤 + 菜单入口四门禁独立守卫（归属混合、重复/负数/越界/断号、局部视图、真实熔炉非行优先）+ 规则阻止学习 + 菜单冲突/歧义跨重启 + 普通与歧义保存失败重试 + 矛盾磁盘输入以歧义为准 + 学习优先家族且规则仍优先学习（事件用 ServerPlayer，准入用真 BE，单测静态态清理）

│   ├── ContainerRuleConfigTest.java           # 玩家差异持久化·覆盖内置+删除不复活+导出全量快照+社区闭环+多档共存/旧格式删除记录不被抹掉/内置规则含无尽箱子300格25列/contentRetaining 内置声明与玩家追加（内置夹具 = ironchest:iron_chest，2026-11-12 起原版/精妙条目不再内置）
│   ├── SlotInteractionCargoGateTest.java      # 槽位交互货物准入真值表·活骨粉不施肥
│   ├── FilteredSlotAccessorTest.java          # 过滤装饰器接缝（2026-10-29）·黑名单抽取回滚 / 模拟不回滚 / 插入拒绝不达下层 / 放行透传 / unwrap / null+EMPTY 全放行
│   ├── NeighborSlotAccessorTest.java          # 邻居访问器接缝（2026-10-29）·抽取/模拟/插入改栈 / 堆叠部分插入余量 / 回滚只回原槽（异物品/满槽只报日志）/ isFull 取 min(槽位上限,物品上限) / getter
│   ├── SlotAccessorTransferConservationTest.java # 传输守恒不变量·boolean委托实际q/诚实全额部分/模拟谎报/insert少报或多报均以栈余量为权威/全拒回滚/随机300组守恒
│   ├── MechanicalReceiversTest.java          # 中立机械接收端口·按类去重/纯matches角色探测/首匹配独占/请求透传与q钳制/活塞解析非漏斗接收者/活箱子工具本体不展开/部分交付/拒抽/精确写扣减/插入违约恢复/余量与超大栈回滚
│   ├── PlainSlotAccessorTest.java             # 普通槽回滚契约·空槽/同组件合并/异物品或满槽只报告；同item异组件模拟与真实均拒绝
│   └── TransferTargetPolicyDispatchTest.java  # 传输目标策略分发（2026-11-01）·目标无接管 → 源侧探测+CONTINUE / REJECT 不触发探测（对齐旧 resolveTarget 时序）/ 接管路径不触发探测 / 跨容器 null=无接管回落
├── living/perf/
│   └── PerfMetricsTntTest.java                 # SUPER聚合指标·content/metadata/heightmap/no-op互斥分类/pending清理量/命中率与省包省光照汇总/清零后不跨报告窗口或存档残留
├── living/util/
│   ├── BroadcastDedupTest.java                # 定向广播去重表的跨维度合并（2026-10-23 定，10-26 抽成共享工具）·**其它维度玩家的记录必须保留**（逐维度 flush 不能互清，旧写法每玩家每 tick 重发一包）/离线玩家淘汰/本维度新记录覆盖旧值（四个同步器共用一份实现，故只留这一份测试）
│   ├── FailureThrottleTest.java               # tick 异常限频报告（2026-10-30，R-01）·同 key 窗口内只记一次其余计数 / 不同 key 各记一次 / 超窗口再报并重置计数 / key 上限 LRU 淘汰 / 汇总窗口推进
│   └── PerItemLedgerTest.java                 # 均分记账内核（2026-11-02，power/sponge 两域共享）·floor 均分记账=写入×count（同源）/ 不足整步不写 / 容量防御截断 / 已满不动 / 退化输入 / 损耗 ≤ count−1（设计稿 §3.1；灯泡行为不变由 RoundTripConservationIT 全量回归守）
└── network/
    ├── ContainerMenuInfoPacketTest.java         # 菜单信息载荷·全形态/来源codec/状态独立/request关联/服务端限频/错菜单拒绝/实际回包/退出清理/几何有效性
    ├── GuiInteractionPacketTest.java          # 目标槽判据·空槽可解析（活桶汲/倒包的回归守卫）/活物品可解析/非活物品不可解析
    └── LivingItemSyncPacketTest.java          # 运行时同步包编解码往返（档 2 安全网）·三组字段往返/空数据/多槽混合/两种键形态/容器种类 kind 往返(背包·末影箱·世界·掉落物)/子记录哨兵/未登记 id 报错
    └── LivingItemRuntimeSyncTest.java         # （门控判定已迁 ContainerViewersTest）+ **物品形态容器按菜单锚点发包（真机 tooltip 不显示容器信息的回归）**
    └── ServerPacketHandlerInventorySafetyTest.java  # 满背包不丢物（2026-10-27 审查 P0）·配方书 Shift 取出活箱子物品曾直接丢栈 ⇒ 背包满时静默销毁 / 满 ⇒ 余量掉落（Player#drop 原版入口，落副本+清零）/ 空 ⇒ 全收不掉 / 部分空间 ⇒ 合并后余量掉落
    └── GuiInteractionTargetGuardTest.java      # GUI 交互包服务端目标身份复验（2026-10-27 审查 P3-2）·isAcceptable 只验「是活物品」⇒ 改过的客户端可对任意活物品发 lever_toggle 写 LIVING_LEVER_DATA / 守卫与规则 JSON 同源（走同一份 matchesTarget：条目说什么守卫放行什么）/ 未知 actionId 放行 / 空目标放行（活桶通道在各自 handler 重验）（⚠️ 判据用 item 型 target——tag 在单测环境不绑）·**同 actionId 多条目=任一命中**（till_to_farmland 5 条 target：曾首条 return 误杀其余 4 条，耕活泥土进阶链断裂——本条是 2026-10-27 审查修复自身引入又当场修掉的回归）
```

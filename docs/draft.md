2. ⏱️ Tick 优化：惰性 Tick（借鉴 AE2 / Carpet Mod）
现状：ContainerChunkCache 缓存了含容器的区块，但每个 tick 仍会遍历所有缓存区块中的所有方块实体，即使容器中没有活物品。

AE2 的做法：只有当存储内容变化时才触发 tick，空闲时完全不 tick。

Carpet Mod 的做法：lazyChunkLoading 选项，只在区块有玩家时才处理。

改进建议：

给 ContainerChunkCache 添加"活跃容器"标记——只有包含活物品的容器才加入活跃列表
容器中活物品被移走后自动从活跃列表移除
这比扫描所有缓存区块的方块实体高效得多

3. 📋 配方缓存（借鉴 Mekanism / FastWorkbench）
现状：ItemTransformComponent 每次 canProcess 都调用 RecipeManager.getRecipeFor() 查询配方。

Mekanism 的做法：缓存最近使用的配方，输入物品不变时直接复用缓存。

FastWorkbench 的做法：使用 RecipeCache 避免重复查询。

改进建议：在 ItemTransformComponent 的 ComponentState 中缓存上次匹配的配方 ID，当输入物品不变时跳过查询。这在大量活熔炉同时工作时性能提升显著。

4. 🧹 路由表过期机制（借鉴 AE2 网络存储）
现状：EnderChannelRegistry 的路由条目只在特定事件（移走物品、区块卸载）时清理，没有自动过期。

AE2 的做法：存储单元有"活跃检测"——如果存储长时间没被访问，会自动从网络中移除。

改进建议：

给 EnderChannelEntry 添加时间戳（注册时间/最后访问时间）
定期（如每 600 ticks）扫描并移除超时未访问的路由条目
这可以处理"源容器被其他模组移走但没有触发事件"的边界情况

6. 📊 容器变化检测（借鉴 Create / AE2）
现状：ContainerSnapshot 每 tick 都重新构建，即使容器内容没有变化。

Create 的做法：使用 LazyOptional 和 invalidate 监听器，只在容器实际变化时重新计算。

AE2 的做法：IActionHost 接口，只有当存储内容变化时才通知。

改进建议：

给 ContainerContext 添加 version 计数器
ContainerSnapshot 缓存上次 version，只在 version 变化时重建
ItemFilterComponent 的黑白名单计算也可以利用这个缓存


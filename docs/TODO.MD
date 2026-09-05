<!-- markdownlint-disable -->

活地图：
1.标记显示优化，比如宝藏地图的红叉叉会重叠。
2.✅ 航空学通过绳索连接的结构，传送会有问题。（已修复：传送前检测软连接，检测到绳索或跨 SubLevel 关节连接时拒绝传送并提示，避免只传一端导致物理约束拉爆。详见 SableIntegration.hasSoftConnection）
3.✅ 退出存档重进后，容器里展开的活地图的渲染会消失。（已修复：容器打开时服务端通过 PlayerContainerEvent.Open 主动推送全量 ClientboundMapItemDataPacket，绕过原版只同步玩家背包地图的限制。详见 LivingMapEventHandler.onContainerOpen）
4.和暮色森林兼容性问题，在暮色森林使用活地图或者暮色森林的魔法地图，会导致游戏服务端卡死。我怀疑是暮色森林的魔法地图mixin的问题。

活水车：
1.在航空学载具上，玩家应力无法传输到脚下。

活末影箱：
1.✅ 玩家绑定频道。（v12 已实现：绑定 + 堆叠数 = 频道键 EnderChannelKey，三态 直连/专属频道/公共频道；隔离是命名空间而非权限。详见 docs/tech/living-ender-chest-tech.md §12.9）

活箱子：
1.活箱子内物品参与合成需要先打开活箱子标签页才行，直接打开配方书界面会显示物品无法合成。
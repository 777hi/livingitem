在 Minecraft 1.21.1 NeoForge 中，想要让物品栏里的物品根据 NBT 切换不同的材质图片，**最推荐、性能最好且完全兼容原版机制的方案是：自定义物品属性（ItemPropertyFunction）+ 物品模型 Overrides**。

原版的弓拉弓动画、工具耐久、盾牌状态等，都是通过这套机制实现的，它会自动适配物品栏、箱子、展示框、掉落物等所有物品显示场景。

## 核心实现思路
1. **客户端注册一个自定义物品属性**：读取物品的 NBT 标签，返回对应状态的浮点数值。
2. **在物品的 JSON 模型中编写 overrides**：根据不同的属性值，切换到对应不同纹理的子模型。

---

## 详细实现步骤

### 1. 客户端注册物品属性
这部分是纯客户端逻辑，必须放在客户端侧执行，不能写到公共端代码里。

#### 示例代码（客户端事件类）
```java
package com.yourname.yourmod.client;

import com.yourname.yourmod.YourMod;
import com.yourname.yourmod.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterItemPropertiesEvent;

// 只在客户端加载，监听 MOD 总线事件
@Mod.EventBusSubscriber(
    modid = YourMod.MOD_ID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.MOD
)
public class ClientModEvents {

    @SubscribeEvent
    public static void onRegisterItemProperties(RegisterItemPropertiesEvent event) {
        // 为你的物品注册属性：属性ID为 yourmod:variant
        event.register(
            ModItems.YOUR_ITEM.get(), // 你的物品实例
            ResourceLocation.fromNamespaceAndPath(YourMod.MOD_ID, "variant"),
            (itemStack, level, entity, seed) -> {
                // 安全读取NBT，避免空指针
                if (itemStack.hasTag() && itemStack.getTag().contains("Variant")) {
                    // 将NBT中的整数状态转为float返回
                    return itemStack.getTag().getInt("Variant");
                }
                // 默认状态返回 0
                return 0.0f;
            }
        );
    }
}
```

- 如果你的 NBT 是字符串类型（如 `"type": "fire"`），可以在 lambda 中做字符串到数字的映射：
  ```java
  if (itemStack.hasTag()) {
      String type = itemStack.getTag().getString("VariantType");
      return switch (type) {
          case "fire" -> 1.0f;
          case "ice" -> 2.0f;
          default -> 0.0f;
      };
  }
  return 0.0f;
  ```

### 2. 编写物品模型 JSON
在资源包目录 `src/main/resources/assets/yourmod/models/item/` 下创建物品模型。

#### 主模型（your_item.json）
```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "yourmod:item/your_item_default"
  },
  "overrides": [
    {
      "predicate": {
        "yourmod:variant": 1
      },
      "model": "yourmod:item/your_item_variant1"
    },
    {
      "predicate": {
        "yourmod:variant": 2
      },
      "model": "yourmod:item/your_item_variant2"
    }
  ]
}
```

#### 子模型（每个变体一个）
每个子模型只需要指定不同的纹理即可，结构和普通物品模型一致。

`your_item_variant1.json`：
```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "yourmod:item/your_item_texture1"
  }
}
```

`your_item_variant2.json`：
```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "yourmod:item/your_item_texture2"
  }
}
```

### 3. 放置纹理文件
将对应不同状态的 PNG 图片放到：
`src/main/resources/assets/yourmod/textures/item/`

比如：
- `your_item_default.png`（默认状态）
- `your_item_texture1.png`（状态1）
- `your_item_texture2.png`（状态2）

---

## 测试与验证
进入游戏后，用指令给自己发放带 NBT 的物品，即可看到纹理切换：
```mcfunction
# 默认状态
/give @p yourmod:your_item{Variant:0} 1

# 状态1
/give @p yourmod:your_item{Variant:1} 1

# 状态2
/give @p yourmod:your_item{Variant:2} 1
```

---

## 关键注意事项
1. **客户端侧限制**：属性注册代码必须只在客户端加载，否则服务端启动会报类找不到错误。
2. **NBT 读取安全**：必须先判断 `hasTag()` 再读取标签，防止空物品导致空指针崩溃。
3. **匹配逻辑**：Minecraft 会选择**不大于当前属性值的最大 predicate 值**对应的模型，因此离散状态建议用整数对应。
4. **路径大小写**：资源路径（模型、纹理文件名）是大小写敏感的，必须完全一致。
5. **多状态叠加**：如果需要同时根据多个 NBT 字段切换，可以注册多个属性，并在 `predicate` 中写入多个条件。

---

## 什么时候需要更复杂的方案？
如果你的变体数量极多（几十上百种）、需要动态生成纹理、或者需要动画/特殊渲染效果，再考虑**自定义 BakedModel + 自定义 ItemOverrides** 的方案。但对于单纯切换图片的需求，上面的属性方案是最优解。

需要我补充自定义 BakedModel 的实现方式，或者帮你排查纹理不生效的常见问题吗？
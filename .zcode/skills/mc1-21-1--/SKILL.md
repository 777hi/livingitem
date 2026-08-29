---
name: "mc1-21-1--"
description: "Search Minecraft 1.21.1 + NeoForge merged source code. Invoke when user needs to read vanilla Minecraft or NeoForge source, understand how a vanilla class works, trace method calls, or check NeoForge patches/hooks."
---

# Minecraft 1.21.1 + NeoForge Merged Source

This skill provides access to the merged Minecraft 1.21.1 + NeoForge 21.1.230 source code at `libs/src/neoforge-21.1.230-merged/`.

## When to Invoke

Invoke this skill when:
- User asks to read vanilla Minecraft source code (any `net.minecraft.*` class)
- User asks to read NeoForge source code (any `net.neoforged.neoforge.*` class)
- User wants to understand how a vanilla mechanic works (e.g., "how does hopper transfer work?")
- User needs to trace method calls in vanilla code
- User asks about NeoForge patches, hooks, or events
- User wants to check an existing vanilla class before writing a mixin
- User asks about `IItemHandler`, `Container`, `BaseContainerBlockEntity`, `HopperBlockEntity`, etc.

## Source Structure

```
libs/src/neoforge-21.1.230-merged/
├── net/minecraft/                    # Vanilla Minecraft source (decompiled)
│   ├── world/                        #   World, level, entities, containers, items
│   │   ├── Container.java            #     Container base class
│   │   ├── SimpleContainer.java      #     Simple IItemHandler-backed container
│   │   ├── CompoundContainer.java    #     Double chest container
│   │   ├── entity/                   #     Entity classes
│   │   │   └── player/               #       Player, Inventory
│   │   ├── item/                     #     Item, ItemStack
│   │   ├── level/                    #     Level, BlockGetter
│   │   │   └── block/
│   │   │       └── entity/           #       BlockEntity, HopperBlockEntity, etc.
│   │   └── inventory/                #     Inventory, ContainerListener
│   ├── server/                       #   Server-side logic
│   │   └── level/
│   ├── client/                       #   Client-side rendering, GUI
│   │   ├── Minecraft.java            #     Main client class
│   │   ├── gui/screens/inventory/    #     Container screens
│   │   └── renderer/
│   ├── core/                         #   Core utilities, BlockPos, Direction
│   ├── network/                      #   Network protocol
│   ├── data/                         #   Data generation, tags, recipes, loot tables
│   ├── resources/                    #   Resource management
│   └── util/                         #   Utilities
│
├── net/neoforged/neoforge/           # NeoForge source
│   ├── capabilities/                 #   Capability system (IItemHandler, etc.)
│   │   ├── Capabilities.java         #     Capability constants
│   │   ├── CapabilityRegistry.java   #     Capability registration
│   │   ├── BlockCapability.java      #     Block-level capability
│   │   ├── ItemCapability.java       #     Item-level capability
│   │   └── EntityCapability.java     #     Entity-level capability
│   ├── attachment/                   #   Attachment system (data attachments)
│   │   ├── AttachmentType.java
│   │   └── AttachmentHolder.java
│   ├── items/                        #   Item extensions (DataComponents, etc.)
│   │   └── DataComponents.java
│   ├── event/                        #   NeoForge event bus & events
│   ├── network/                      #   NeoForge networking (payload, etc.)
│   ├── registries/                   #   Registry system (DeferredRegister)
│   ├── server/                       #   Server-side NeoForge hooks
│   ├── client/                       #   Client-side NeoForge (events, rendering)
│   │   └── event/                    #     Client events
│   └── common/                       #   Common utilities
│
├── com/mojang/                       # Mojang libraries (blaze3d, math, etc.)
│   ├── blaze3d/                      #   Rendering engine
│   ├── math/                         #   Math utilities (Matrix4f, Quaternion, etc.)
│   ├── serialization/                #   Codec, DynamicOps
│   └── datafixers/                   #   DataFixerUpper
│
└── assets/                           # Minecraft + NeoForge assets
    ├── minecraft/                     #   Vanilla assets
    └── neoforge/                      #   NeoForge assets
```

## How to Use

1. **Search for a class**: Use `Glob` to find `.java` files by name
   ```
   Glob pattern: "**/HopperBlockEntity.java" in libs/src/neoforge-21.1.230-merged/
   ```

2. **Search for code**: Use `Grep` to find method calls, field references, etc.
   ```
   Grep pattern: "IItemHandler" in libs/src/neoforge-21.1.230-merged/net/
   ```

3. **Read a file**: Use `Read` to read the source code
   ```
   Read: libs/src/neoforge-21.1.230-merged/net/minecraft/world/Container.java
   ```

4. **Ignore `.class` files**: The directory contains both `.java` and `.class` files. Always filter for `.java` when searching.

## Key Files by Domain

### Containers & Inventories (Vanilla)
| File | Path |
|------|------|
| Container (interface) | `net/minecraft/world/Container.java` |
| SimpleContainer | `net/minecraft/world/SimpleContainer.java` |
| CompoundContainer | `net/minecraft/world/CompoundContainer.java` |
| ContainerHelper | `net/minecraft/world/ContainerHelper.java` |
| ContainerListener | `net/minecraft/world/ContainerListener.java` |
| HopperBlockEntity | `net/minecraft/world/level/block/entity/HopperBlockEntity.java` |
| BaseContainerBlockEntity | `net/minecraft/world/level/block/entity/BaseContainerBlockEntity.java` |
| Inventory (player) | `net/minecraft/world/entity/player/Inventory.java` |

### Items (Vanilla)
| File | Path |
|------|------|
| Item | `net/minecraft/world/item/Item.java` |
| ItemStack | `net/minecraft/world/item/ItemStack.java` |
| BlockItem | `net/minecraft/world/item/BlockItem.java` |

### Block Entities (Vanilla)
| File | Path |
|------|------|
| BlockEntity | `net/minecraft/world/level/block/entity/BlockEntity.java` |
| FurnaceBlockEntity | `net/minecraft/world/level/block/entity/FurnaceBlockEntity.java` |
| ChestBlockEntity | `net/minecraft/world/level/block/entity/ChestBlockEntity.java` |
| EnderChestBlockEntity | `net/minecraft/world/level/block/entity/EnderChestBlockEntity.java` |

### NeoForge Capabilities
| File | Path |
|------|------|
| Capabilities | `net/neoforged/neoforge/capabilities/Capabilities.java` |
| CapabilityRegistry | `net/neoforged/neoforge/capabilities/CapabilityRegistry.java` |
| BlockCapability | `net/neoforged/neoforge/capabilities/BlockCapability.java` |
| ItemCapability | `net/neoforged/neoforge/capabilities/ItemCapability.java` |
| RegisterCapabilitiesEvent | `net/neoforged/neoforge/capabilities/RegisterCapabilitiesEvent.java` |

### NeoForge Items & Data Components
| File | Path |
|------|------|
| DataComponents | `net/neoforged/neoforge/items/DataComponents.java` |

### NeoForge Events (Server)
| File | Path |
|------|------|
| TickEvent | `net/neoforged/neoforge/event/TickEvent.java` |
| AttachCapabilitiesEvent | `net/neoforged/neoforge/event/AttachCapabilitiesEvent.java` |

### NeoForge Events (Client)
| File | Path |
|------|------|
| ClientTickEvent | `net/neoforged/neoforge/client/event/ClientTickEvent.java` |
| RenderLevelStageEvent | `net/neoforged/neoforge/client/event/RenderLevelStageEvent.java` |

## Tips

- **Only `.java` files are useful** — the `.class` files are compiled bytecode and can be ignored in searches.
- **Vanilla code has NeoForge patches** — look for `neoforge` or `Forge` in method names within vanilla classes for patch hooks.
- **Use `SearchCodebase`** when you're not sure which file contains the logic you're looking for.
- **Prefer this over NeoForge docs** when you need exact method signatures, implementation details, or field names.
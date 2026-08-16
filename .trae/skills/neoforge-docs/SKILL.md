---
name: "neoforge-docs"
description: "Search NeoForge official documentation for modding APIs, conventions, and best practices. Invoke when user asks about NeoForge APIs, needs to check NeoForge conventions, or wants to look up NeoForge modding patterns."
---

# NeoForge Documentation

This skill provides access to the official NeoForge documentation repository located at `libs/src/Documentation-main/`.

## When to Invoke

Invoke this skill when:
- User asks about any NeoForge API, class, or method
- User wants to know best practices for NeoForge modding
- User asks about items, blocks, inventories, networking, rendering, data storage, etc. in NeoForge
- User asks "how does NeoForge do X?"
- User needs to check NeoForge conventions before implementing something
- User asks about `IItemHandler`, `IItemHandlerModifiable`, `ResourceHandler`, capability system, etc.

## Documentation Structure

The NeoForge docs are a Docusaurus site with markdown files under `docs/`:

```
libs/src/Documentation-main/
├── docs/                          # Main documentation (1.21.x)
│   ├── gettingstarted/            # Getting started, mod files, structuring, versioning
│   ├── concepts/                  # Events, registries, sides
│   ├── items/                     # Items, data components, tools, armor, consumables
│   ├── blocks/                    # Blocks, block states
│   ├── blockentities/             # Block entities, BER
│   ├── inventories/               # Containers, menus, capabilities, transactions
│   ├── datastorage/               # Attachments, codecs, NBT, saved data
│   ├── networking/                # Payload, stream codecs, configuration tasks
│   ├── rendering/                 # Screens, particles, features
│   ├── resources/                 # Client/server resources
│   │   ├── client/                # Models, textures, sounds, i18n
│   │   └── server/                # Recipes, loot tables, advancements, tags, data maps
│   ├── entities/                  # Entities, attributes, renderers
│   ├── worldgen/                  # Biome modifiers
│   ├── advanced/                  # Access transformers, extensible enums, feature flags
│   └── misc/                      # Config, key mappings, game test, update checker
├── versioned_docs/                # Older version docs (1.20.4, etc.)
└── toolchain/                     # Build toolchain docs (dependencies, parchment)
```

## How to Use

1. **Read the relevant `.md` file** directly from `libs/src/Documentation-main/docs/`
2. **Search across files** with `Grep` when unsure which file covers a topic
3. **Prefer `docs/` (1.21.x)** over `versioned_docs/` unless user specifically asks about older versions

### Key Files by Topic

| Topic | File |
|-------|------|
| Items (basic) | `docs/items/index.md` |
| Data Components | `docs/items/datacomponents.md` |
| Item Interactions | `docs/items/interactions.md` |
| Blocks | `docs/blocks/index.md` |
| Block Entities | `docs/blockentities/index.md` |
| Inventories / Capabilities | `docs/inventories/capabilities.md` |
| Containers | `docs/inventories/container.md` |
| Menus | `docs/inventories/menus.md` |
| Transactions | `docs/inventories/transactions.md` |
| Networking | `docs/networking/index.md` |
| Payload | `docs/networking/payload.md` |
| Stream Codecs | `docs/networking/streamcodecs.md` |
| Events | `docs/concepts/events.md` |
| Registries | `docs/concepts/registries.md` |
| Sides | `docs/concepts/sides.md` |
| Attachments | `docs/datastorage/attachments.md` |
| NBT | `docs/datastorage/nbt.md` |
| Codecs | `docs/datastorage/codecs.md` |
| Screen Rendering | `docs/rendering/screens.md` |
| Recipes | `docs/resources/server/recipes/index.md` |
| Tags | `docs/resources/server/tags.md` |
| Config | `docs/misc/config.md` |
| Game Test | `docs/misc/gametest.md` |
| Getting Started | `docs/gettingstarted/index.md` |
| Mod Files | `docs/gettingstarted/modfiles.md` |
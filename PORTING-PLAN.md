# Lost Cities 9.4.2 (MC 1.21.11 / NeoForge) → Fabric (MC 26.2) Porting Plan

Assessment produced 2026-07-24 from branch `1.21.11_neo`.

**Headline finding:** This mod is unusually port-friendly. It has **no mixins and no coremods**, **no third-party mod dependencies** in the main source, worldgen is done through vanilla **`Feature`** objects (not a custom `ChunkGenerator`), and the code already uses the renamed **`net.minecraft.resources.Identifier`** class (the 26.2 rename). The loader coupling is small, concentrated, and mostly confined to one package (`setup/`) plus a handful of scattered call sites.

## 1. Project layout & build system

| Item | Value |
|---|---|
| Source sets | `src/main/java` (184 `.java`), `src/api/java` (1 `.java`), `src/main/resources`, `src/generated/resources` |
| Total Java files | **185** |
| Root package | `mcjty.lostcities.*` |
| Build plugin | NeoGradle userdev `net.neoforged.gradle.userdev` v7.1.4 (`build.gradle:17`) |
| Mappings | Official Mojang + Parchment `2024.12.07` |
| Java | 21 (target must move to 25 for 26.2) |
| MC / Neo versions | `minecraft_version=1.21.11`, `neo_version=21.11.38-beta` |
| Shared build logic | `gradletools.gradle` (repos()/at()/runs()/jars()/mc() helpers) |
| API jar | `apiJar` task packages `mcjty/lostcities/api/**` |

LOC dominated by loader-agnostic worldgen: `LostCityTerrainFeature.java` (2332), `BuildingInfo.java` (2001), `NoiseChunkOpt.java` (962). Total ~22.8k LOC.
`src/api/java/ivorius/reccomplex/dimensions/DimensionDictionary.java` is a vestigial legacy stub.

## 2. NeoForge API inventory

**21 of 184 main files** import `net.neoforged.*`. Subsystems:

- **Mod init**: `LostCities.java` (`@Mod`, ctor with ModContainer/IEventBus/Dist; listeners for construct/setup/IMC/payloads/datapack registries/datagen). `ModSetup.init(FMLCommonSetupEvent)`, `ClientSetup.init(FMLClientSetupEvent)` register game-bus handlers.
- **Config**: `setup/Config.java` — three `ModConfigSpec` (CLIENT/COMMON/SERVER), simple types only. `FMLPaths.CONFIGDIR` in `LostCities.java:43`, `config/ProfileSetup.java:446`.
- **Networking**: 2 payloads (`network/PacketRequestProfile`, `PacketReturnProfileToClient`) — vanilla `CustomPacketPayload` + `StreamCodec`; only registration (`PayloadRegistrar`) + `IPayloadContext` handlers are NeoForge.
- **Registration**: `setup/Registration.java` — `DeferredRegister` for 2 `Feature`s + 1 `AttachmentType<Boolean>` (`spawnset`, copyOnDeath). `setup/CustomRegistries.java` — **13 custom datapack registries** (buildings, palettes, parts, styles, conditions, citystyles, multibuildings, variants, worldstyles, predefinedcities, predefinedspheres, scattered, stuff) via `DataPackRegistryEvent.NewRegistry`, each with a Codec.
- **Game events** (`setup/ForgeEventHandlers.java`): `LevelEvent.CreateSpawnPosition`, `LevelTickEvent.Post`, `PlayerEvent.PlayerLogged{In,Out}`, `CanPlayerSleepEvent`, `ServerAboutToStartEvent`, `ServerStoppingEvent`, `RegisterCommandsEvent`.
- **Client**: `setup/ClientEventHandlers.java` — `ScreenEvent.Render.Post` + `ScreenEvent.Init.Post` inject a "Cities" button into `CreateWorldScreen` (needs AT on `tabManager`/`$MoreTab`).
- **Datagen**: `datagen/DataGenerators.java` (`GatherDataEvent.Server`), `LCBlockTags extends BlockTagsProvider` → 6 block-tag JSONs.
- **Scattered call sites (~10, trivial shims)**:
  - `ServerLifecycleHooks.getCurrentServer()` — `worldgen/lost/cityassets/Palette.java:84`, `worldgen/ErrorLogger.java:28`, `varia/WorldTools.java:33/45/52`
  - `NeoForge.EVENT_BUS.post(LostCityEvent...)` — `worldgen/lost/BuildingInfo.java:408`, `worldgen/LostCityTerrainFeature.java:344/474/963/972`
  - `Tags.Biomes.IS_VOID` — `worldgen/LostCityFeature.java:54`, `worldgen/LostCitySphereFeature.java:30`
  - API base classes `Event`/`ICancellableEvent` — `api/LostCityEvent.java`

## 3. Worldgen core wiring

- Cities generate inside two vanilla `Feature<NoneFeatureConfiguration>` subclasses: `worldgen/LostCityFeature.java` (`lostcities:lostcity`) and `worldgen/LostCitySphereFeature.java` (`lostcities:spheres`).
- Injection via NeoForge biome-modifier JSONs (`data/lostcities/neoforge/biome_modifier/lostcities.json` → `neoforge:add_features`, `#minecraft:is_overworld`, step `raw_generation`; `lostcity_spheres.json` → `top_layer_modification`).
- Datapack dimension `data/lostcities/dimension/lostcity.json` uses vanilla `minecraft:noise` + Neo-specific `"forge:use_server_seed": true`.
- AccessTransformer entries (6, in `META-INF/accesstransformer.cfg`): `CreateWorldScreen.tabManager` + `$MoreTab`; `NoiseChunk.getInterpolatedState`; `DensityFunctions$Marker` + `$Marker$Type`; `NoiseChunk$NoiseChunkDensityFunction` — needed by `NoiseChunkOpt.java`/`HeightGenOpt.java` which re-implement vanilla `NoiseChunk` (highest technical risk vs 26.2 density-function internals).
- No mixins, no coremods.

## 4. External dependencies

None (all optional integrations commented out). Only NeoForge itself.

## 5. Workstreams (ordered)

- **WS0 — Toolchain**: NeoGradle → Fabric Loom 1.17.17, MC 26.2, Mojmap, Loader 0.19.3, Fabric API 0.155.2+26.2, Java 25, `fabric.mod.json` replacing `neoforge.mods.toml`. Fix any 1.21.11→26.1→26.2 vanilla renames surfaced by the compiler (see scratchpad primers).
- **WS1 — Access wideners**: convert the 6 AT entries to a Loom `.accesswidener`; validate `NoiseChunkOpt`/`HeightGenOpt` against 26.2 density-function internals. HIGH RISK.
- **WS2 — Entrypoints/lifecycle**: `ModInitializer`/`ClientModInitializer`/`DataGeneratorEntrypoint`; map events (ServerLifecycleEvents, ServerTickEvents, CommandRegistrationCallback, EntitySleepEvents, ServerPlayConnectionEvents; spawn-position via mixin or ServerWorldEvents).
- **WS3 — Registration**: `Registry.register` for the 2 Features; Fabric `DynamicRegistries.register(Synced)` for the 13 codec registries; Fabric attachment API for `spawnset`.
- **WS4 — Feature injection**: replace biome-modifier JSONs with `BiomeModifications.addFeature(...)` at `RAW_GENERATION` / `TOP_LAYER_MODIFICATION` for overworld biomes.
- **WS5 — Dimension seed**: handle `forge:use_server_seed` (drop or mixin). RISK.
- **WS6 — Config**: `ModConfigSpec` → Forge Config API Port (fuzs.forgeconfigapiport, v26.2.1 available on Fabric 26.2 — keeps ModConfigSpec API nearly unchanged!) or hand-rolled. `FMLPaths.CONFIGDIR` → `FabricLoader.getInstance().getConfigDir()`.
- **WS7 — Networking**: `PayloadTypeRegistry` + `ServerPlayNetworking`/`ClientPlayNetworking`; payload bodies unchanged.
- **WS8 — Custom events**: `api/LostCityEvent` → Fabric `Event<Callback>` objects preserving cancellation semantics.
- **WS9 — Platform shims**: server accessor via `ServerLifecycleEvents`; `Tags.Biomes.IS_VOID` → `c:is_void` conventional tag; `Tags.Blocks.GLASS_BLOCKS` → `c:glass_blocks`.
- **WS10 — Datagen**: ship the 6 generated tag JSONs directly and drop datagen (simplest), or port to Fabric datagen.
- **WS11 — Client GUI hook**: `ScreenEvents.AFTER_INIT`/`afterRender` + access widener for the CreateWorldScreen button.
- **WS12 — Verification**: build; then runClient smoke test if possible.

## Reference material

- NeoForge 26.1→26.2 primer: scratchpad `primer-26.2.md`. Also need the 1.21.11→26.1 primer: https://raw.githubusercontent.com/neoforged/.github/main/primers/26.1/index.md (the 26.1 jump is the big one: many renames incl. ResourceLocation→Identifier already done in this codebase).
- Fabric API 0.155.2+26.2, Loader 0.19.3, Loom 1.17.17, Gradle 9.5.1, Java 25 (Temurin installed at /Library/Java/JavaVirtualMachines/temurin-25.jdk).
- A working multiloader 26.2 reference (once ported): /Volumes/Dev/Projects/krona/minecraft-mods/DynamicTrees (Fabric subproject shows loom config for 26.2).

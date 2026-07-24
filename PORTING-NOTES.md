# Lost Cities 9.4.2 — NeoForge/MC 1.21.11 → Fabric/MC 26.2 Porting Notes

Branch: `fabric/26.2`. Builds with `./gradlew build` → `build/libs/lostcities-fabric-26.2-<version>.jar`.

## Toolchain (WS0)
- NeoGradle userdev → **Fabric Loom 1.17.17**, Gradle 9.5.1, Java 25, Fabric Loader 0.19.3,
  Fabric API 0.155.2+26.2. MC 26.2 is unobfuscated, so there is no `mappings` dependency,
  no `modImplementation` remap configurations (plain `implementation` is used), and no refmap.
- `neoforge.mods.toml` → `fabric.mod.json`. Entrypoints: `mcjty.lostcities.LostCities` (main),
  `mcjty.lostcities.setup.ClientSetup` (client). Depends on `fabric-api` and `forgeconfigapiport`.
- AccessTransformer → `META-INF/lostcities.accesswidener` (namespace `official`), validated by
  `validateAccessWidener`. Same 6 entries (CreateWorldScreen.tabManager/$MoreTab,
  NoiseChunk.getInterpolatedState, DensityFunctions$Marker(+$Type), NoiseChunk$NoiseChunkDensityFunction).
- `src/api/java` (vestigial `ivorius.reccomplex` stub using gnu.trove) is **no longer compiled**.
- `com.google.code.findbugs:jsr305` added as compileOnly (javax.annotation).

## Loader coupling (WS2–WS9)
- **Lifecycle**: `LostCities` implements `ModInitializer`; `ModSetup.init()` runs synchronously at init.
- **Config (WS6)**: Forge Config API Port (fuzs) v26.2.1 — `ModConfigSpec`/`ModConfig` code unchanged
  (classes ship under original `net.neoforged.*` packages). Registered via
  `fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry`. `FMLPaths.CONFIGDIR` →
  `FabricLoader.getConfigDir()`. Note FCAP places the SERVER config at `config/lostcities-server.toml`
  (global config dir), not per-world `serverconfig/`.
- **Registration (WS3)**: two `Feature`s via plain `Registry.register`; `Registration.LOSTCITY_FEATURE`
  remains a `Supplier` for source compatibility. The `spawnset` `AttachmentType` was **dropped** —
  it was registered but never used anywhere in the codebase.
- **13 datapack registries**: Fabric `DynamicRegistries.register(...)` (unsynced, matching NeoForge
  behavior which had no network codec).
- **Feature injection (WS4)**: NeoForge biome-modifier JSONs deleted; Fabric
  `BiomeModifications.addFeature(BiomeSelectors.tag(BiomeTags.IS_OVERWORLD), RAW_GENERATION /
  TOP_LAYER_MODIFICATION, <placed feature key>)` in the mod initializer.
- **Events (WS2)**: `ForgeEventHandlers.register()` wires CommandRegistrationCallback,
  ServerPlayConnectionEvents.JOIN, ServerTickEvents.END_LEVEL_TICK, ServerLifecycleEvents
  SERVER_STARTING/STOPPING, EntitySleepEvents.ALLOW_SLEEPING (returns `BedSleepingProblem.OTHER_PROBLEM`
  and teleports, like the old CanPlayerSleepEvent handler).
- **CreateSpawnPosition**: no Fabric equivalent → `MinecraftServerMixin` injects at HEAD of the private
  static `MinecraftServer#setInitialSpawn` and cancels when `ForgeEventHandlers.onCreateSpawnPoint`
  takes over spawn placement. Verified firing at world creation.
- **Networking (WS7)**: `PayloadTypeRegistry.clientboundPlay()/serverboundPlay()` +
  `ServerPlayNetworking`/`ClientPlayNetworking` receivers. Both payload handlers were already
  empty `@todo` bodies; they remain no-ops (`handle()` without context).
- **API events (WS8)**: `LostCityEvent` is now a plain POJO with `isCanceled()/setCanceled()`;
  new `mcjty.lostcities.api.LostCityEvents` exposes five Fabric `Event<Consumer<...>>` hooks
  (CHARACTERISTICS, PRE/POST_GEN_CITY_CHUNK, POST_GEN_OUTSIDE_CHUNK, PRE_EXPLOSION).
  **API break** for downstream mods: subscribe via `LostCityEvents.X.register(...)` instead of the
  NeoForge bus.
- **IMC**: no Fabric equivalent. `ILostCities`/`ILostCitiesPre` consumers should use
  `LostCities.lostCitiesImp` directly. The IMC plumbing was removed.
- **Shims (WS9)**: `ServerLifecycleHooks.getCurrentServer()` → `mcjty.lostcities.varia.ServerAccess`
  (populated by ServerLifecycleEvents). `Tags.Biomes.IS_VOID` → conventional `c:is_void`
  (`LostTags.IS_VOID`).
- **Client GUI (WS11)**: "Cities" button injected via `ScreenEvents.AFTER_INIT` + `Screens.getWidgets`,
  visibility tracked per-tick (`afterTick`) against `tabManager.getCurrentTab() instanceof MoreTab`.
  The decorative 70x70 config icon blit from `ScreenEvent.Render.Post` was **dropped** — Fabric's
  screen API on 26.2 has no post-render hook (render moved to the extract pipeline). Cosmetic only.
  Client-side logout cleanup moved to `ClientPlayConnectionEvents.DISCONNECT`.

## Datagen (WS10)
Datagen sources deleted (`datagen/`). The six generated block-tag JSONs ship in the jar from
`src/generated/resources`, moved from `tags/blocks/` to the 26.2 singular `tags/block/`.
Note: `easybreakable.json` contains the flattened glass list; the old datagen referenced
`neoforge:glass_blocks` — if you regenerate tags someday, use `c:glass_blocks`.

## Dimension (WS5)
- `data/lostcities/dimension/lostcity.json`: dropped `"seed": 0` and `"forge:use_server_seed": true`.
  Vanilla 26.2 noise dimensions always use the world seed, which is exactly what
  `use_server_seed` requested — no behavior loss.
- `data/lostcities/dimension_type/lostcity.json`: rewritten for the 26.2 schema
  (`attributes` map, `has_ender_dragon_fight`, `default_clock`, `timelines`; `bed_works`,
  `respawn_anchor_works`, `effects`, `natural`, `ultrawarm`, `piglin_safe`, `has_raids` are gone
  or folded into attributes). Kept: height 0..256, monster spawn light 2, overworld visuals.

## Vanilla 1.21.11→26.2 changes fixed (selection)
- `ChunkPos` is a record: `.x()`/`.z()`, `ChunkPos.containing(BlockPos)`, `asLong` → `pack`.
- `DimensionDataStorage` → `SavedDataStorage`; `SavedDataType` now takes
  `(Identifier, Supplier, Codec, DataFixTypes)` — mod data uses `SAVED_DATA_COMMAND_STORAGE`
  (passthrough schema). Saved-data ids are now Identifiers (`lostcities:lostcities_data`,
  `lostcities:lostcity_editdata`) → **old `.dat` files from 1.21.11 worlds are not migrated**.
- GUI rework: `GuiGraphics` → `GuiGraphicsExtractor`, `drawString` → `text`,
  `Screen#render` → `Screen#extractRenderState`, `Minecraft#setScreen` → `minecraft.gui.setScreen`.
- `LevelChunkSection.SECTION_WIDTH/HEIGHT/SIZE` removed → local constants in `ChunkDriver`.
- `BlockTags.SAPLINGS` constant removed (tag JSON still exists) → direct `TagKey` lookup.
- NeoForge `Level#isAreaLoaded` extension → vanilla `hasChunksAt`.
- `Blocks.BLACK_BED` gone (beds are a `ColorCollection`) → bed facing read from
  `BedBlock.FACING` state property.
- `BlockState#getValues()` returns `Stream<Property.Value<?>>` → `Tools.stateToString` rewritten.
- `MinecraftServer.doRunTask` protected → `schedule(TickTask)`.
- `DensityFunctions.Marker.Type` gained `BlendDensity` → unwrapped in `NoiseChunkOpt`
  (the optimized pipeline doesn't blend).

## WS1: NoiseChunkOpt / HeightGenOpt
Compiled against 26.2 with only two changes (BlendDensity marker unwrap, `getBlender()` no longer
an override). The vanilla `NoiseChunk`/`NoiseRouter`/`Aquifer` internals are structurally the same
as 1.21.11. **Runtime behavior is unverified** — the optimized path is only used when
`optimizedHeightmap = true` in `lostcities/common.toml` (default **false**), so the risk is opt-in.

## Verified (WS12)
- `./gradlew build` green; jar contains fabric.mod.json (expanded), access widener, mixin json+class,
  data (incl. moved block tags), assets.
- Dedicated server: boots to `Done`, custom `lostcities:lostcity` dimension loads, config TOMLs
  generated, standard profiles written to `config/lostcities/profiles/`.
- Spawn mixin fires (observed in a crash stack when testing with an invalid profile name).
- Worldgen: with `selectedProfile = "onlycities"` the generated spawn chunk contains the city street
  palette (stone bricks + mossy/cracked variants, iron bars, smooth stone slab).
- Client: boots to resource load with both entrypoints, no errors.

## Known risks / follow-ups
- **Pre-existing NPE** (also on NeoForge): an invalid `selectedProfile` crashes world init at
  `Config.getProfileForDimension` (`STANDARD_PROFILES.get(profile).GENERATE_NETHER`).
- Optimized heightmap path (`optimizedHeightmap=true`) needs in-game verification vs 26.2 density
  functions before recommending it.
- The `CanPlayerSleepEvent` port: Fabric's `ALLOW_SLEEPING` fires at a slightly different point in
  the sleep flow; teleport-bed behavior should be tested in game (bed + diamond block + 6 skulls).
- GuiLCConfig (Cities config screen) compiles against the new extract-based GUI pipeline but has not
  been exercised in-game; test the CreateWorldScreen "More" tab button and preview rendering.
- `lostcities/stuff/example.json` still references `#forge:stone` in a selector (inert example data).
- IMC-based integrations (e.g. other McJty mods) will not find Lost Cities via IMC on Fabric.
- Old worlds from the NeoForge 1.21.11 build are not migration targets (saved-data ids changed,
  two MC version jumps).

## Things to test in-game
1. Create a world, More tab → "Cities" button → profile GUI (select profile, customize, preview map).
2. Overworld city generation with `default`, `rarecities`, `biosphere` profiles; spheres profiles at
   TOP_LAYER_MODIFICATION step.
3. The `lostcities:lostcity` datapack dimension (default profile mapping `biosphere`).
4. `/lostcities` commands (map, locate, debug, editmode).
5. Teleporter bed both directions; spawn placement with profiles that set SPAWN_BIOME/SPAWN_CITY/
   FORCE_SPAWN_IN_BUILDING.
6. `optimizedHeightmap = true` behavior parity.

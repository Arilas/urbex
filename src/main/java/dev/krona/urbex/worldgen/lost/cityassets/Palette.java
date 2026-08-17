package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSourceSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A palette of materials as used by building parts
 */
public class Palette {

    private final Identifier name;
    private final Map<Character, PE> palette = new HashMap<>();
    private final Map<BlockState, BlockState> damaged = new HashMap<>();

    /**
     * The version 2 form, or null for a version 1 palette.
     *
     * <p><b>One asset type for both formats, which is not the same as one model for both.</b> The
     * {@code palettes} registry has one value type and one {@link AssetIndex}, and every consumer -
     * {@link Style}, {@link BuildingPart}, {@link AssetGraph} - names {@code Palette}. Giving version 2
     * its own asset type would mean a second index, a second selector in every style, and a second
     * branch at every one of those consumers, which is what {@code VER.006} forbids being necessary:
     * a style's {@code randompalettes} may draw a version 1 and a version 2 palette into one merge, so
     * they have to be drawable from one list.</p>
     *
     * <p>What is <em>not</em> shared is the model. When this field is set, {@link #palette} and
     * {@link #damaged} are empty and stay empty: a version 2 palette is compiled by
     * {@code CompiledV2Palette}, all eight stages of {@code LOAD.001}, and none of version 1's
     * per-entry compilation runs. {@link CompiledPalette} is where the two meet, and it meets them as
     * compiled markers rather than as a common node model - which is
     * {@code PaletteAssetDefinition}'s "no common node model, no shared merge, and nothing here invites
     * one" held one layer further in.</p>
     */
    @Nullable
    private final CompiledV2Palette v2;

    /**
     * Builds a fully resolved palette from its {@code extends} chain, root first.
     * <p>
     * A palette is a keyed collection, so the chain merges <em>by character</em> rather than by
     * position: entries land in a {@link LinkedHashMap} keyed by their marker, in chain order, so a
     * descendant that repaints two markers out of thirty overwrites exactly those two and keeps the
     * other twenty-eight. Appending as a list would double-register a character; replacing as a
     * list would silently drop everything the child did not restate. Only the surviving entries are
     * then compiled, so an overridden entry takes its {@code damaged} mapping with it.
     */
    /**
     * @param blockLookup the block registry every block string in this palette resolves against,
     *                    handed down by the compiler from the world being loaded.
     * @param variants    the compiled variants this palette's {@code variant} entries resolve
     *                    against. May be null only for a palette that provably names none; one that
     *                    does then fails naming itself and the variant rather than reaching for a
     *                    static server (issue #60) or compiling one on the spot (issue #128).
     */
    public Palette(Identifier id, HolderLookup<Block> blockLookup, @Nullable AssetIndex<Variant> variants,
                   List<PaletteDefinition> chainRootFirst) {
        name = id;
        v2 = null;
        compile(blockLookup, variants, mergeByCharacter(chainRootFirst, name));
    }

    public Palette(String name) {
        this.name = Identifier.fromNamespaceAndPath(Urbex.MODID, name);
        this.v2 = null;
    }

    private Palette(Identifier id, CompiledV2Palette compiled) {
        this.name = id;
        this.v2 = compiled;
    }

    /**
     * A registered palette written in format version 2, already compiled.
     *
     * <p>The compilation happened before this: {@code CompiledV2Palette.compile} runs stages 4 to 8 of
     * {@code LOAD.001} and refuses the palette by name if anything is wrong, which is {@code LOAD.004}
     * and {@code LOAD.010}. By the time one of these exists, every question generation can ask it has
     * an answer - {@code LOAD.011} - so this constructor cannot fail and does not validate.</p>
     */
    public static Palette version2(Identifier id, CompiledV2Palette compiled) {
        return new Palette(id, compiled);
    }

    /** The compiled version 2 form, or null for a version 1 palette. */
    @Nullable
    public CompiledV2Palette v2() {
        return v2;
    }

    /**
     * Builds the inline {@code palette} block a part or building carries, merged along that asset's
     * own {@code extends} chain.
     * <p>
     * An inline palette is a keyed collection exactly like a registered one, so it merges by
     * character too: a part that extends another and declares an inline palette repainting two
     * markers keeps the rest of its ancestor's. Replacing wholesale here would reproduce, one level
     * down, the very failure {@link #mergeByCharacter} exists to prevent.
     *
     * <p>{@code MERGE.009} is the first check below and {@code DIAG.031} is its message. The version 1
     * codec accepts {@code extends} wherever a {@link PaletteDefinition} is embedded, and an inline
     * palette is not a registry entry, so nothing can resolve it; silently dropping a key the codec
     * accepted is how a datapack quietly means something other than what it says. A version <em>2</em>
     * inline palette is refused for the same rule one stage earlier, by
     * {@link PaletteAssetDefinition#INLINE_CODEC}, so this loop is version 1's half of it - see that
     * field for why the two halves fire at different times.</p>
     *
     * <p>{@code VER.015} is the second: an inline palette may declare version 2 as of {@code MERGE.011},
     * and nothing compiles a version 2 palette yet, so {@link PaletteAssetDefinition#version1Only} says
     * so by name rather than this method casting and failing from a worker thread.</p>
     *
     * @param blockLookup    the block registry the inline entries resolve against
     * @param owner          the part or building the block is written in, for error messages and
     *                       for the synthetic palette name
     * @param chainRootFirst the inline blocks along the owner's chain, root first
     */
    public static Palette inline(HolderLookup<Block> blockLookup, @Nullable AssetIndex<Variant> variants,
                                 Identifier owner, List<PaletteAssetDefinition> chainRootFirst) {
        for (PaletteAssetDefinition re : chainRootFirst) {
            if (re.getExtends().isPresent()) {
                throw new IllegalStateException(Diag.DIAG_031.message("'" + owner + "'",
                        "'" + re.getExtends().orElseThrow() + "'", "'" + owner + "'"));
            }
        }
        List<PaletteDefinition> version1 = PaletteAssetDefinition.version1Only(owner, chainRootFirst);
        Palette palette = new Palette("__local__" + owner.getPath());
        palette.compile(blockLookup, variants, mergeByCharacter(version1, owner));
        return palette;
    }

    /**
     * Later entries overwrite the characters they declare and leave the rest alone. Only the
     * surviving entries are handed on to {@link #compile}, so an overridden entry takes its
     * {@code damaged} mapping with it rather than leaving it keyed on a block that is no longer
     * placed.
     * <p>
     * {@code palette} is required of the chain rather than of each file, so an entry that only
     * declares {@code extends} inherits its ancestor's markers; a chain where nothing declares one
     * is a load error rather than a palette that silently maps no characters at all.
     */
    private static Collection<PaletteEntry> mergeByCharacter(List<PaletteDefinition> chainRootFirst,
                                                             Identifier owner) {
        Map<Character, PaletteEntry> merged = new LinkedHashMap<>();
        boolean anyEntries = false;
        for (PaletteDefinition re : chainRootFirst) {
            if (re.getPaletteEntries() == null) {
                continue;
            }
            anyEntries = true;
            for (PaletteEntry entry : re.getPaletteEntries()) {
                merged.put(entry.getChr().charAt(0), entry);
            }
        }
        Resolved.require(anyEntries ? merged : null, owner, "palette");
        return merged.values();
    }

    /**
     * Version 1's in-place merge, which a version 2 palette does not take part in.
     *
     * <p>{@code Style.getRandomPalette} used this to flatten a draw into one {@code Palette} before
     * compiling it. That cannot express a cross-version draw ({@code VER.006}) - there is nothing to
     * copy a {@code CompiledV2Palette} into - so composition moved up to {@link CompiledPalette}, which
     * merges compiled markers rather than authored entries. This remains for version 1's own callers.</p>
     *
     * @throws IllegalStateException if either side is a version 2 palette, rather than silently
     *         producing a palette with none of its markers
     */
    public void merge(Palette other) {
        if (v2 != null || other.v2 != null) {
            throw new IllegalStateException("Palette '" + name + "' cannot merge '" + other.name
                    + "' in place: a version 2 palette is composed as compiled markers, by "
                    + "CompiledPalette, and copying its maps here would produce a palette with no "
                    + "markers at all");
        }
        palette.putAll(other.palette);
        damaged.putAll(other.damaged);
    }

    /** The fully-qualified id, e.g. {@code "urbex:common"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    public Map<BlockState, BlockState> getDamaged() {
        return damaged;
    }

    public Map<Character, PE> getPalette() {
        return palette;
    }

    private void compile(HolderLookup<Block> blockLookup, @Nullable AssetIndex<Variant> variants,
                         Collection<PaletteEntry> entries) {
        for (PaletteEntry entry : entries) {
            Character c = entry.getChr().charAt(0);
            // Null when the damaged block is absent from this game, so the mapping is simply not
            // written. Air would say "damaging this block deletes it", which is a claim the author
            // did not make (issue #91).
            BlockState dmg = entry.getDamaged() == null ? null
                    : Tools.resolveState(entry.getDamaged(), blockLookup, name);
            rejectRemovedLightSpellings(entry, c);
            LightSourceSettings settings = entry.getLightSource();
            // Also null when every candidate in the pool named an absent block; see
            // LightPool.compile. An authored-empty pool is still a load error, and an in-place
            // source has no pool at all - its own block is the light.
            LightPool light = settings == null || !settings.isSocket() ? null
                    : LightPool.compile(blockLookup, name, c, settings);
            LightSource lightSource = settings == null ? null
                    : new LightSource(light, compileUnlit(blockLookup, settings, c));
            Info info = Info.of(entry.getMob(), entry.getLoot(), lightSource, entry.getTag());
            // What the character resolves to, for the in-place emission check below. Empty for a
            // socket, which has no block of its own, and for 'frompalette', whose target is only
            // known once CompiledPalette has resolved the character it names.
            List<BlockState> resolved = new ArrayList<>();

            if (entry.getBlock() != null) {
                String block = entry.getBlock();
                BlockState state = Tools.stringToState(block, blockLookup, name);
                palette.put(c, new PE(state, info));
                resolved.add(state);
                if (dmg != null) {
                    damaged.put(state, dmg);
                }
            } else if (entry.getVariant() != null) {
                String variantName = entry.getVariant();
                // The compiled variants of this palette's own world, handed in by the compiler.
                // This used to ask ServerAccess.getServer().getLevel(OVERWORLD), which resolved every
                // dimension's variants against the overworld and threw from a worldgen worker when
                // the static server reference was not populated yet (issue #60); then it asked a
                // static registry that compiled on demand (issue #128). Now the variant it needs was
                // compiled before this palette was.
                if (variants == null) {
                    throw new IllegalStateException("Palette '" + name + "' entry '" + c
                            + "' names variant '" + variantName + "', but this palette was compiled "
                            + "without a variant index to resolve it against");
                }
                Variant variant = variants.getOrThrow(variantName);
                List<Pair<Integer, BlockState>> blocks = variant.getBlocks();
                for (Pair<Integer, BlockState> pair : blocks) {
                    resolved.add(pair.getRight());
                    if (dmg != null) {
                        damaged.put(pair.getRight(), dmg);
                    }
                }
                addMappingViaState(c, blocks, info);
            } else if (entry.getFrompalette() != null) {
                String value = entry.getFrompalette();
                palette.put(c, new PE(value, info));
            } else if (entry.getBlocks() != null) {
                List<BlockEntry> entryBlocks = entry.getBlocks();
                List<Pair<Integer, BlockState>> blocks = new ArrayList<>();
                for (BlockEntry ob : entryBlocks) {
                    Integer f = ob.random();
                    String block = ob.block();
                    // Dropped rather than placed as air, exactly as in a variant: the weights that
                    // remain are apportioned over the character's 128 slots by
                    // CompiledPalette.distributeSlots, so the survivors take over the missing
                    // entry's share instead of competing with invisible blocks (issue #91).
                    BlockState state = Tools.resolveState(block, blockLookup, name);
                    if (state == null) {
                        continue;
                    }
                    blocks.add(Pair.of(f, state));
                    resolved.add(state);
                    if (dmg != null) {
                        damaged.put(state, dmg);
                    }
                }
                addMappingViaState(c, blocks, info);
            } else if (light != null) {
                palette.put(c, new PE(light.representative(), info));
            } else if (settings != null && settings.isSocket()) {
                // A socket whose whole pool named absent blocks. The marker still has to map to
                // something - a character with no entry at all throws from the driver, which is the
                // crash issue #91 is removing - and its replacement is what it will write anyway.
                palette.put(c, new PE(Blocks.AIR.defaultBlockState(), info));
            } else if (settings != null) {
                // A light source that names nothing to place. Both spellings reach here: an object
                // whose four placement lists are all empty, and a bare "lightSource": true on an
                // entry with no block of its own. Neither has an interpretation - there is no state
                // to light the marker with - and the generic "Illegal palette" this used to raise
                // named the file but not which of its characters, nor what was missing from it.
                throw new IllegalArgumentException("Palette '" + name + "' entry '" + c + "' declares "
                        + "'lightSource' but names nothing to place. Give the entry a block, blocks, "
                        + "variant or frompalette to light, or give the light source at least one "
                        + "candidate in floor, wall, ceiling, or free.");
            } else {
                throw new RuntimeException("Illegal palette " + name + "!");
            }

            if (settings != null && !settings.isSocket()) {
                requireEmittingInPlaceSource(resolved, c);
            }
        }
    }

    /**
     * Refuses the two spellings {@code lightSource} replaced, by name.
     * <p>
     * Both used to mean "this marker is optional decoration": {@code torch: true} placed a vanilla
     * torch and {@code light} a typed pool. Neither could be said about an ordinary block entry, so
     * every light a pack authored as a plain block - ModernTweaks' lanterns, most of its lighting -
     * ignored lighting density entirely. Dropping the keys from the codec instead would make them
     * unknown keys, which a palette silently ignores: the pack would load, place a permanent torch,
     * and say nothing.
     */
    private void rejectRemovedLightSpellings(PaletteEntry entry, char c) {
        if (entry.isLegacyTorch()) {
            throw new IllegalArgumentException("Palette '" + name + "' entry '" + c + "' declares "
                    + "'torch', which no longer exists. Write \"lightSource\" instead: either "
                    + "\"lightSource\": true to make this entry's own block an optional light, or a "
                    + "\"lightSource\" object with floor/wall/ceiling/free candidates to let Urbex "
                    + "pick and orient one.");
        }
        if (entry.isLegacyLight()) {
            throw new IllegalArgumentException("Palette '" + name + "' entry '" + c + "' declares "
                    + "'light', which was renamed. Write the same object under \"lightSource\", and "
                    + "add \"unlit\" to it if this marker should leave something behind when the "
                    + "light is off.");
        }
    }

    /**
     * A {@code lightSource} on an entry whose blocks emit nothing is a load error.
     * <p>
     * There is no reading of it that works: the entry would roll lighting density and then place the
     * same dark block either way, so the author would have marked something optional that can never
     * look different. An entry that resolves to no state at all is exempt - that is issue #91's
     * absent-block case, which is already air and is not the author's mistake.
     */
    private void requireEmittingInPlaceSource(List<BlockState> resolved, char c) {
        if (resolved.isEmpty()) {
            return;
        }
        for (BlockState state : resolved) {
            if (state.getLightEmission() > 0) {
                return;
            }
        }
        throw new IllegalArgumentException("Palette '" + name + "' entry '" + c + "' declares "
                + "'lightSource', but none of the blocks it resolves to emit any light. Either name "
                + "candidates under floor/wall/ceiling/free, or drop 'lightSource' from this entry.");
    }

    /**
     * What this source writes when it is off, or when a socket finds nowhere to put a light.
     * <p>
     * Compiled with the same issue-#91 leniency as any other palette block: an absent block is
     * dropped from a weighted replacement, and a replacement whose every block is absent becomes
     * air - which is what a rejected light marker has always left behind.
     */
    private BlockChoice compileUnlit(HolderLookup<Block> blockLookup, LightSourceSettings settings, char c) {
        if (settings.unlit() != null) {
            BlockState state = Tools.resolveState(settings.unlit(), blockLookup, name);
            return state == null ? BlockChoice.AIR : BlockChoice.of(state);
        }
        if (settings.unlitBlocks() == null) {
            return BlockChoice.AIR;
        }
        List<Pair<Integer, BlockState>> weighted = new ArrayList<>();
        for (BlockEntry ob : settings.unlitBlocks()) {
            BlockState state = Tools.resolveState(ob.block(), blockLookup, name);
            if (state != null) {
                weighted.add(Pair.of(ob.random(), state));
            }
        }
        if (weighted.isEmpty() && !settings.unlitBlocks().isEmpty()) {
            return BlockChoice.AIR;
        }
        if (weighted.isEmpty()) {
            throw new IllegalArgumentException("Palette '" + name + "' entry '" + c
                    + "' declares an empty 'unlitBlocks'. Name at least one block, or omit the field "
                    + "to leave air behind.");
        }
        return BlockChoice.of(weighted);
    }

    /**
     * Maps {@code c} to a weighted list, or to air when nothing is left in it.
     * <p>
     * The empty case is reachable only through issue #91: every block the character named is absent
     * from this game. It has to be handled here rather than left to
     * {@code CompiledPalette.distributeSlots}, which refuses weights summing to zero - correctly, for
     * an authored list - and it has to be air rather than no entry at all, because a character the
     * palette does not map throws from the driver on the first part that uses it.
     */
    private Palette addMappingViaState(char c, List<Pair<Integer, BlockState>> randomBlocks, Info info) {
        if (randomBlocks.isEmpty()) {
            palette.put(c, new PE(Blocks.AIR.defaultBlockState(), info));
            return this;
        }
        palette.put(c, new PE(randomBlocks.toArray(new Pair[randomBlocks.size()]), info));
        return this;
    }

    /**
     * What a marker carries beyond its block: version 1's four metadata fields, plus the ordered list
     * of the traits they amount to.
     *
     * @param applied the traits this marker actually applies, in {@link MarkerTrait} order. Computed
     *                once, at compile time, because {@code Parts.generatePart} walks it for every block
     *                of every part that has one and building it there would allocate at a position.
     */
    public record Info(String mobId, String loot, LightSource lightSource, CompoundTag tag,
                       List<MarkerTrait> applied) {

        /**
         * The traits these four fields amount to, in {@link MarkerTrait} order.
         * <p>
         * The emptiness guards on {@code loot} and {@code mobId} are version 1's, kept exactly: the
         * {@code else if} chain this replaces tested {@code != null && !isEmpty()} for both and bare
         * {@code != null} for the other two, so a marker declaring {@code "loot": ""} was special enough
         * to skip the block's other handling and not special enough to be given loot. That is not a
         * behaviour worth preserving on its merits; it is preserved because {@link #isSpecial} still
         * answers the same way and changing what an empty string means is a separate decision from
         * fixing the chain.
         */
        public static Info of(String mobId, String loot, LightSource lightSource, CompoundTag tag) {
            List<MarkerTrait> applied = new ArrayList<>(4);
            if (loot != null && !loot.isEmpty()) {
                applied.add(MarkerTrait.LOOT);
            }
            if (mobId != null && !mobId.isEmpty()) {
                applied.add(MarkerTrait.SPAWNER);
            }
            if (tag != null) {
                applied.add(MarkerTrait.BLOCK_ENTITY);
            }
            if (lightSource != null) {
                applied.add(MarkerTrait.LIGHT);
            }
            return new Info(mobId, loot, lightSource, tag, List.copyOf(applied));
        }

        public boolean isSpecial() {
            return mobId != null || loot != null || lightSource != null || tag != null;
        }
    }

    public record PE(Object blocks, Info info) {
    }

}

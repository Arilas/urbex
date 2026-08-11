package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.cityassets.Resolved;
import dev.krona.urbex.worldgen.lost.regassets.data.BiomeMatcher;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockMatcher;
import dev.krona.urbex.worldgen.lost.regassets.data.IdentifierMatcher;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Settings for a piece of scattered decoration.
 * <p>
 * {@code column}, {@code mincount}, {@code maxcount} and {@code attempts} are optional here rather
 * than required, because a rarer variant of an existing decoration should not have to restate them.
 * Requiredness is checked after the chain is resolved, in {@link #resolve}.
 */
public class StuffSettingsRE implements IAsset<StuffSettingsRE>, Extendable {

    public static final Codec<StuffSettingsRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(Codec.STRING).optionalFieldOf("tags").forGetter(l -> Optional.ofNullable(l.tags)),
                    Codec.STRING.optionalFieldOf("column").forGetter(l -> Optional.ofNullable(l.column)),
                    Codec.INT.optionalFieldOf("minheight").forGetter(l -> Optional.ofNullable(l.minheight)),
                    Codec.INT.optionalFieldOf("maxheight").forGetter(l -> Optional.ofNullable(l.maxheight)),
                    Codec.INT.optionalFieldOf("mincount").forGetter(l -> Optional.ofNullable(l.mincount)),
                    Codec.INT.optionalFieldOf("maxcount").forGetter(l -> Optional.ofNullable(l.maxcount)),
                    Codec.INT.optionalFieldOf("attempts").forGetter(l -> Optional.ofNullable(l.attempts)),
                    Codec.BOOL.optionalFieldOf("inbuilding").forGetter(l -> Optional.ofNullable(l.inbuilding)),
                    Codec.BOOL.optionalFieldOf("seesky").forGetter(l -> Optional.ofNullable(l.seesky)),
                    BiomeMatcher.CODEC.optionalFieldOf("biomes").forGetter(l -> Optional.ofNullable(l.biomeMatcher)),
                    BlockMatcher.CODEC.optionalFieldOf("blocks").forGetter(l -> Optional.ofNullable(l.blockMatcher)),
                    BlockMatcher.CODEC.optionalFieldOf("upperblocks").forGetter(l -> Optional.ofNullable(l.upperBlockMatcher)),
                    IdentifierMatcher.CODEC.optionalFieldOf("buildings").forGetter(l -> Optional.ofNullable(l.buildingMatcher))
            ).apply(instance, StuffSettingsRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final Mergeable<String> tags;
    // Null on any of these means "not declared here", so the chain reads it from an ancestor.
    private final String column;
    private final Integer minheight;
    private final Integer maxheight;
    private final Integer mincount;
    private final Integer maxcount;
    private final Integer attempts;
    private final Boolean inbuilding;
    private final Boolean seesky;
    private final BiomeMatcher biomeMatcher;
    private final BlockMatcher blockMatcher;
    private final BlockMatcher upperBlockMatcher;
    private final IdentifierMatcher buildingMatcher;

    public StuffSettingsRE(Optional<Identifier> extendsId,
                           Optional<Mergeable<String>> tags,
                           Optional<String> column,
                           Optional<Integer> minheight, Optional<Integer> maxheight,
                           Optional<Integer> mincount, Optional<Integer> maxcount, Optional<Integer> attempts,
                           Optional<Boolean> inbuilding, Optional<Boolean> seesky,
                           Optional<BiomeMatcher> biomeMatcher, Optional<BlockMatcher> blockMatcher,
                           Optional<BlockMatcher> upperBlockMatcher,
                           Optional<IdentifierMatcher> buildingMatcher) {
        this.extendsId = extendsId;
        this.tags = tags.orElse(null);
        this.column = column.orElse(null);
        this.minheight = minheight.orElse(null);
        this.maxheight = maxheight.orElse(null);
        this.mincount = mincount.orElse(null);
        this.maxcount = maxcount.orElse(null);
        this.attempts = attempts.orElse(null);
        this.inbuilding = inbuilding.orElse(null);
        this.seesky = seesky.orElse(null);
        this.biomeMatcher = biomeMatcher.orElse(null);
        this.blockMatcher = blockMatcher.orElse(null);
        this.upperBlockMatcher = upperBlockMatcher.orElse(null);
        this.buildingMatcher = buildingMatcher.orElse(null);
    }

    /**
     * Folds an {@code extends} chain, root first, into one settings object: every field takes the
     * value of the last entry in the chain that declares it, and {@code tags} goes through
     * {@link Mergeable#apply} so a child can replace or append to what it inherits.
     * <p>
     * The four fields generation cannot default - {@code column}, {@code mincount},
     * {@code maxcount} and {@code attempts} - are required <em>of the fold</em> rather than of each
     * file, so a child inherits them, and a chain where nothing declares one is a load error naming
     * the asset and the field rather than an NPE during generation.
     */
    public static StuffSettingsRE resolve(List<StuffSettingsRE> chainRootFirst) {
        StuffSettingsRE leaf = chainRootFirst.get(chainRootFirst.size() - 1);
        if (chainRootFirst.size() == 1) {
            return leaf.requireResolved();
        }
        List<String> tags = new ArrayList<>();
        boolean anyTags = false;
        String column = null;
        Integer minheight = null;
        Integer maxheight = null;
        Integer mincount = null;
        Integer maxcount = null;
        Integer attempts = null;
        Boolean inbuilding = null;
        Boolean seesky = null;
        BiomeMatcher biomeMatcher = null;
        BlockMatcher blockMatcher = null;
        BlockMatcher upperBlockMatcher = null;
        IdentifierMatcher buildingMatcher = null;
        for (StuffSettingsRE re : chainRootFirst) {
            if (re.tags != null) {
                Mergeable.apply(tags, re.tags);
                anyTags = true;
            }
            if (re.column != null) {
                column = re.column;
            }
            if (re.mincount != null) {
                mincount = re.mincount;
            }
            if (re.maxcount != null) {
                maxcount = re.maxcount;
            }
            if (re.attempts != null) {
                attempts = re.attempts;
            }
            if (re.minheight != null) {
                minheight = re.minheight;
            }
            if (re.maxheight != null) {
                maxheight = re.maxheight;
            }
            if (re.inbuilding != null) {
                inbuilding = re.inbuilding;
            }
            if (re.seesky != null) {
                seesky = re.seesky;
            }
            if (re.biomeMatcher != null) {
                biomeMatcher = re.biomeMatcher;
            }
            if (re.blockMatcher != null) {
                blockMatcher = re.blockMatcher;
            }
            if (re.upperBlockMatcher != null) {
                upperBlockMatcher = re.upperBlockMatcher;
            }
            if (re.buildingMatcher != null) {
                buildingMatcher = re.buildingMatcher;
            }
        }
        return new StuffSettingsRE(Optional.empty(),
                anyTags ? Optional.of(new Mergeable<>(true, List.copyOf(tags))) : Optional.empty(),
                Optional.ofNullable(column),
                Optional.ofNullable(minheight), Optional.ofNullable(maxheight),
                Optional.ofNullable(mincount), Optional.ofNullable(maxcount), Optional.ofNullable(attempts),
                Optional.ofNullable(inbuilding), Optional.ofNullable(seesky),
                Optional.ofNullable(biomeMatcher), Optional.ofNullable(blockMatcher),
                Optional.ofNullable(upperBlockMatcher), Optional.ofNullable(buildingMatcher))
                .setRegistryName(leaf.getRegistryName())
                .requireResolved();
    }

    /**
     * Fails the load unless the whole chain, taken together, declared everything generation reads
     * without a fallback. Called on the fold - or on the entry itself when the chain is one long.
     */
    private StuffSettingsRE requireResolved() {
        Resolved.require(column, name, "column");
        Resolved.require(mincount, name, "mincount");
        Resolved.require(maxcount, name, "maxcount");
        Resolved.require(attempts, name, "attempts");
        return this;
    }

    public List<String> getTags() {
        return tags == null ? Collections.emptyList() : tags.values();
    }

    /**
     * The four getters below unbox fields that are null on an entry which does not declare them.
     * Generation only ever sees a settings object that came out of {@link #resolve}, which has
     * already failed the load if the chain left one of them undeclared.
     */
    public String getColumn() {
        return column;
    }

    public Integer getMinheight() {
        return minheight;
    }

    public Integer getMaxheight() {
        return maxheight;
    }

    public int getMincount() {
        return mincount;
    }

    public int getMaxcount() {
        return maxcount;
    }

    public BiomeMatcher getBiomeMatcher() {
        return biomeMatcher == null ? BiomeMatcher.ANY : biomeMatcher;
    }

    public BlockMatcher getBlockMatcher() {
        return blockMatcher == null ? BlockMatcher.ANY : blockMatcher;
    }

    public BlockMatcher getUpperBlockMatcher() {
        return upperBlockMatcher == null ? BlockMatcher.ANY : upperBlockMatcher;
    }

    public IdentifierMatcher getBuildingMatcher() {
        return buildingMatcher == null ? IdentifierMatcher.ANY : buildingMatcher;
    }

    public Boolean isInBuilding() {
        return inbuilding;
    }

    public Boolean isSeesky() {
        return seesky;
    }

    public int getAttempts() {
        return attempts;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public StuffSettingsRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}

package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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

public class StuffSettingsRE implements IAsset<StuffSettingsRE>, Extendable {

    public static final Codec<StuffSettingsRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(Codec.STRING).optionalFieldOf("tags").forGetter(l -> Optional.ofNullable(l.tags)),
                    Codec.STRING.fieldOf("column").forGetter(l -> l.column),
                    Codec.INT.optionalFieldOf("minheight").forGetter(l -> Optional.ofNullable(l.minheight)),
                    Codec.INT.optionalFieldOf("maxheight").forGetter(l -> Optional.ofNullable(l.maxheight)),
                    Codec.INT.fieldOf("mincount").forGetter(l -> l.mincount),
                    Codec.INT.fieldOf("maxcount").forGetter(l -> l.maxcount),
                    Codec.INT.fieldOf("attempts").forGetter(l -> l.attempts),
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
    private final String column;
    private final Integer minheight;
    private final Integer maxheight;
    private final int mincount;
    private final int maxcount;
    private final int attempts;
    private final Boolean inbuilding;
    private final Boolean seesky;
    private final BiomeMatcher biomeMatcher;
    private final BlockMatcher blockMatcher;
    private final BlockMatcher upperBlockMatcher;
    private final IdentifierMatcher buildingMatcher;

    public StuffSettingsRE(Optional<Identifier> extendsId,
                           Optional<Mergeable<String>> tags,
                           String column,
                           Optional<Integer> minheight, Optional<Integer> maxheight, int mincount, int maxcount, int attempts,
                           Optional<Boolean> inbuilding, Optional<Boolean> seesky,
                           Optional<BiomeMatcher> biomeMatcher, Optional<BlockMatcher> blockMatcher,
                           Optional<BlockMatcher> upperBlockMatcher,
                           Optional<IdentifierMatcher> buildingMatcher) {
        this.extendsId = extendsId;
        this.tags = tags.orElse(null);
        this.column = column;
        this.minheight = minheight.orElse(null);
        this.maxheight = maxheight.orElse(null);
        this.mincount = mincount;
        this.maxcount = maxcount;
        this.attempts = attempts;
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
     */
    public static StuffSettingsRE resolve(List<StuffSettingsRE> chainRootFirst) {
        StuffSettingsRE leaf = chainRootFirst.get(chainRootFirst.size() - 1);
        if (chainRootFirst.size() == 1) {
            return leaf;
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
            column = re.column;
            mincount = re.mincount;
            maxcount = re.maxcount;
            attempts = re.attempts;
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
                column,
                Optional.ofNullable(minheight), Optional.ofNullable(maxheight),
                mincount, maxcount, attempts,
                Optional.ofNullable(inbuilding), Optional.ofNullable(seesky),
                Optional.ofNullable(biomeMatcher), Optional.ofNullable(blockMatcher),
                Optional.ofNullable(upperBlockMatcher), Optional.ofNullable(buildingMatcher))
                .setRegistryName(leaf.getRegistryName());
    }

    public List<String> getTags() {
        return tags == null ? Collections.emptyList() : tags.values();
    }

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

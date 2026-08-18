package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code VER.017}: the {@code variants} registry is removed, and a version 1 palette naming one is
 * refused by name.
 *
 * <p>This file replaces {@code PaletteVariantResolutionTest}, which covered where a {@code variant}
 * resolved: against the index the palette was handed rather than against a process-wide server
 * reference (issue #60) or a registry compiled on demand (issue #128). There is no index to resolve
 * against now, so what is left to guard is the refusal - and the refusal is the whole of what those
 * two issues were about, one level up. A palette that silently painted air would be their failure
 * again, in the form this format is least able to see.</p>
 *
 * <p><b>Why an assertion on the message and not only on the throw.</b> {@code VER.017}'s
 * {@code > Why it is refused and not dropped} is that the key is held decodable <em>purely</em> so it
 * can fail by name; a refusal that named neither the variant nor its replacement would satisfy the
 * throw and defeat the reason for it. So the three things an author needs are asserted individually:
 * what they wrote, where it went, and what rewrites it.</p>
 */
class VariantKeyRefusedTest {

    private static final Identifier PALETTE_ID = Identifier.fromNamespaceAndPath("urbex", "variant-palette");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @Rule("VER.017")
    void aVersion1EntryNamingAVariantIsRefusedAndTheMessageNamesItsReplacement() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, List.of(paletteNamingVariant())));

        String message = failure.getMessage();
        assertTrue(message.contains("urbex:rubble"),
                () -> "names the variant the author wrote: " + message);
        assertTrue(message.contains("variant-palette") && message.contains("'V'"),
                () -> "names the palette and the marker, which is what the author can edit: " + message);
        assertTrue(message.contains("definitions") && message.contains("$ref"),
                () -> "names where the variants registry went: " + message);
        assertTrue(message.contains("convertPalettes"),
                () -> "names the tool that rewrites it, since the remedy is a conversion: " + message);
    }

    /**
     * The refusal fires before the entry is read for anything else.
     *
     * <p>An entry may name a {@code variant} beside keys that are still live - {@code damaged} here -
     * and the version 1 block-source ladder took {@code block} before {@code variant}. An entry naming
     * only a variant has no other source, so it would fail somewhere regardless; this one would not,
     * and it is the case that distinguishes a refusal from a palette that happens to break.</p>
     */
    @Test
    @Rule("VER.017")
    void anEntryNamingBothAVariantAndALiveKeyIsStillRefused() {
        PaletteEntry entry = new PaletteEntry("V", Optional.empty(), Optional.of("urbex:rubble"),
                Optional.empty(), Optional.empty(), Optional.of("minecraft:iron_bars"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK,
                        List.of(new PaletteDefinition(Optional.empty(), Optional.of(List.of(entry))))));

        assertTrue(failure.getMessage().contains("urbex:rubble"), failure.getMessage());
    }

    private static PaletteDefinition paletteNamingVariant() {
        PaletteEntry entry = new PaletteEntry("V", Optional.empty(), Optional.of("urbex:rubble"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new PaletteDefinition(Optional.empty(), Optional.of(List.of(entry)));
    }
}

package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preset half of the {@code name} field: how it resolves, and what a UI reads when nothing
 * declared one.
 * <p>
 * The fallback is the part worth pinning. Before {@code name} existed the Cities tab labelled every
 * row with the fully-qualified id, and a datapack that declares no name must still read exactly that
 * way - a blank row would be a worse regression than the namespacing this field exists to fix.
 * <p>
 * Headless: {@code Presets.resolve(Identifier, Function)} needs no registry or level.
 */
class DisplayNameTest {

    private static PresetRE decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return PresetRE.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("urbex", path);
    }

    @Test
    void anAuthoredNameIsWhatTheUiShows() {
        Identifier presetId = id("tallbuildings");
        Map<Identifier, PresetRE> lookup = Map.of(presetId, decode("{\"name\": \"Tall Buildings\"}"));

        Preset p = Presets.resolve(presetId, lookup::get);

        assertEquals("Tall Buildings", p.getName());
        assertEquals("Tall Buildings", p.getDisplayName());
    }

    @Test
    void noNameAnywhereFallsBackToTheFullyQualifiedId() {
        Identifier presetId = id("tallbuildings");
        Map<Identifier, PresetRE> lookup = Map.of(presetId, decode("{}"));

        Preset p = Presets.resolve(presetId, lookup::get);

        assertEquals("", p.getName(), "nothing was authored, so the raw field stays empty");
        assertEquals("urbex:tallbuildings", p.getDisplayName(),
                "the fallback is the id, which is what every row showed before the field existed");
    }

    @Test
    void anEmptyStringIsTreatedAsNoNameRatherThanAsABlankLabel() {
        Identifier presetId = id("blank");
        Map<Identifier, PresetRE> lookup = Map.of(presetId, decode("{\"name\": \"\"}"));

        assertEquals("urbex:blank", Presets.resolve(presetId, lookup::get).getDisplayName());
    }

    /**
     * {@code name} inherits like {@code description} does. Documented rather than merely observed:
     * it is the reason every shipped preset declares one, and the reason the two abstract city-style
     * bases deliberately declare none.
     */
    @Test
    void aChildInheritsItsParentsNameWhenItDeclaresNone() {
        Identifier parent = id("default");
        Identifier child = id("largecities");
        Map<Identifier, PresetRE> lookup = Map.of(
                parent, decode("{\"name\": \"Default\"}"),
                child, decode("{\"extends\": \"urbex:default\"}"));

        assertEquals("Default", Presets.resolve(child, lookup::get).getDisplayName());
    }

    @Test
    void aChildsOwnNameWinsOverTheOneItExtends() {
        Identifier parent = id("default");
        Identifier child = id("largecities");
        Map<Identifier, PresetRE> lookup = Map.of(
                parent, decode("{\"name\": \"Default\"}"),
                child, decode("{\"extends\": \"urbex:default\", \"name\": \"Large Cities\"}"));

        assertEquals("Large Cities", Presets.resolve(child, lookup::get).getDisplayName());
    }

    /**
     * The customized-preset path: the Cities tab's "Customize this preset…" entry is published as a
     * {@code PresetRE} overlay built by {@code toRE()}, and rebuilt from it on a Re-Create. A name
     * dropped anywhere along that round trip would rename the row on reload.
     */
    @Test
    void theNameSurvivesCopyAndTheToReRoundTrip() {
        Identifier presetId = id("wasteland");
        Map<Identifier, PresetRE> lookup = Map.of(presetId, decode("{\"name\": \"Wasteland\"}"));
        Preset resolved = Presets.resolve(presetId, lookup::get);

        assertEquals("Wasteland", resolved.copy().getDisplayName());

        Preset rebuilt = Presets.applyOverrides(new Preset(presetId), resolved.toRE());
        assertEquals("Wasteland", rebuilt.getDisplayName());
    }

    /** A name is free text; nothing may try to read it as an id. */
    @Test
    void aNameNeedsNoNamespaceAndMayContainSpacesAndPunctuation() {
        Identifier presetId = id("fancy");
        Map<Identifier, PresetRE> lookup =
                Map.of(presetId, decode("{\"name\": \"Cities & Ruins (heavy)\"}"));

        assertEquals("Cities & Ruins (heavy)", Presets.resolve(presetId, lookup::get).getDisplayName());
    }

    @Test
    void nameIsEncodedUnderItsOwnKeyAndNowhereElse() {
        Preset p = new Preset(id("x"));
        p.setName("Example");

        JsonElement json = PresetRE.CODEC.encodeStart(JsonOps.INSTANCE, p.toRE()).getOrThrow();

        assertTrue(json.getAsJsonObject().has("name"), "toRE() must carry the name");
        assertEquals("Example", json.getAsJsonObject().get("name").getAsString());
        // The metadata block is a MapCodec purely to buy a seventeenth field back from
        // RecordCodecBuilder's sixteen-field limit; it must stay flattened into the file's own
        // object, or every existing preset's 'description' and 'icon' would need renesting.
        assertTrue(json.getAsJsonObject().has("description"),
                "the metadata MapCodec must inline its keys, not nest them");
    }
}

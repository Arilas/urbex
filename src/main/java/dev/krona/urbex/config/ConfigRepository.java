package dev.krona.urbex.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.Urbex;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and writes Urbex's JSON configuration files.
 *
 * <p>Storage only. Nothing here decides anything, publishes anything or is read by generation - it
 * turns files into an {@link UrbexConfig} and back. {@code Config} used to do this alongside global
 * publication, dimension-rule parsing, precedence, saved-data writes, validation and preset caching,
 * all in one class of static fields (issue #130).</p>
 *
 * <p>Both files are read fail-soft, and deliberately so. A config that will not parse leaves the
 * defaults (or, for a world file, the global config) in place and says so in the log, rather than
 * refusing to start: an unreadable settings file is not a reason a player cannot open their world.
 * Contrast the asset compiler, which does refuse - a datapack that does not compile has no defaults
 * to fall back to.</p>
 */
public final class ConfigRepository {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigRepository() {
    }

    /**
     * Reads {@code <config>/urbex/urbex.json} and writes a valid config back in full.
     *
     * <p>The write-back is not incidental: it is how every available option becomes visible to
     * whoever is editing the file, so it goes through {@link UrbexConfig#toFullJson} and names every
     * key whatever its value. The ordinary encoding omits any key that still holds its default,
     * which for a fresh install is all of them - so this used to write {@code {}} while claiming to
     * be "the full, normalized file".</p>
     *
     * <p>An unreadable or invalid existing file is preserved so the player can repair it. Only a
     * missing file is created from defaults; falling back at runtime must not erase settings.</p>
     *
     * @return the parsed config, or {@link UrbexConfig#DEFAULT} if there is no file or it does not
     *         parse
     */
    public static UrbexConfig loadGlobal(Path configDir) {
        Path dir = configDir.resolve("urbex");
        Path file = dir.resolve("urbex.json");
        UrbexConfig config = UrbexConfig.DEFAULT;
        if (!Files.notExists(file)) {
            JsonObject json = readJson(file);
            if (json == null) {
                return config;
            }
            Optional<UrbexConfig> parsed = UrbexConfig.fromJson(json);
            if (parsed.isPresent()) {
                config = parsed.get();
            } else {
                Urbex.getLogger().error("Invalid config in {} - using defaults. Fix or delete the file.", file);
                return config;
            }
        }
        write(file, dir, UrbexConfig.toFullJson(config));
        return config;
    }

    /**
     * Applies {@code <world>/serverconfig/urbex.json} over {@code global}.
     *
     * <p>The merge is per-key and happens at the JSON level, so a world file carries only what it
     * changes. Unlike the global file this one is never written back: it is the
     * player's own list of differences, and normalizing it would fill it with every key they did not
     * ask to change.</p>
     */
    public static UrbexConfig applyWorldOverrides(UrbexConfig global, Path worldRoot) {
        Path file = worldRoot.resolve("serverconfig").resolve("urbex.json");
        if (Files.notExists(file)) {
            return global;
        }
        JsonObject overrides = readJson(file);
        if (overrides == null || overrides.isEmpty()) {
            return global;
        }
        JsonObject merged = UrbexConfig.merge(UrbexConfig.toJson(global), overrides);
        Optional<UrbexConfig> parsed = UrbexConfig.fromJson(merged);
        if (parsed.isEmpty()) {
            Urbex.getLogger().error("Invalid world config in {} - ignoring it.", file);
            return global;
        }
        Urbex.getLogger().info("Applied {} world config override(s) from {}", overrides.size(), file);
        return parsed.get();
    }

    private static void write(Path file, Path dir, JsonObject json) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file, GSON.toJson(json));
        } catch (IOException e) {
            Urbex.getLogger().error("Could not write {}", file, e);
        }
    }

    private static JsonObject readJson(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            Urbex.getLogger().error("Could not read {}", file, e);
            return null;
        }
    }
}

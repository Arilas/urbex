package dev.krona.urbex.commands;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Resolves user-supplied names for command file output to a confined location.
 * Commands must never write to a path derived from raw player input: a name like
 * "whitelist" or "ops" aimed at the server working directory can overwrite server
 * control files (issue #32).
 */
public final class ExportPath {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private ExportPath() {
    }

    /**
     * Returns {@code base/<name>.json} after validating that {@code name} contains only
     * letters, digits, underscores and hyphens.
     *
     * @throws IllegalArgumentException if the name is empty or contains any other character
     */
    public static Path resolve(Path base, String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid file name '" + name + "': only letters, digits, '_' and '-' are allowed");
        }
        return base.resolve(name + ".json");
    }
}

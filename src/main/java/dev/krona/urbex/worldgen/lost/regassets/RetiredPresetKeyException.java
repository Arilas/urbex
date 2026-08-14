package dev.krona.urbex.worldgen.lost.regassets;

/** Marks a retired preset override that must not be downgraded by fail-soft parse boundaries. */
public final class RetiredPresetKeyException extends RuntimeException {

    public RetiredPresetKeyException(String message) {
        super(message);
    }
}

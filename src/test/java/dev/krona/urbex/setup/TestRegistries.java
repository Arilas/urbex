package dev.krona.urbex.setup;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@link RegistryAccess} holding every Urbex registry, derived from {@link CustomRegistries} rather
 * than listed by hand.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Three test files built their own access from a hand-written list of registry keys —
 * {@code AssetCompilerTest.registries}, {@code AssetValidationTest.registriesWithBrokenVariants} and
 * {@code PreviewAssetsTest.registries} — and all three listed <b>13</b> of the 14. The one they omitted
 * was {@code definitions}. Two of the three said in their own javadoc that they held "every registry
 * {@code validate} walks" and "every Urbex registry present".</p>
 *
 * <p>What that cost is measured: {@code V2Palettes.definitions} reads that registry off the
 * {@code RegistryAccess} it is handed, and it uses {@code lookup} rather than {@code lookupOrThrow}
 * deliberately, so an access without the registry is not an error — it is an <em>empty index</em>, which
 * is exactly the state in which every qualified {@code $ref} is refused with {@code DIAG.030}. That is
 * the bug this branch shipped and fixed in Task 9, and with the lookup disabled again the whole suite
 * stayed green: not one test built an access that could tell the difference. 14 of the 30 bundled
 * palettes carry a {@code $ref}, so unwiring it breaks a real world load, and only {@code runDigestCheck}
 * — a manual task, wired into neither {@code test} nor {@code check} — would have noticed.</p>
 *
 * <p>So a list is the wrong shape for this and reflection is the right one, on
 * {@code docs/format/README.md} §1's own argument: a hand-written list is a second copy of a fact the
 * repository already states, and nothing compared the two. {@code RetiredKeysRejectedTest} already read
 * {@code CustomRegistries} this way and correctly counted 14 while its three neighbours counted 13;
 * {@link #COUNT} is that number in one place now rather than in two.</p>
 */
public final class TestRegistries {

    /**
     * How many dynamic registries {@link CustomRegistries} declares.
     * <p>
     * Stated once, here, and checked against the fields by {@link #keys()} on every call — so this
     * constant cannot drift from the class it counts, and a fifteenth registry fails every test that
     * builds an access rather than being quietly left out of one.
     */
    public static final int COUNT = 13;

    private TestRegistries() {
    }

    /**
     * Every {@code ResourceKey<Registry<?>>} {@link CustomRegistries} declares, in declaration order.
     * <p>
     * Read off the {@code _REGISTRY_KEY} fields, which is the same source
     * {@code RetiredKeysRejectedTest.everyDynamicRegistryIsCovered} reads and for the same reason: the
     * fields are what {@code CustomRegistries.register} enumerates, so anything that adds a registry
     * adds a field.
     */
    public static List<ResourceKey<? extends Registry<?>>> keys() {
        List<ResourceKey<? extends Registry<?>>> keys = new ArrayList<>();
        for (Field field : CustomRegistries.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !field.getName().endsWith("_REGISTRY_KEY")) {
                continue;
            }
            try {
                field.setAccessible(true);
                keys.add((ResourceKey<? extends Registry<?>>) field.get(null));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not read " + field.getName(), e);
            }
        }
        assertEquals(COUNT, keys.size(),
                () -> "CustomRegistries declares " + keys.size() + " registries and TestRegistries.COUNT"
                        + " says " + COUNT + "; update the constant and check every access built from it");
        return keys;
    }

    /** The value type {@code X} out of a {@code ResourceKey<Registry<X>>} field, by field name. */
    public static Map<String, Class<?>> valueTypesByFieldName() {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        for (Field field : CustomRegistries.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !field.getName().endsWith("_REGISTRY_KEY")) {
                continue;
            }
            ParameterizedType resourceKey = (ParameterizedType) field.getGenericType();
            ParameterizedType registry = (ParameterizedType) resourceKey.getActualTypeArguments()[0];
            types.put(field.getName(), (Class<?>) registry.getActualTypeArguments()[0]);
        }
        assertEquals(COUNT, types.size(), "one value type per declared registry");
        return types;
    }

    /**
     * An access holding the block registry and all {@link #COUNT} Urbex registries, empty, with the
     * given ones substituted in by key.
     * <p>
     * The block registry is here because compilation resolves every block string against the world's
     * own rather than reaching for a static one ({@code LOAD.003}); a real world always has it, and an
     * access built by hand has to say so.
     */
    public static RegistryAccess with(Registry<?>... populated) {
        Map<ResourceKey<? extends Registry<?>>, Registry<?>> byKey = new LinkedHashMap<>();
        for (ResourceKey<? extends Registry<?>> key : keys()) {
            byKey.put(key, empty(key));
        }
        for (Registry<?> registry : populated) {
            if (!byKey.containsKey(registry.key())) {
                throw new IllegalArgumentException(
                        registry.key() + " is not a registry CustomRegistries declares");
            }
            byKey.put(registry.key(), registry);
        }
        List<Registry<?>> all = new ArrayList<>();
        all.add(BuiltInRegistries.BLOCK);
        all.addAll(byKey.values());
        return new RegistryAccess.ImmutableRegistryAccess(all).freeze();
    }

    /** One empty, frozen registry for a key. */
    @SuppressWarnings("unchecked")
    public static <T> Registry<T> empty(ResourceKey<? extends Registry<?>> key) {
        return new MappedRegistry<T>((ResourceKey<Registry<T>>) key, Lifecycle.stable()).freeze();
    }

    /** A frozen registry holding exactly these entries, keyed under {@code urbex:<path>}. */
    @SuppressWarnings("unchecked")
    public static <T> Registry<T> of(ResourceKey<? extends Registry<?>> key, Map<String, T> entries) {
        MappedRegistry<T> registry =
                new MappedRegistry<>((ResourceKey<Registry<T>>) key, Lifecycle.stable());
        entries.forEach((path, value) -> registry.register(
                ResourceKey.create((ResourceKey<Registry<T>>) key,
                        Identifier.fromNamespaceAndPath("urbex", path)),
                value, net.minecraft.core.RegistrationInfo.BUILT_IN));
        return registry.freeze();
    }

    /**
     * Fails naming every Urbex registry this access does not carry.
     * <p>
     * For a test that builds its access some other way and still wants the guarantee: an absent
     * registry is not an error at any seam that reads one with {@code lookup}, so "it worked" is not
     * evidence that it was there.
     */
    public static void assertHoldsEveryUrbexRegistry(RegistryAccess access) {
        List<String> missing = new ArrayList<>();
        for (ResourceKey<? extends Registry<?>> key : keys()) {
            if (access.lookup(key).isEmpty()) {
                missing.add(key.identifier().toString());
            }
        }
        assertEquals(List.of(), missing,
                "the access is missing Urbex registries, and a seam that reads one with lookup()"
                        + " cannot tell an absent registry from an empty one");
    }
}

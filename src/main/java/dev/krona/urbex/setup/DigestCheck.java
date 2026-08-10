package dev.krona.urbex.setup;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.DigestRunner;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;
import java.util.Set;

/**
 * Headless worldgen regression check: when {@code -Durbex.digestCheck=radius,order,offset} is
 * set, the server generates that chunk square right after startup, prints the driver digest,
 * compares it against {@code -Durbex.digestCheck.expected} (when given), emits a single
 * greppable verdict line and shuts the server down.
 * <p>
 * This exists because the vanilla GameTest server runs a flat world in which the Urbex feature
 * never fires - a real dedicated server on a pinned seed is the only harness that exercises the
 * actual generation path end to end. CI boots it via the {@code runDigestCheck} Gradle task on a
 * fresh copy of the pinned world and greps for {@link #OK}.
 */
public final class DigestCheck {

    public static final String PROP = "urbex.digestCheck";
    public static final String PROP_EXPECTED = "urbex.digestCheck.expected";
    /**
     * When present (value is not consulted, only presence), the check fails if the sampled square
     * contains zero chunks with a planned primary bridge deck. Set on the {@code digestCheckBridge}
     * run configuration only - that window's entire reason to exist is being the one mechanical
     * proof a bridge deck renders, so a future change that moves the window off it must fail loudly
     * here rather than silently pinning a golden hash for a window that has stopped covering one.
     */
    public static final String PROP_REQUIRE_BRIDGE = "urbex.digestCheck.requireBridge";
    public static final String OK = "URBEX-DIGEST-CHECK: OK";
    public static final String FAIL = "URBEX-DIGEST-CHECK: FAIL";

    /** The check parameters: {@code radius,order,offset}, e.g. {@code 3,rowmajor,100}. */
    public record Spec(int radius, String order, int offset) {
        private static final Set<String> ORDERS = Set.of("rowmajor", "reverse", "shuffled");

        public static Spec parse(String value) {
            String[] parts = value.split(",");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Expected radius,order,offset but got '" + value + "'");
            }
            int radius = Integer.parseInt(parts[0].trim());
            if (radius < 1) {
                throw new IllegalArgumentException("Radius must be at least 1, got " + radius);
            }
            String order = parts[1].trim();
            if (!ORDERS.contains(order)) {
                throw new IllegalArgumentException("Order must be one of " + ORDERS + ", got '" + order + "'");
            }
            int offset = Integer.parseInt(parts[2].trim());
            return new Spec(radius, order, offset);
        }
    }

    private DigestCheck() {
    }

    /** No-op unless the {@value #PROP} system property is present. Called once from mod init. */
    public static void registerIfRequested() {
        String value = System.getProperty(PROP);
        if (value == null) {
            return;
        }
        Spec spec = Spec.parse(value);
        String expected = System.getProperty(PROP_EXPECTED);
        boolean requireBridge = System.getProperty(PROP_REQUIRE_BRIDGE) != null;

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                ServerLevel level = server.overworld();
                DigestRunner.Result result = DigestRunner.run(level, spec.radius(), spec.order(), spec.offset());
                String driverLine = result.driverLine(spec.order(), spec.offset());
                Urbex.getLogger().info(driverLine);
                System.out.println(driverLine);

                String actual = String.format("%016x", result.driverDigest());
                if (result.driverBlocks() == 0) {
                    verdict(FAIL + " (no driver writes recorded - is an urbex profile configured for the overworld?)");
                } else if (requireBridge && result.bridgeChunks() == 0) {
                    verdict(FAIL + " (sample window contains zero planned-bridge chunks - it no longer covers "
                            + "the bridge this check exists to guard; relocate the window rather than accepting "
                            + "a new golden here)");
                } else if (expected != null && !expected.trim().toLowerCase(Locale.ROOT).equals(actual)) {
                    verdict(FAIL + String.format(" (expected DRIVERDIGEST=%s, got %s)", expected.trim(), actual));
                } else {
                    verdict(OK + " DRIVERDIGEST=" + actual);
                }
            } catch (Throwable t) {
                Urbex.getLogger().error("Digest check crashed", t);
                verdict(FAIL + " (" + t + ")");
            } finally {
                server.halt(false);
            }
        });
    }

    private static void verdict(String line) {
        Urbex.getLogger().info(line);
        System.out.println(line);
    }
}

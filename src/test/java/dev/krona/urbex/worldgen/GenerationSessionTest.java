package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server half of the lifetime rules (issue #125): one session per running server, and nothing
 * of one server's reachable from the next.
 * <p>
 * The per-level rules - load, unload, reload, epoch retention - are in
 * {@link RuntimeRepositoryTest}. Both suites drive the identities as plain objects because neither
 * {@code MinecraftServer} nor {@code ServerLevel} can be constructed, subclassed usefully or
 * proxied; the session takes its owner as an {@code Object} for exactly that reason, and never
 * dereferences it.
 */
class GenerationSessionTest {

    @BeforeAll
    static void bootstrap() {
        // AssetRegistries.reset(), which opening and closing a session both do, walks the
        // registry-backed asset registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void closeWhateverIsOpen() {
        GenerationSession session = GenerationSession.current();
        if (session != null) {
            GenerationSession.closeFor(session.owner());
        }
    }

    @Test
    void openingASessionInstallsIt() {
        GenerationSession session = GenerationSession.openFor(new Object());

        assertSame(session, GenerationSession.current());
        assertFalse(session.isClosed());
        assertEquals(0, session.loadedLevelCount(), "a fresh session has published no level runtimes");
    }

    /**
     * Two servers in one JVM, in sequence. The second gets a different session with nothing
     * published, and the first is closed rather than left holding a stopped server's levels - the
     * failure mode of the map this replaced, which kept the previous world's dimension info until a
     * counter bump from somewhere else invalidated it.
     */
    @Test
    void aSecondServerInTheSameJvmGetsAFreshSession() {
        Object firstServer = new Object();
        Object secondServer = new Object();
        GenerationSession first = GenerationSession.openFor(firstServer);

        GenerationSession second = GenerationSession.openFor(secondServer);

        assertNotSame(first, second);
        assertSame(second, GenerationSession.current());
        assertTrue(first.isClosed(), "the previous server's runtimes are retired, not inherited");
        assertEquals(0, second.loadedLevelCount());
    }

    @Test
    void closingTheCurrentSessionLeavesNothingToGenerateAgainst() {
        Object server = new Object();
        GenerationSession session = GenerationSession.openFor(server);

        GenerationSession.closeFor(server);

        assertNull(GenerationSession.current());
        assertTrue(session.isClosed());
    }

    /**
     * The same-JVM restart hazard from the other side. A server that stops after a second one has
     * already started must not close the running server's session; without the identity check, the
     * new world would silently generate nothing at all.
     */
    @Test
    void aStoppingServerCannotCloseASessionThatIsNotItsOwn() {
        Object firstServer = new Object();
        Object secondServer = new Object();
        GenerationSession.openFor(firstServer);
        GenerationSession second = GenerationSession.openFor(secondServer);

        GenerationSession.closeFor(firstServer);

        assertSame(second, GenerationSession.current());
        assertFalse(second.isClosed());
    }

    @Test
    void closingWhenNothingIsOpenIsHarmless() {
        GenerationSession.closeFor(new Object());

        assertNull(GenerationSession.current());
    }
}

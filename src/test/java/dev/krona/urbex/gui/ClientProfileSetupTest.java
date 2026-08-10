package dev.krona.urbex.gui;

import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.config.UrbexProfile;
import net.minecraft.server.packs.repository.PackRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Covers issue #66: {@code toggleWorldStyle} used to crash with an {@link IndexOutOfBoundsException}
 * whenever the scanned packs had no {@code urbex/worldstyles} jsons at all (styles.get(0) on an
 * empty list, reached only once a profile is actually selected), and leaked the
 * {@code MultiPackResourceManager} it opened on every call.
 */
class ClientProfileSetupTest {

    private static final String TEST_PROFILE_ID = "toggle-worldstyle-test-profile";

    @AfterEach
    void cleanupProfile() {
        ProfileSetup.STANDARD_PROFILES.remove(TEST_PROFILE_ID);
    }

    @Test
    void toggleWorldStyleDoesNotCrashWhenNoWorldstylesAreVisible() {
        ProfileSetup.STANDARD_PROFILES.put(TEST_PROFILE_ID, new UrbexProfile(TEST_PROFILE_ID, true));
        ClientProfileSetup setup = new ClientProfileSetup(() -> {});
        setup.setProfile(TEST_PROFILE_ID);

        // No RepositorySource registered, so this repository has no available (and so no
        // selected) packs - openAllSelected() is empty, and listResources("urbex/worldstyles", ...)
        // therefore finds nothing. get().isPresent() is true (a profile is selected above), which
        // is what used to reach the unguarded styles.get(0) on an empty list.
        PackRepository emptyRepository = new PackRepository();

        assertDoesNotThrow(() -> setup.toggleWorldStyle(emptyRepository));
    }
}

package com.mystipixel.royalvotes;

import com.mystipixel.royalvotes.VotifierConflict.Installed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VotifierConflictTest {

    private static final Installed SELF = new Installed("RoyalVotes", List.of("Votifier"));

    @Test
    void ownInstanceAfterAReloadIsNotAConflict() {
        // what Paper still holds after PlugManX unloads the old jar and loads the new one
        Installed stale = new Installed("RoyalVotes", List.of("Votifier"));
        assertEquals(Optional.empty(), VotifierConflict.find("RoyalVotes", List.of(stale, SELF)));
    }

    @Test
    void ownInstanceAloneIsNotAConflict() {
        assertEquals(Optional.empty(), VotifierConflict.find("RoyalVotes",
                List.of(SELF, new Installed("PlaceholderAPI", List.of()))));
    }

    @Test
    void nuVotifierIsDetected() {
        // NuVotifier's plugin.yml name is "Votifier"
        assertEquals(Optional.of("Votifier"), VotifierConflict.find("RoyalVotes",
                List.of(SELF, new Installed("Votifier", List.of()))));
    }

    @Test
    void anotherPluginProvidingVotifierIsDetected() {
        assertEquals(Optional.of("SomeVoteReceiver"), VotifierConflict.find("RoyalVotes",
                List.of(SELF, new Installed("SomeVoteReceiver", List.of("votifier")))));
    }
}

package com.mystipixel.royalvotes;

import java.util.List;
import java.util.Optional;

/**
 * Finds another Votifier receiver among the installed plugins. It looks at the plugin list rather than
 * {@code getPlugin("Votifier")}: Paper never drops a {@code provides} alias when a plugin is unloaded, so
 * after a PlugManX reload that lookup still returns the old RoyalVotes instance.
 */
final class VotifierConflict {

    static final String VOTIFIER = "Votifier";

    record Installed(String name, List<String> provides) {
    }

    private VotifierConflict() {
    }

    // the name of the first other plugin that is or provides Votifier; our own name never counts
    static Optional<String> find(String self, Iterable<Installed> plugins) {
        for (Installed plugin : plugins) {
            if (plugin.name().equalsIgnoreCase(self)) {
                continue;
            }
            if (plugin.name().equalsIgnoreCase(VOTIFIER)
                    || plugin.provides().stream().anyMatch(VOTIFIER::equalsIgnoreCase)) {
                return Optional.of(plugin.name());
            }
        }
        return Optional.empty();
    }
}

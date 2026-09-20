package com.mystipixel.royalvotes.reward;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Rewards keyed by a count, shared by streaks and milestones. A key is either an exact number
 * ({@code 7}) or repeating ({@code every-7}); when both match the same count, both pay.
 */
public final class Thresholds {

    private final Map<Integer, Reward> exact = new LinkedHashMap<>();
    private final Map<Integer, Reward> repeating = new LinkedHashMap<>();

    public static Thresholds load(ConfigurationSection section, String where, Logger logger) {
        Thresholds thresholds = new Thresholds();
        if (section == null) {
            return thresholds;
        }
        for (String key : section.getKeys(false)) {
            boolean every = key.startsWith("every-");
            int count;
            try {
                count = Integer.parseInt(every ? key.substring("every-".length()) : key);
            } catch (NumberFormatException notNumber) {
                count = 0;
            }
            if (count <= 0) {
                logger.warning(where + "." + key + ": expected a positive number or every-<number>;"
                        + " skipped.");
                continue;
            }
            (every ? thresholds.repeating : thresholds.exact)
                    .put(count, Reward.load(section.getConfigurationSection(key)));
        }
        return thresholds;
    }

    public List<Reward> matching(int count) {
        List<Reward> matched = new ArrayList<>();
        if (count <= 0) {
            return matched;
        }
        Reward hit = exact.get(count);
        if (hit != null) {
            matched.add(hit);
        }
        for (Map.Entry<Integer, Reward> entry : repeating.entrySet()) {
            if (count % entry.getKey() == 0) {
                matched.add(entry.getValue());
            }
        }
        return matched;
    }

    public int size() {
        return exact.size() + repeating.size();
    }
}

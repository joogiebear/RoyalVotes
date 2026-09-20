package com.mystipixel.royalvotes.data;

/** The streak rule, kept free of Bukkit and of the clock so it can be tested as plain arithmetic. */
public final class Streaks {

    private Streaks() {
    }

    /**
     * The streak after a vote on {@code voteDay}.
     *
     * @param lastDay   epoch day of the previous vote, or {@link PlayerStats#NEVER}
     * @param graceDays whole days a player may miss without losing the streak
     */
    public static int next(int current, long lastDay, long voteDay, int graceDays) {
        if (lastDay == PlayerStats.NEVER) {
            return 1;
        }
        long gap = voteDay - lastDay;
        if (gap <= 0) {
            // A second site on the same day, or a queued vote delivered out of order: the streak is
            // counted in days, so neither moves it.
            return Math.max(current, 1);
        }
        return gap <= 1L + Math.max(0, graceDays) ? current + 1 : 1;
    }
}

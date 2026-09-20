package com.mystipixel.royalvotes.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreaksTest {

    @Test
    void firstVoteStartsAStreak() {
        assertEquals(1, Streaks.next(0, PlayerStats.NEVER, 100, 0));
    }

    @Test
    void nextDayExtends() {
        assertEquals(5, Streaks.next(4, 99, 100, 0));
    }

    @Test
    void secondSiteOnTheSameDayDoesNotMoveIt() {
        assertEquals(4, Streaks.next(4, 100, 100, 0));
    }

    @Test
    void queuedVoteDeliveredOutOfOrderDoesNotMoveIt() {
        assertEquals(4, Streaks.next(4, 100, 98, 0));
    }

    @Test
    void missedDayResets() {
        assertEquals(1, Streaks.next(4, 98, 100, 0));
    }

    @Test
    void graceDaysForgiveAMiss() {
        assertEquals(5, Streaks.next(4, 98, 100, 1));
        assertEquals(1, Streaks.next(4, 97, 100, 1));
    }
}

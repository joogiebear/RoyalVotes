package com.mystipixel.royalvotes.data;

import java.util.UUID;

/** One player's running totals. Mutable; owned by {@link VoteStore} and touched on the main thread only. */
public final class PlayerStats {

    /** {@link #lastVoteDay} before the first vote. */
    public static final long NEVER = Long.MIN_VALUE;

    private final UUID uuid;
    private String name;
    private int total;
    private int streak;
    private int bestStreak;
    private long lastVoteDay = NEVER;
    private long lastVote;

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public int total() {
        return total;
    }

    public void total(int total) {
        this.total = total;
    }

    public int streak() {
        return streak;
    }

    public void streak(int streak) {
        this.streak = streak;
        this.bestStreak = Math.max(bestStreak, streak);
    }

    public int bestStreak() {
        return bestStreak;
    }

    public void bestStreak(int bestStreak) {
        this.bestStreak = bestStreak;
    }

    /** Epoch day (in the configured timezone) of the most recent vote, or {@link #NEVER}. */
    public long lastVoteDay() {
        return lastVoteDay;
    }

    public void lastVoteDay(long lastVoteDay) {
        this.lastVoteDay = lastVoteDay;
    }

    public long lastVote() {
        return lastVote;
    }

    public void lastVote(long lastVote) {
        this.lastVote = lastVote;
    }
}

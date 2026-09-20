package com.vexsoftware.votifier.model;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread for each vote. See {@link Vote} for why this sits in {@code com.vexsoftware}.
 *
 * <p>RoyalVotes fires this only while the voter is online — immediately, or on their next join for a
 * vote that arrived while they were away — because listeners such as libreforge's trigger discard
 * votes for players they cannot find.
 */
public class VotifierEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Vote vote;

    public VotifierEvent(Vote vote) {
        this.vote = vote;
    }

    public VotifierEvent(Vote vote, boolean async) {
        super(async);
        this.vote = vote;
    }

    public Vote getVote() {
        return vote;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

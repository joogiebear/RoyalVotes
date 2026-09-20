package com.mystipixel.royalvotes.data;

/**
 * A vote waiting for its player to come online.
 *
 * @param receivedAt epoch millis when the vote arrived, so a vote cast on Monday and collected on
 *                   Wednesday still counts towards Monday's streak day
 */
public record PendingVote(String service, String username, String address, long receivedAt) {
}

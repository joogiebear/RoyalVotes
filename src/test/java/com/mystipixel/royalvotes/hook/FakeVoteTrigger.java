package com.mystipixel.royalvotes.hook;

import com.vexsoftware.votifier.model.VotifierEvent;

import java.util.ArrayList;
import java.util.List;

// Stands in for libreforge's TriggerRegisterVote: a singleton with a handle(VotifierEvent) listener.
public final class FakeVoteTrigger {

    public static final FakeVoteTrigger INSTANCE = new FakeVoteTrigger();

    // each test loads its own copy of this class, so this starts empty
    public static final List<String> SEEN = new ArrayList<>();

    public void handle(VotifierEvent event) {
        SEEN.add(event.getVote().getServiceName() + ":" + event.getVote().getUsername());
    }
}

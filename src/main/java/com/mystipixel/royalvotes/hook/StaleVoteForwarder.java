package com.mystipixel.royalvotes.hook;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Keeps libreforge's {@code register_vote} trigger counting after a plugin manager reloads RoyalVotes.
 * libreforge's listener stays bound to the {@code VotifierEvent} class of the first jar (its class loader
 * can never swap it), so it no longer sees our events. This calls it directly with an old-class event.
 */
public final class StaleVoteForwarder {

    static final String TRIGGER = "com.willfp.libreforge.integrations.votifier.impl.TriggerRegisterVote";

    private final Object trigger;
    private final Method handle;
    private final Constructor<?> staleVote;
    private final Constructor<?> staleEvent;

    private StaleVoteForwarder(Object trigger, Method handle, Constructor<?> staleVote, Constructor<?> staleEvent) {
        this.trigger = trigger;
        this.handle = handle;
        this.staleVote = staleVote;
        this.staleEvent = staleEvent;
    }

    /**
     * A forwarder when libreforge's trigger is bound to a {@code VotifierEvent} from an earlier RoyalVotes jar,
     * or null when it is bound to ours and Bukkit delivers our events to it as usual.
     *
     * @throws ReflectiveOperationException or {@link LinkageError} when the trigger is bound to an old jar
     *         but cannot be reached; only a restart fixes that
     */
    public static StaleVoteForwarder find(ClassLoader libreforge) throws ReflectiveOperationException {
        return find(libreforge, TRIGGER);
    }

    static StaleVoteForwarder find(ClassLoader libreforge, String triggerClass) throws ReflectiveOperationException {
        Class<?> type = Class.forName(triggerClass, true, libreforge);
        Method handle = null;
        for (Method method : type.getMethods()) {
            if (method.getName().equals("handle") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].getName().equals(VotifierEvent.class.getName())) {
                handle = method;
            }
        }
        if (handle == null) {
            throw new NoSuchMethodException(triggerClass + ".handle(VotifierEvent)");
        }
        Class<?> event = handle.getParameterTypes()[0];
        if (event == VotifierEvent.class) {
            pinVote(libreforge);
            return null;
        }
        // asked through libreforge's loader: the Vote it links getVote() against, which may not be ours
        Class<?> vote = Class.forName(Vote.class.getName(), false, libreforge);
        if (vote.getClassLoader() != event.getClassLoader()) {
            // libreforge first met Vote after the reload, so the old event's getVote() would not link
            throw new LinkageError("libreforge's register_vote listener is bound to an unloaded RoyalVotes jar"
                    + " and resolved Vote from a different one");
        }
        Object instance = type.getField("INSTANCE").get(null);
        return new StaleVoteForwarder(instance, handle,
                vote.getConstructor(String.class, String.class, String.class, String.class),
                event.getConstructor(vote));
    }

    /**
     * Makes libreforge's class loader resolve our {@code Vote} now, while it is the only one. Otherwise it first
     * does so on the first vote it handles, and if that comes after a reload, {@link #find} cannot forward.
     */
    public static void pinVote(ClassLoader libreforge) throws ClassNotFoundException {
        Class.forName(Vote.class.getName(), false, libreforge);
    }

    /**
     * Hands one vote to the old listener. Main thread, like the listener it stands in for.
     *
     * @throws InvocationTargetException when libreforge's listener itself throws
     */
    public void forward(Vote vote) throws ReflectiveOperationException {
        Object oldVote = staleVote.newInstance(vote.getServiceName(), vote.getUsername(), vote.getAddress(),
                vote.getTimeStamp());
        handle.invoke(trigger, staleEvent.newInstance(oldVote));
    }
}

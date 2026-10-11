package com.mystipixel.royalvotes.hook;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plugin class loaders simulated with plain ones: "oldJar" is the unloaded RoyalVotes, "libreforge" defines
 * the trigger and links the com.vexsoftware classes to whichever jar it met first; this test's own loader
 * is the reloaded RoyalVotes.
 */
class StaleVoteForwarderTest {

    private static final String TRIGGER = FakeVoteTrigger.class.getName();
    private static final String VOTE = Vote.class.getName();
    private static final String EVENT = VotifierEvent.class.getName();

    @Test
    void notReloadedNeedsNoForwarding() throws Exception {
        Loader libreforge = new Loader(Set.of(TRIGGER), Map.of());
        assertNull(StaleVoteForwarder.find(libreforge, TRIGGER));
    }

    @Test
    void afterReloadTheVoteReachesTheOldListener() throws Exception {
        Loader oldJar = new Loader(Set.of(VOTE, EVENT), Map.of());
        Loader libreforge = new Loader(Set.of(TRIGGER), Map.of(VOTE, oldJar, EVENT, oldJar));

        // the bug: the listener's event class, and so its handler list, is not the one we fire
        Class<?> bound = Class.forName(EVENT, true, oldJar);
        assertNotSame(VotifierEvent.class, bound);
        assertNotSame(VotifierEvent.getHandlerList(), bound.getMethod("getHandlerList").invoke(null));

        StaleVoteForwarder forwarder = StaleVoteForwarder.find(libreforge, TRIGGER);
        assertNotNull(forwarder);
        forwarder.forward(new Vote("TopG", "Steve", "127.0.0.1", "1700000000"));
        assertEquals(List.of("TopG:Steve"), libreforge.loadClass(TRIGGER).getField("SEEN").get(null));
    }

    @Test
    void afterReloadWithVoteFirstSeenFromTheNewJarItCannotForward() {
        Loader oldJar = new Loader(Set.of(VOTE, EVENT), Map.of());
        // Vote not mapped: libreforge resolves it from this test's loader, the reloaded jar
        Loader libreforge = new Loader(Set.of(TRIGGER), Map.of(EVENT, oldJar));
        assertThrows(LinkageError.class, () -> StaleVoteForwarder.find(libreforge, TRIGGER));
    }

    private static final class Loader extends URLClassLoader {

        private final Set<String> own;
        private final Map<String, ClassLoader> elsewhere;

        Loader(Set<String> own, Map<String, ClassLoader> elsewhere) {
            super(new URL[] {codeSource(Vote.class), codeSource(FakeVoteTrigger.class)},
                    StaleVoteForwarderTest.class.getClassLoader());
            this.own = own;
            this.elsewhere = elsewhere;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            ClassLoader other = elsewhere.get(name);
            if (other != null) {
                return other.loadClass(name);
            }
            if (own.contains(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : findClass(name);
                }
            }
            return super.loadClass(name, resolve);
        }

        private static URL codeSource(Class<?> type) {
            return type.getProtectionDomain().getCodeSource().getLocation();
        }
    }
}

package com.mystipixel.royalvotes.data;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Everything that survives a restart: per-player totals, the offline queue and the party counter, in
 * one {@code data.yml}.
 *
 * <p>Main-thread only. Saving serialises on the main thread (cheap — it is a string) and writes on an
 * async one, through a temp file, so a crash mid-write cannot leave a truncated data file behind.
 */
public final class VoteStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();
    /** lowercase username → votes waiting for them. Keyed by name because that is all a vote carries. */
    private final Map<String, List<PendingVote>> pending = new LinkedHashMap<>();
    private int partyCount;
    private boolean dirty;
    private List<PlayerStats> topCache;
    private final Object writeLock = new Object();

    public VoteStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        stats.clear();
        pending.clear();
        topCache = null;
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        partyCount = yaml.getInt("party.count", 0);

        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                ConfigurationSection entry = players.getConfigurationSection(key);
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException notUuid) {
                    continue;
                }
                if (entry == null) {
                    continue;
                }
                PlayerStats loaded = new PlayerStats(uuid, entry.getString("name", "?"));
                loaded.total(entry.getInt("total"));
                loaded.streak(entry.getInt("streak"));
                loaded.bestStreak(entry.getInt("best-streak"));
                loaded.lastVoteDay(entry.getLong("last-vote-day", PlayerStats.NEVER));
                loaded.lastVote(entry.getLong("last-vote"));
                stats.put(uuid, loaded);
            }
        }

        ConfigurationSection queued = yaml.getConfigurationSection("pending");
        if (queued != null) {
            for (String name : queued.getKeys(false)) {
                List<PendingVote> votes = new ArrayList<>();
                for (Map<?, ?> raw : queued.getMapList(name)) {
                    Object service = raw.get("service");
                    Object received = raw.get("received");
                    if (service == null || !(received instanceof Number time)) {
                        continue;
                    }
                    Object username = raw.get("username");
                    Object address = raw.get("address");
                    votes.add(new PendingVote(service.toString(),
                            username == null ? name : username.toString(),
                            address == null ? "" : address.toString(), time.longValue()));
                }
                if (!votes.isEmpty()) {
                    pending.put(votes.get(0).username().toLowerCase(Locale.ROOT), votes);
                }
            }
        }
    }

    public PlayerStats stats(UUID uuid, String name) {
        PlayerStats found = stats.computeIfAbsent(uuid, id -> new PlayerStats(id, name));
        if (!name.equals(found.name())) {
            found.name(name);
            markDirty();
        }
        return found;
    }

    /** Stats if this player has ever voted; never creates an entry. */
    public PlayerStats peek(UUID uuid) {
        return stats.get(uuid);
    }

    public PlayerStats findByName(String name) {
        for (PlayerStats entry : stats.values()) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    /** Most votes first. Cached until the next change, since placeholders ask for it every tick or so. */
    public List<PlayerStats> top() {
        if (topCache == null) {
            List<PlayerStats> sorted = new ArrayList<>(stats.values());
            sorted.removeIf(entry -> entry.total() <= 0);
            // Lambdas, not method references: total() and name() are overloaded with their setters.
            sorted.sort(Comparator.comparingInt((PlayerStats entry) -> entry.total()).reversed()
                    .thenComparing(entry -> entry.name(), String.CASE_INSENSITIVE_ORDER));
            topCache = List.copyOf(sorted);
        }
        return topCache;
    }

    /**
     * Queue a vote for an offline player.
     *
     * @return false when their queue is full and the vote was dropped
     */
    public boolean enqueue(PendingVote vote, int maxPerPlayer) {
        List<PendingVote> votes = pending.computeIfAbsent(vote.username().toLowerCase(Locale.ROOT),
                name -> new ArrayList<>());
        if (votes.size() >= maxPerPlayer) {
            return false;
        }
        votes.add(vote);
        markDirty();
        return true;
    }

    /** Remove and return everything queued for a name, oldest first. */
    public List<PendingVote> drain(String username) {
        List<PendingVote> votes = pending.remove(username.toLowerCase(Locale.ROOT));
        if (votes == null) {
            return List.of();
        }
        markDirty();
        votes.sort(Comparator.comparingLong(PendingVote::receivedAt));
        return votes;
    }

    public int pendingCount(String username) {
        List<PendingVote> votes = pending.get(username.toLowerCase(Locale.ROOT));
        return votes == null ? 0 : votes.size();
    }

    /** Drop queued votes older than the cutoff — mistyped usernames would otherwise sit here forever. */
    public int expire(long cutoffMillis) {
        int removed = 0;
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            List<PendingVote> votes = iterator.next();
            int before = votes.size();
            votes.removeIf(vote -> vote.receivedAt() < cutoffMillis);
            removed += before - votes.size();
            if (votes.isEmpty()) {
                iterator.remove();
            }
        }
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    public int partyCount() {
        return partyCount;
    }

    public void partyCount(int partyCount) {
        this.partyCount = Math.max(0, partyCount);
        markDirty();
    }

    public void markDirty() {
        dirty = true;
        topCache = null;
    }

    public void saveIfDirty(boolean async) {
        if (!dirty) {
            return;
        }
        dirty = false;
        String serialised = serialise();
        if (async) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(serialised));
        } else {
            write(serialised);
        }
    }

    private String serialise() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("party.count", partyCount);
        for (PlayerStats entry : stats.values()) {
            String base = "players." + entry.uuid();
            yaml.set(base + ".name", entry.name());
            yaml.set(base + ".total", entry.total());
            yaml.set(base + ".streak", entry.streak());
            yaml.set(base + ".best-streak", entry.bestStreak());
            yaml.set(base + ".last-vote-day", entry.lastVoteDay());
            yaml.set(base + ".last-vote", entry.lastVote());
        }
        // Bedrock-style names can start with '.', which is YamlConfiguration's path separator, so the
        // key is made path-safe. Loading never reads the key back: each vote carries its username.
        ConfigurationSection queued = yaml.createSection("pending");
        for (Map.Entry<String, List<PendingVote>> entry : pending.entrySet()) {
            List<Map<String, Object>> votes = new ArrayList<>();
            for (PendingVote vote : entry.getValue()) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("service", vote.service());
                raw.put("username", vote.username());
                raw.put("address", vote.address());
                raw.put("received", vote.receivedAt());
                votes.add(raw);
            }
            queued.set(entry.getKey().replace('.', '_'), votes);
        }
        return yaml.saveToString();
    }

    private void write(String serialised) {
        synchronized (writeLock) {
            try {
                File folder = file.getParentFile();
                if (!folder.isDirectory() && !folder.mkdirs()) {
                    throw new IOException("could not create " + folder);
                }
                File temp = new File(folder, "data.yml.tmp");
                Files.writeString(temp.toPath(), serialised, StandardCharsets.UTF_8);
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failed) {
                plugin.getLogger().log(Level.SEVERE, "Could not save data.yml — vote totals since the"
                        + " last successful save will be lost on restart", failed);
            }
        }
    }
}

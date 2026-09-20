package com.mystipixel.royalvotes;

import com.mystipixel.royalvotes.data.PendingVote;
import com.mystipixel.royalvotes.data.PlayerStats;
import com.mystipixel.royalvotes.data.Streaks;
import com.mystipixel.royalvotes.data.VoteStore;
import com.mystipixel.royalvotes.reward.Reward;
import com.mystipixel.royalvotes.reward.Thresholds;
import com.mystipixel.royalvotes.util.Text;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * What happens to a vote once it has arrived. Main-thread only.
 *
 * <p>A vote is split in two. The part that belongs to the server — the vote-party counter — is
 * counted the moment it arrives. The part that belongs to the player — totals, streak, rewards, and
 * the {@link VotifierEvent} other plugins listen for — waits until they are online, because a reward
 * command aimed at an offline player fails and libreforge's trigger ignores them outright.
 */
public final class VoteService {

    /** Java names, plus the '.' or '*' prefix Geyser/Floodgate servers put on Bedrock players. */
    private static final Pattern USERNAME = Pattern.compile("[.*]?[A-Za-z0-9_]{1,16}");
    private static final Pattern UNSAFE_SERVICE = Pattern.compile("[^A-Za-z0-9._-]");

    /** A chance-based extra on top of the normal vote reward. */
    private record Bonus(String id, double chance, Reward reward) {
    }

    private final RoyalVotesPlugin plugin;
    private final VoteStore store;

    private ZoneId zone = ZoneId.systemDefault();
    private int maxQueued;
    private long expireMillis;
    private Reward voteReward = Reward.NONE;
    private final Map<String, Reward> serviceRewards = new LinkedHashMap<>();
    private final List<Bonus> bonuses = new ArrayList<>();
    private boolean streaksEnabled;
    private int graceDays;
    private Thresholds streakRewards = new Thresholds();
    private Thresholds milestoneRewards = new Thresholds();
    private boolean partyEnabled;
    private int partyRequired;
    private final Set<Integer> partyAnnounceAt = new HashSet<>();
    private String partyAnnounce = "";
    private Reward partyPlayerReward = Reward.NONE;
    private List<String> partyGlobalCommands = List.of();
    private String partyBroadcast = "";

    public VoteService(RoyalVotesPlugin plugin, VoteStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void reload(FileConfiguration config) {
        String zoneName = config.getString("settings.timezone", "");
        zone = ZoneId.systemDefault();
        if (zoneName != null && !zoneName.isBlank()) {
            try {
                zone = ZoneId.of(zoneName);
            } catch (DateTimeException unknown) {
                plugin.getLogger().warning("settings.timezone '" + zoneName + "' is not a timezone id"
                        + " (expected e.g. America/Chicago); using the server's.");
            }
        }
        maxQueued = Math.max(1, config.getInt("offline.max-per-player", 20));
        expireMillis = Math.max(1, config.getInt("offline.expire-days", 7)) * 86_400_000L;

        voteReward = Reward.load(config.getConfigurationSection("rewards.vote"));
        serviceRewards.clear();
        ConfigurationSection services = config.getConfigurationSection("rewards.services");
        if (services != null) {
            // Keyed by an id with the site's name inside, because real service names contain dots
            // ("PlanetMinecraft.com") and a dot in a YAML key is a path separator to Bukkit.
            for (String id : services.getKeys(false)) {
                ConfigurationSection entry = services.getConfigurationSection(id);
                String service = entry == null ? null : entry.getString("service");
                if (service == null || service.isBlank()) {
                    plugin.getLogger().warning("rewards.services." + id + ": missing 'service'; skipped.");
                    continue;
                }
                serviceRewards.put(sanitiseService(service).toLowerCase(), Reward.load(entry));
            }
        }
        bonuses.clear();
        ConfigurationSection bonus = config.getConfigurationSection("rewards.bonus");
        if (bonus != null) {
            for (String id : bonus.getKeys(false)) {
                ConfigurationSection entry = bonus.getConfigurationSection(id);
                if (entry != null) {
                    bonuses.add(new Bonus(id, entry.getDouble("chance", 0), Reward.load(entry)));
                }
            }
        }

        streaksEnabled = config.getBoolean("streaks.enabled", true);
        graceDays = Math.max(0, config.getInt("streaks.grace-days", 0));
        streakRewards = Thresholds.load(config.getConfigurationSection("streaks.rewards"),
                "streaks.rewards", plugin.getLogger());
        milestoneRewards = Thresholds.load(config.getConfigurationSection("milestones"),
                "milestones", plugin.getLogger());

        partyEnabled = config.getBoolean("party.enabled", true);
        partyRequired = Math.max(1, config.getInt("party.required", 50));
        partyAnnounceAt.clear();
        partyAnnounceAt.addAll(config.getIntegerList("party.announce-remaining"));
        partyAnnounce = config.getString("party.announce-message", "");
        partyPlayerReward = Reward.load(config.getConfigurationSection("party.player-reward"));
        partyGlobalCommands = List.copyOf(config.getStringList("party.global-commands"));
        partyBroadcast = config.getString("party.broadcast", "");
    }

    /** Entry point for every vote, real or {@code /royalvotes fakevote}. */
    public void receive(Vote vote) {
        String username = vote.getUsername() == null ? "" : vote.getUsername().trim();
        if (!USERNAME.matcher(username).matches()) {
            plugin.getLogger().warning("Ignored a vote from " + vote.getServiceName()
                    + " for an impossible username (" + username.length() + " characters).");
            return;
        }
        String service = sanitiseService(vote.getServiceName());
        if (plugin.debug()) {
            plugin.getLogger().info("Vote: " + username + " via " + service);
        }

        countTowardsParty();

        PendingVote pending = new PendingVote(service, username,
                vote.getAddress() == null ? "" : vote.getAddress(), System.currentTimeMillis());
        Player player = Bukkit.getPlayerExact(username);
        if (player != null) {
            deliver(player, pending);
        } else if (!store.enqueue(pending, maxQueued)) {
            plugin.getLogger().warning(username + " already has " + maxQueued + " votes queued;"
                    + " dropped one from " + service + ".");
        }
    }

    /** Pay out whatever arrived while this player was away. */
    public void deliverQueued(Player player) {
        List<PendingVote> votes = store.drain(player.getName());
        if (votes.isEmpty()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - expireMillis;
        int delivered = 0;
        for (PendingVote vote : votes) {
            if (vote.receivedAt() >= cutoff) {
                deliver(player, vote);
                delivered++;
            }
        }
        if (delivered > 0) {
            String message = plugin.message("queued-delivered");
            if (!message.isEmpty()) {
                player.sendMessage(Text.chat(message.replace("%amount%", String.valueOf(delivered))));
            }
        }
    }

    private void deliver(Player player, PendingVote vote) {
        PlayerStats stats = store.stats(player.getUniqueId(), player.getName());
        long voteDay = Instant.ofEpochMilli(vote.receivedAt()).atZone(zone).toLocalDate().toEpochDay();

        int previousStreak = stats.streak();
        boolean newStreakDay = stats.lastVoteDay() == PlayerStats.NEVER || voteDay > stats.lastVoteDay();
        stats.total(stats.total() + 1);
        stats.streak(Streaks.next(previousStreak, stats.lastVoteDay(), voteDay, graceDays));
        stats.lastVoteDay(Math.max(stats.lastVoteDay(), voteDay));
        stats.lastVote(Math.max(stats.lastVote(), vote.receivedAt()));
        store.markDirty();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getName());
        values.put("service", vote.service());
        values.put("total", String.valueOf(stats.total()));
        values.put("streak", String.valueOf(stats.streak()));
        values.put("best_streak", String.valueOf(stats.bestStreak()));

        serviceRewards.getOrDefault(vote.service().toLowerCase(), voteReward).give(player, values);
        for (Bonus bonus : bonuses) {
            if (ThreadLocalRandom.current().nextDouble(100) < bonus.chance()) {
                bonus.reward().give(player, values);
            }
        }
        // Once per day, not once per site: five sites on day 7 must not pay the day-7 reward five times.
        if (streaksEnabled && newStreakDay) {
            for (Reward reward : streakRewards.matching(stats.streak())) {
                reward.give(player, values);
            }
        }
        for (Reward reward : milestoneRewards.matching(stats.total())) {
            reward.give(player, values);
        }

        // Last, so a listener that throws cannot cost the player the rewards above.
        Vote event = new Vote(vote.service(), player.getName(), vote.address(),
                String.valueOf(vote.receivedAt() / 1000));
        Bukkit.getPluginManager().callEvent(new VotifierEvent(event));
    }

    private void countTowardsParty() {
        if (!partyEnabled) {
            return;
        }
        int count = store.partyCount() + 1;
        if (count < partyRequired) {
            store.partyCount(count);
            int remaining = partyRequired - count;
            if (partyAnnounceAt.contains(remaining) && !partyAnnounce.isEmpty()) {
                Bukkit.broadcast(Text.chat(partyAnnounce
                        .replace("%remaining%", String.valueOf(remaining))
                        .replace("%required%", String.valueOf(partyRequired))));
            }
            return;
        }
        store.partyCount(0);
        startParty();
    }

    public void startParty() {
        if (!partyBroadcast.isEmpty()) {
            Bukkit.broadcast(Text.chat(partyBroadcast));
        }
        for (String command : partyGlobalCommands) {
            if (!command.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        command.startsWith("/") ? command.substring(1) : command);
            }
        }
        for (Player online : List.copyOf(Bukkit.getOnlinePlayers())) {
            partyPlayerReward.give(online, Map.of("player", online.getName()));
        }
    }

    /** Housekeeping, run on the autosave timer. */
    public void expireQueue() {
        int removed = store.expire(System.currentTimeMillis() - expireMillis);
        if (removed > 0 && plugin.debug()) {
            plugin.getLogger().info("Expired " + removed + " queued vote(s).");
        }
    }

    public boolean votedToday(PlayerStats stats) {
        return stats != null && stats.lastVoteDay() == today();
    }

    /** The streak as it stands now: a stored streak the player has since let lapse reads as 0. */
    public int liveStreak(PlayerStats stats) {
        if (stats == null || stats.lastVoteDay() == PlayerStats.NEVER) {
            return 0;
        }
        return today() - stats.lastVoteDay() <= 1L + graceDays ? stats.streak() : 0;
    }

    private long today() {
        return Instant.now().atZone(zone).toLocalDate().toEpochDay();
    }

    public boolean partyEnabled() {
        return partyEnabled;
    }

    public int partyRequired() {
        return partyRequired;
    }

    public int rewardCount() {
        return 1 + serviceRewards.size() + bonuses.size() + streakRewards.size() + milestoneRewards.size();
    }

    /** Service names end up inside console commands, so they are reduced to characters that cannot break one. */
    static String sanitiseService(String service) {
        if (service == null || service.isBlank()) {
            return "unknown";
        }
        String clean = UNSAFE_SERVICE.matcher(service.trim()).replaceAll("");
        return clean.isEmpty() ? "unknown" : clean;
    }
}

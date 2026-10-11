package com.mystipixel.royalvotes;

import com.mystipixel.royalvotes.command.RoyalVotesCommand;
import com.mystipixel.royalvotes.command.VoteCommand;
import com.mystipixel.royalvotes.data.VoteStore;
import com.mystipixel.royalvotes.hook.LibreforgeHook;
import com.mystipixel.royalvotes.hook.VotesExpansion;
import com.mystipixel.royalvotes.net.RsaKeys;
import com.mystipixel.royalvotes.net.VoteServer;
import com.mystipixel.royalvotes.util.Text;
import com.vexsoftware.votifier.model.Vote;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Vote receiver and vote-reward plugin in one jar. It speaks the Votifier protocols, fires the standard
 * {@code VotifierEvent}, and holds votes for offline players until they join.
 */
public final class RoyalVotesPlugin extends JavaPlugin implements Listener {

    private static final long HOUSEKEEPING_TICKS = 20L * 60;
    // identifies the plugin, not the server, so it is fixed rather than configurable
    private static final int BSTATS_PLUGIN_ID = 34176;

    private VoteStore store;
    private VoteService votes;
    private VoteServer server;
    private enum ListenerState { NOT_STARTED, DISABLED, LISTENING, FAILED }

    private ListenerState listenerState = ListenerState.NOT_STARTED;
    private String listenerHost = "";
    private int listenerPort;
    private boolean listenerV1;
    private LibreforgeHook.Result libreforge = LibreforgeHook.Result.NOT_INSTALLED;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (Bukkit.getPluginManager().getPlugin("Votifier") != null
                && Bukkit.getPluginManager().getPlugin("Votifier") != this) {
            // Two receivers would fight over the port and both define the com.vexsoftware classes.
            getLogger().severe("Another Votifier plugin is installed. RoyalVotes replaces it: remove"
                    + " NuVotifier/Votifier (its rsa folder can be copied into plugins/RoyalVotes) and"
                    + " restart. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.store = new VoteStore(this);
        store.load();
        this.votes = new VoteService(this, store);
        votes.reload(getConfig());

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        VoteCommand voteCommand = new VoteCommand(this);
        register("vote", voteCommand);
        register("votetop", voteCommand);
        RoyalVotesCommand admin = new RoyalVotesCommand(this);
        PluginCommand adminCommand = getCommand("royalvotes");
        if (adminCommand != null) {
            adminCommand.setExecutor(admin);
            adminCommand.setTabCompleter(admin);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new VotesExpansion(this).register();
        }
        hookLibreforge();
        startListener();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            votes.expireQueue();
            store.saveIfDirty(true);
        }, HOUSEKEEPING_TICKS, HOUSEKEEPING_TICKS);

        setupMetrics();
        getLogger().info("RoyalVotes enabled, " + votes.rewardCount() + " reward(s) configured.");
    }

    // owners who want no reporting disable it globally in plugins/bStats/config.yml
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        // The enum name, not libreforgeSummary(): that is a sentence for an admin, not a pie label.
        metrics.addCustomChart(new SimplePie("libreforge_hook",
                () -> libreforge.name().toLowerCase(java.util.Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("v1_enabled",
                () -> String.valueOf(getConfig().getBoolean("listener.v1-enabled", true))));
        metrics.addCustomChart(new SimplePie("vote_party",
                () -> String.valueOf(votes.partyEnabled())));
        metrics.addCustomChart(new SimplePie("placeholderapi",
                () -> String.valueOf(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))));
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop();
        }
        if (store != null) {
            store.saveIfDirty(false);
        }
    }

    // libreforge is loaded by whichever eco plugin enables first, possibly after us, so retry when it
    // enables. Both chances come before eco plugins compile their configs on the first tick.
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("libreforge")) {
            hookLibreforge();
        }
    }

    private void hookLibreforge() {
        if (libreforge == LibreforgeHook.Result.HOOKED) {
            return;
        }
        libreforge = LibreforgeHook.hook(getLogger());
        if (libreforge == LibreforgeHook.Result.HOOKED) {
            getLogger().info("Enabled libreforge's register_vote trigger. EcoBattlepass and other eco"
                    + " plugins can now react to votes.");
        }
    }

    public void reload() {
        reloadConfig();
        votes.reload(getConfig());
        startListener();
    }

    private void startListener() {
        if (server != null) {
            server.stop();
            server = null;
        }
        ConfigurationSection config = getConfig().getConfigurationSection("listener");
        if (config == null || !config.getBoolean("enabled", true)) {
            listenerState = ListenerState.DISABLED;
            return;
        }
        String host = config.getString("host", "0.0.0.0");
        int port = config.getInt("port", 8192);
        listenerHost = host;
        listenerPort = port;

        Map<String, String> tokens = loadTokens(config);
        KeyPair keys = null;
        if (config.getBoolean("v1-enabled", true)) {
            try {
                keys = RsaKeys.loadOrCreate(new File(getDataFolder(), "rsa"));
            } catch (Exception unreadable) {
                getLogger().log(Level.SEVERE, "Could not load or create the RSA keys in rsa/, v1 votes"
                        + " will be refused until this is fixed", unreadable);
            }
        }

        server = new VoteServer(getLogger(), host, port, keys == null ? null : keys.getPrivate(),
                service -> tokens.getOrDefault(service, tokens.get("default")),
                this::onVoteFromNetwork, debug());
        try {
            server.start();
            listenerState = ListenerState.LISTENING;
            listenerV1 = keys != null;
            getLogger().info("Listening for votes on " + host + ":" + port + " (v2 tokens"
                    + (keys == null ? "" : " + v1 RSA") + ").");
        } catch (Exception unbindable) {
            server = null;
            listenerState = ListenerState.FAILED;
            getLogger().severe("Could not listen on " + host + ":" + port + " (" + unbindable.getMessage()
                    + "). Is another process using the port? Votes will not arrive until this is fixed.");
        }
    }

    // deep keys: Bukkit splits "PlanetMinecraft.com" at the dot and getValues(true) joins it back
    private Map<String, String> loadTokens(ConfigurationSection listener) {
        Map<String, String> tokens = new HashMap<>();
        ConfigurationSection section = listener.getConfigurationSection("tokens");
        if (section != null) {
            for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
                if (entry.getValue() instanceof String token && !token.isBlank()) {
                    tokens.put(entry.getKey(), token);
                }
            }
        }
        if (!tokens.containsKey("default")) {
            String generated = new BigInteger(130, new SecureRandom()).toString(32);
            getConfig().set("listener.tokens.default", generated);
            saveConfig();
            tokens.put("default", generated);
            getLogger().info("Generated a v2 token; it is in config.yml under listener.tokens.default.");
        }
        return Map.copyOf(tokens);
    }

    // called on a connection thread
    private void onVoteFromNetwork(Vote vote) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, () -> votes.receive(vote));
        }
    }

    private void register(String name, VoteCommand executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
        }
    }

    /**
     * A line from the {@code messages} section with {@code %name%} placeholders filled from name/value pairs,
     * or empty when the owner has blanked it to silence it. A key missing from the server's config.yml uses
     * the bundled default.
     */
    public String message(String key, String... replacements) {
        return Text.fill(getConfig().getString("messages." + key), replacements);
    }

    public boolean debug() {
        return getConfig().getBoolean("settings.debug", false);
    }

    public String listenerSummary() {
        String port = String.valueOf(listenerPort);
        return switch (listenerState) {
            case NOT_STARTED -> message("admin.listener.not-started");
            case DISABLED -> message("admin.listener.disabled");
            case LISTENING -> listenerV1
                    ? message("admin.listener.v1-and-v2", "host", listenerHost, "port", port)
                    : message("admin.listener.v2-only", "host", listenerHost, "port", port);
            case FAILED -> message("admin.listener.failed", "host", listenerHost, "port", port);
        };
    }

    public String libreforgeSummary() {
        return switch (libreforge) {
            case HOOKED, ALREADY_LOADED -> message("admin.libreforge.enabled");
            case NOT_INSTALLED -> message("admin.libreforge.not-installed");
            case FAILED -> message("admin.libreforge.failed");
        };
    }

    public VoteStore store() {
        return store;
    }

    public VoteService votes() {
        return votes;
    }
}

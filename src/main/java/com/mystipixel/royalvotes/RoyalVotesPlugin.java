package com.mystipixel.royalvotes;

import com.mystipixel.royalvotes.command.RoyalVotesCommand;
import com.mystipixel.royalvotes.command.VoteCommand;
import com.mystipixel.royalvotes.data.VoteStore;
import com.mystipixel.royalvotes.hook.LibreforgeHook;
import com.mystipixel.royalvotes.hook.VotesExpansion;
import com.mystipixel.royalvotes.net.RsaKeys;
import com.mystipixel.royalvotes.net.VoteServer;
import com.vexsoftware.votifier.model.Vote;
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
 * A vote receiver and a vote-reward plugin in one jar.
 *
 * <p>It speaks the Votifier protocols itself and fires the standard {@code VotifierEvent}, so nothing
 * else needs installing for votes to arrive — and it holds votes for offline players until they join,
 * which a bare Votifier does not, and which is the difference between a battlepass vote task that
 * counts every vote and one that counts only the votes cast while logged in.
 */
public final class RoyalVotesPlugin extends JavaPlugin implements Listener {

    private static final long HOUSEKEEPING_TICKS = 20L * 60;

    private VoteStore store;
    private VoteService votes;
    private VoteServer server;
    private String listenerSummary = "not started";
    private LibreforgeHook.Result libreforge = LibreforgeHook.Result.NOT_INSTALLED;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (Bukkit.getPluginManager().getPlugin("Votifier") != null
                && Bukkit.getPluginManager().getPlugin("Votifier") != this) {
            // Two receivers would fight over the port and both define the com.vexsoftware classes.
            getLogger().severe("Another Votifier plugin is installed. RoyalVotes replaces it — remove"
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

        getLogger().info("RoyalVotes enabled — " + votes.rewardCount() + " reward(s) configured.");
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

    /**
     * libreforge is loaded at runtime by whichever eco plugin enables first, which may be after us, so
     * the hook is tried at our enable and again whenever libreforge itself enables. Both happen before
     * eco plugins compile their configs on the first tick, which is when the trigger has to exist.
     */
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
            getLogger().info("Enabled libreforge's register_vote trigger — EcoBattlepass and other eco"
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
            listenerSummary = "disabled in config";
            return;
        }
        String host = config.getString("host", "0.0.0.0");
        int port = config.getInt("port", 8192);

        Map<String, String> tokens = loadTokens(config);
        KeyPair keys = null;
        if (config.getBoolean("v1-enabled", true)) {
            try {
                keys = RsaKeys.loadOrCreate(new File(getDataFolder(), "rsa"));
            } catch (Exception unreadable) {
                getLogger().log(Level.SEVERE, "Could not load or create the RSA keys in rsa/ — v1 votes"
                        + " will be refused until this is fixed", unreadable);
            }
        }

        server = new VoteServer(getLogger(), host, port, keys == null ? null : keys.getPrivate(),
                service -> tokens.getOrDefault(service, tokens.get("default")),
                this::onVoteFromNetwork, debug());
        try {
            server.start();
            listenerSummary = host + ":" + port + " (v2 tokens" + (keys == null ? "" : " + v1 RSA") + ")";
            getLogger().info("Listening for votes on " + listenerSummary + ".");
        } catch (Exception unbindable) {
            server = null;
            listenerSummary = "FAILED to bind " + host + ":" + port;
            getLogger().severe("Could not listen on " + host + ":" + port + " (" + unbindable.getMessage()
                    + "). Is another process using the port? Votes will not arrive until this is fixed.");
        }
    }

    /**
     * Tokens by service name, generating the default on first run.
     *
     * <p>Read with deep keys: Bukkit splits "PlanetMinecraft.com" into nested sections at the dot, and
     * {@code getValues(true)} joins them back into the name the vote site will actually send.
     */
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

    /** Called on a connection thread. */
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

    /** A line from the {@code messages} section, or empty when the owner has blanked it to silence it. */
    public String message(String key) {
        return getConfig().getString("messages." + key, "");
    }

    /** Logs each vote and each rejected connection. */
    public boolean debug() {
        return getConfig().getBoolean("settings.debug", false);
    }

    public String listenerSummary() {
        return listenerSummary;
    }

    public String libreforgeSummary() {
        return switch (libreforge) {
            case HOOKED, ALREADY_LOADED -> "enabled";
            case NOT_INSTALLED -> "libreforge not installed";
            case FAILED -> "FAILED — see the startup log";
        };
    }

    public VoteStore store() {
        return store;
    }

    public VoteService votes() {
        return votes;
    }
}

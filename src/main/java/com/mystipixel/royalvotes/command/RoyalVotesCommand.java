package com.mystipixel.royalvotes.command;

import com.mystipixel.royalvotes.RoyalVotesPlugin;
import com.mystipixel.royalvotes.data.PlayerStats;
import com.mystipixel.royalvotes.util.Text;
import com.vexsoftware.votifier.model.Vote;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /royalvotes} admin tools. */
public final class RoyalVotesCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "status", "fakevote", "stats", "party");

    private final RoyalVotesPlugin plugin;

    public RoyalVotesCommand(RoyalVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                say(sender, "&aReloaded. &7Listener: " + plugin.listenerSummary());
            }
            case "status" -> {
                say(sender, "&6RoyalVotes &7" + plugin.getPluginMeta().getVersion());
                say(sender, "&7Listener: &f" + plugin.listenerSummary());
                say(sender, "&7libreforge register_vote: &f" + plugin.libreforgeSummary());
                say(sender, "&7Vote party: &f" + (plugin.votes().partyEnabled()
                        ? plugin.store().partyCount() + "/" + plugin.votes().partyRequired() : "off"));
            }
            case "fakevote" -> {
                if (args.length < 2) {
                    say(sender, "&cUsage: /" + label + " fakevote <player> [service]");
                    return true;
                }
                // Goes through the same door as a real vote, so it exercises the queue, the rewards
                // and the VotifierEvent — which is what makes it a useful test of a battlepass task.
                String service = args.length > 2 ? args[2] : "fakevote";
                plugin.votes().receive(new Vote(service, args[1], "127.0.0.1",
                        String.valueOf(System.currentTimeMillis() / 1000)));
                say(sender, "&aSent a test vote for &f" + args[1] + "&a via &f" + service + "&a."
                        + (Bukkit.getPlayerExact(args[1]) == null ? " &7They are offline, so it is queued." : ""));
            }
            case "stats" -> {
                if (args.length < 2) {
                    say(sender, "&cUsage: /" + label + " stats <player>");
                    return true;
                }
                PlayerStats stats = plugin.store().findByName(args[1]);
                int queued = plugin.store().pendingCount(args[1]);
                if (stats == null) {
                    say(sender, "&f" + args[1] + " &7has no delivered votes. Queued: &f" + queued);
                    return true;
                }
                say(sender, "&f" + stats.name() + "&7 — total &f" + stats.total() + "&7, streak &f"
                        + plugin.votes().liveStreak(stats) + "&7 (best &f" + stats.bestStreak()
                        + "&7), queued &f" + queued);
            }
            case "party" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("start")) {
                    plugin.store().partyCount(0);
                    plugin.votes().startParty();
                    say(sender, "&aVote party started.");
                } else if (args.length >= 3 && args[1].equalsIgnoreCase("set")) {
                    try {
                        plugin.store().partyCount(Integer.parseInt(args[2]));
                        say(sender, "&aParty counter set to &f" + plugin.store().partyCount() + "&a.");
                    } catch (NumberFormatException notNumber) {
                        say(sender, "&c'" + args[2] + "' is not a number.");
                    }
                } else {
                    say(sender, "&cUsage: /" + label + " party <start|set <count>>");
                }
            }
            default -> say(sender, "&cUsage: /" + label + " <" + String.join("|", SUBCOMMANDS) + ">");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("fakevote") || args[0].equalsIgnoreCase("stats"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                options.add(online.getName());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            options.addAll(List.of("start", "set"));
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }

    private static void say(CommandSender sender, String message) {
        sender.sendMessage(Text.chat(message));
    }
}

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
                say(sender, plugin.message("admin.reloaded", "listener", plugin.listenerSummary()));
            }
            case "status" -> {
                say(sender, plugin.message("admin.status.header", "version", plugin.getPluginMeta().getVersion()));
                say(sender, plugin.message("admin.status.listener", "listener", plugin.listenerSummary()));
                say(sender, plugin.message("admin.status.libreforge", "libreforge", plugin.libreforgeSummary()));
                String party = plugin.votes().partyEnabled()
                        ? plugin.message("admin.status.party-progress",
                                "current", String.valueOf(plugin.store().partyCount()),
                                "required", String.valueOf(plugin.votes().partyRequired()))
                        : plugin.message("admin.status.party-off");
                say(sender, plugin.message("admin.status.party", "party", party));
            }
            case "fakevote" -> {
                if (args.length < 2) {
                    say(sender, plugin.message("admin.fakevote.usage", "label", label));
                    return true;
                }
                // same path as a real vote: exercises the queue, rewards and VotifierEvent (battlepass tasks)
                String service = args.length > 2 ? args[2] : "fakevote";
                plugin.votes().receive(new Vote(service, args[1], "127.0.0.1",
                        String.valueOf(System.currentTimeMillis() / 1000)));
                boolean offline = Bukkit.getPlayerExact(args[1]) == null;
                say(sender, offline
                        ? plugin.message("admin.fakevote.sent-queued", "player", args[1], "service", service)
                        : plugin.message("admin.fakevote.sent", "player", args[1], "service", service));
            }
            case "stats" -> {
                if (args.length < 2) {
                    say(sender, plugin.message("admin.stats.usage", "label", label));
                    return true;
                }
                PlayerStats stats = plugin.store().findByName(args[1]);
                int queued = plugin.store().pendingCount(args[1]);
                if (stats == null) {
                    say(sender, plugin.message("admin.stats.none", "player", args[1], "queued", String.valueOf(queued)));
                    return true;
                }
                say(sender, plugin.message("admin.stats.line", "player", stats.name(),
                        "total", String.valueOf(stats.total()),
                        "streak", String.valueOf(plugin.votes().liveStreak(stats)),
                        "best", String.valueOf(stats.bestStreak()), "queued", String.valueOf(queued)));
            }
            case "party" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("start")) {
                    plugin.store().partyCount(0);
                    plugin.votes().startParty();
                    say(sender, plugin.message("admin.party.started"));
                } else if (args.length >= 3 && args[1].equalsIgnoreCase("set")) {
                    try {
                        plugin.store().partyCount(Integer.parseInt(args[2]));
                        say(sender, plugin.message("admin.party.set", "count", String.valueOf(plugin.store().partyCount())));
                    } catch (NumberFormatException notNumber) {
                        say(sender, plugin.message("admin.party.not-a-number", "value", args[2]));
                    }
                } else {
                    say(sender, plugin.message("admin.party.usage", "label", label));
                }
            }
            default -> say(sender, plugin.message("admin.usage", "label", label));
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
        if (!message.isEmpty()) {
            sender.sendMessage(Text.chat(message));
        }
    }
}

package com.mystipixel.royalvotes.command;

import com.mystipixel.royalvotes.RoyalVotesPlugin;
import com.mystipixel.royalvotes.data.PlayerStats;
import com.mystipixel.royalvotes.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** {@code /vote} — the vote links, clickable — and {@code /votetop}. */
public final class VoteCommand implements CommandExecutor {

    private static final int PAGE_SIZE = 10;

    private final RoyalVotesPlugin plugin;

    public VoteCommand(RoyalVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("votetop")) {
            top(sender, args);
        } else {
            links(sender);
        }
        return true;
    }

    private void links(CommandSender sender) {
        FileConfiguration config = plugin.getConfig();
        PlayerStats stats = sender instanceof Player player ? plugin.store().peek(player.getUniqueId()) : null;
        for (String line : config.getStringList("links.header")) {
            sender.sendMessage(Text.chat(fill(line, stats)));
        }
        String format = config.getString("links.line", "&8» &e%name%");
        for (Map<?, ?> site : config.getMapList("links.sites")) {
            Object name = site.get("name");
            Object url = site.get("url");
            if (name == null || url == null) {
                continue;
            }
            String link = url.toString();
            // Only web links become clickable: a config typo must not turn into some other click action.
            boolean web = link.startsWith("https://") || link.startsWith("http://");
            Component line = Text.chat(format.replace("%name%", name.toString()).replace("%url%", link));
            sender.sendMessage(web ? line.clickEvent(ClickEvent.openUrl(link)) : line);
        }
        for (String line : config.getStringList("links.footer")) {
            sender.sendMessage(Text.chat(fill(line, stats)));
        }
    }

    private String fill(String line, PlayerStats stats) {
        return line
                .replace("%total%", String.valueOf(stats == null ? 0 : stats.total()))
                .replace("%streak%", String.valueOf(plugin.votes().liveStreak(stats)))
                .replace("%party_current%", String.valueOf(plugin.store().partyCount()))
                .replace("%party_required%", String.valueOf(plugin.votes().partyRequired()));
    }

    private void top(CommandSender sender, String[] args) {
        List<PlayerStats> top = plugin.store().top();
        int pages = Math.max(1, (top.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException notNumber) {
                page = 1;
            }
        }
        page = Math.min(Math.max(page, 1), pages);

        sender.sendMessage(Text.chat(plugin.message("top-header")
                .replace("%page%", String.valueOf(page)).replace("%pages%", String.valueOf(pages))));
        if (top.isEmpty()) {
            sender.sendMessage(Text.chat(plugin.message("top-empty")));
            return;
        }
        String format = plugin.message("top-line");
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(top.size(), page * PAGE_SIZE); i++) {
            PlayerStats entry = top.get(i);
            sender.sendMessage(Text.chat(format.replace("%rank%", String.valueOf(i + 1))
                    .replace("%player%", entry.name()).replace("%total%", String.valueOf(entry.total()))));
        }
    }
}

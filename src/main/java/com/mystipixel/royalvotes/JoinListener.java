package com.mystipixel.royalvotes;

import com.mystipixel.royalvotes.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/** Pays out queued votes on join, and nudges players who have not voted today. */
public final class JoinListener implements Listener {

    private final RoyalVotesPlugin plugin;

    public JoinListener(RoyalVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Delayed so plugins that load player data on join (EcoBattlepass among them) have it ready
        // before the replayed VotifierEvent asks them to add progress to it.
        long delay = Math.max(1, plugin.getConfig().getLong("offline.join-delay-ticks", 60));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return;
            }
            plugin.votes().deliverQueued(player);

            if (plugin.getConfig().getBoolean("reminder.enabled", true)
                    && !plugin.votes().votedToday(plugin.store().peek(uuid))) {
                for (String line : plugin.getConfig().getStringList("reminder.messages")) {
                    player.sendMessage(Text.chat(line));
                }
            }
        }, delay);
    }
}

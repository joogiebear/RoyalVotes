package com.mystipixel.royalvotes.hook;

import com.mystipixel.royalvotes.RoyalVotesPlugin;
import com.mystipixel.royalvotes.data.PlayerStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;

/**
 * {@code %royalvotes_...%}. Only ever loaded once PlaceholderAPI is confirmed present, so the plugin
 * itself never links against it.
 */
public final class VotesExpansion extends PlaceholderExpansion {

    private final RoyalVotesPlugin plugin;

    public VotesExpansion(RoyalVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "royalvotes";
    }

    @Override
    public String getAuthor() {
        return "Mystipixel";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;                             // survive /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        switch (params) {
            case "party_current":
                return String.valueOf(plugin.store().partyCount());
            case "party_required":
                return String.valueOf(plugin.votes().partyRequired());
            case "party_remaining":
                return String.valueOf(Math.max(0, plugin.votes().partyRequired() - plugin.store().partyCount()));
            default:
                break;
        }
        if (params.startsWith("top_name_") || params.startsWith("top_votes_")) {
            boolean name = params.startsWith("top_name_");
            int rank;
            try {
                rank = Integer.parseInt(params.substring(params.lastIndexOf('_') + 1));
            } catch (NumberFormatException notNumber) {
                return null;
            }
            List<PlayerStats> top = plugin.store().top();
            if (rank < 1 || rank > top.size()) {
                return name ? "-" : "0";
            }
            PlayerStats entry = top.get(rank - 1);
            return name ? entry.name() : String.valueOf(entry.total());
        }

        if (player == null) {
            return "";
        }
        PlayerStats stats = plugin.store().peek(player.getUniqueId());
        return switch (params) {
            case "total" -> String.valueOf(stats == null ? 0 : stats.total());
            case "streak" -> String.valueOf(plugin.votes().liveStreak(stats));
            case "best_streak" -> String.valueOf(stats == null ? 0 : stats.bestStreak());
            case "voted_today" -> String.valueOf(plugin.votes().votedToday(stats));
            case "pending" -> player.getName() == null ? "0"
                    : String.valueOf(plugin.store().pendingCount(player.getName()));
            default -> null;
        };
    }
}

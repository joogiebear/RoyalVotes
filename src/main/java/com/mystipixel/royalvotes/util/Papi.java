package com.mystipixel.royalvotes.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Optional PlaceholderAPI bridge. Names and lore resolve when an item is applied (join, respawn,
 * world change, reload), commands at the moment of the click — so a lore line like
 * {@code %server_online%} is as fresh as the last apply, while a command is always current.
 *
 * <p>Soft on purpose: without PlaceholderAPI installed every string passes through untouched, and
 * the PAPI class is only referenced behind the plugin-enabled check so the JVM never links it when
 * it is absent.
 */
public final class Papi {

    private Papi() {
    }

    public static String apply(Player player, String text) {
        if (text == null || text.indexOf('%') < 0
                || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return text;
        }
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable broken) {
            return text;                         // a broken expansion must never break the item
        }
    }
}

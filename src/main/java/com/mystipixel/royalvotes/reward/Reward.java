package com.mystipixel.royalvotes.reward;

import com.mystipixel.royalvotes.util.Papi;
import com.mystipixel.royalvotes.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * One bundle of things that happen: console commands, lines to the player, a server-wide broadcast.
 * Every reward in the config — per vote, bonus, streak, milestone, party — is this same shape.
 */
public record Reward(List<String> commands, List<String> messages, String broadcast) {

    public static final Reward NONE = new Reward(List.of(), List.of(), "");

    public static Reward load(ConfigurationSection section) {
        if (section == null) {
            return NONE;
        }
        return new Reward(List.copyOf(section.getStringList("commands")),
                List.copyOf(section.getStringList("messages")), section.getString("broadcast", ""));
    }

    public boolean isEmpty() {
        return commands.isEmpty() && messages.isEmpty() && broadcast.isEmpty();
    }

    /** @param values placeholder name (without the %) → replacement */
    public void give(Player player, Map<String, String> values) {
        for (String command : commands) {
            String line = fill(player, command, values);
            if (line.startsWith("/")) {
                line = line.substring(1);
            }
            if (!line.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
            }
        }
        for (String message : messages) {
            player.sendMessage(Text.chat(fill(player, message, values)));
        }
        if (!broadcast.isEmpty()) {
            Bukkit.broadcast(Text.chat(fill(player, broadcast, values)));
        }
    }

    static String fill(Player player, String text, Map<String, String> values) {
        String filled = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.replace("%" + value.getKey() + "%", value.getValue());
        }
        return Papi.apply(player, filled);
    }
}

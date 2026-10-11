package com.mystipixel.royalvotes.config;

import com.mystipixel.royalvotes.util.Text;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every messages.* key the code reads must ship in config.yml: a server whose config.yml predates a key
// then gets the bundled text from the jar's defaults.
class MessageDefaultsTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern MESSAGE_CALL = Pattern.compile("\\bmessage\\s*\\(\\s*(\"[^\"]*\"|[^,)]+)");

    @Test
    void everyMessageKeyReadInCodeShipsInConfigYml() throws IOException {
        YamlConfiguration shipped = load();
        List<String> problems = new ArrayList<>();
        int found = 0;
        for (Path file : sources()) {
            String source = Files.readString(file);
            Matcher m = MESSAGE_CALL.matcher(source);
            while (m.find()) {
                String key = m.group(1).strip();
                if (key.startsWith("String key")) {
                    continue; // the declaration of message(String key, ...)
                }
                found++;
                String where = file.getFileName() + ":" + lineOf(source, m.start());
                if (!key.startsWith("\"")) {
                    problems.add(where + ": message key must be a string literal, not " + key);
                } else if (!shipped.isString("messages." + key.substring(1, key.length() - 1))) {
                    problems.add(where + ": messages." + key + " is not in config.yml");
                }
            }
        }
        assertTrue(found > 25, "found only " + found + " message reads; is the scan still matching?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertTrue(shipped.isString("links.line"), "links.line is read without a code fallback");
    }

    @Test
    void configYmlHasNoDuplicateKeys() throws IOException {
        // YAML keeps only the last of two equal keys, silently dropping the first
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        int items = 0;
        for (String line : resource().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (trimmed.startsWith("-")) {
                // each list entry is its own map, so "- name:" / "url:" pairs don't repeat across entries
                while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                    indents.remove(indents.size() - 1);
                    path.remove(path.size() - 1);
                }
                path.add("[" + items++ + "]");
                indents.add(indent);
                trimmed = trimmed.substring(1).strip();
                indent += 2;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0 || trimmed.startsWith("\"")) {
                continue;
            }
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate keys in config.yml: " + duplicates);
    }

    @Test
    void bundledDefaultsRenderTheTextTheCommandSentBefore() throws IOException {
        YamlConfiguration shipped = load();
        String label = "rv";
        assertEquals("&cUsage: /" + label + " <" + String.join("|", List.of("reload", "status", "fakevote",
                "stats", "party")) + ">", render(shipped, "admin.usage", "label", label));
        String listener = render(shipped, "admin.listener.v1-and-v2", "host", "0.0.0.0", "port", "8192");
        assertEquals("0.0.0.0" + ":" + 8192 + " (v2 tokens" + " + v1 RSA" + ")", listener);
        assertEquals("0.0.0.0:8192 (v2 tokens)", render(shipped, "admin.listener.v2-only", "host", "0.0.0.0",
                "port", "8192"));
        assertEquals("FAILED to bind " + "0.0.0.0" + ":" + 8192, render(shipped, "admin.listener.failed",
                "host", "0.0.0.0", "port", "8192"));
        assertEquals("&aReloaded. &7Listener: " + listener, render(shipped, "admin.reloaded", "listener", listener));
        String party = render(shipped, "admin.status.party-progress", "current", "3", "required", "50");
        assertEquals("&7Vote party: &f" + 3 + "/" + 50, render(shipped, "admin.status.party", "party", party));
        assertEquals("&7Vote party: &f" + "off", render(shipped, "admin.status.party", "party",
                render(shipped, "admin.status.party-off")));
        assertEquals("&aSent a test vote for &f" + "Steve" + "&a via &f" + "fakevote" + "&a."
                        + " &7They are offline, so it is queued.",
                render(shipped, "admin.fakevote.sent-queued", "player", "Steve", "service", "fakevote"));
        assertEquals("&f" + "Steve" + "&7: total &f" + 12 + "&7, streak &f" + 3 + "&7 (best &f" + 5
                        + "&7), queued &f" + 1,
                render(shipped, "admin.stats.line", "player", "Steve", "total", "12", "streak", "3", "best", "5",
                        "queued", "1"));
        assertEquals("&c'" + "x" + "' is not a number.", render(shipped, "admin.party.not-a-number", "value", "x"));
        assertEquals("&7libreforge register_vote: &f" + "FAILED (see the startup log)",
                render(shipped, "admin.status.libreforge", "libreforge", render(shipped, "admin.libreforge.failed")));
    }

    private static String render(YamlConfiguration shipped, String key, String... replacements) {
        return Text.fill(shipped.getString("messages." + key), replacements);
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String resource() throws IOException {
        try (InputStream in = MessageDefaultsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration load() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource());
        } catch (InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}

package com.mystipixel.royalvotes.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Legacy '&' colour strings to Adventure components. */
public final class Text {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component chat(String input) {
        return AMP.deserialize(input == null ? "" : input);
    }

    /**
     * Fills {@code %name%} placeholders given as alternating name/value pairs (names without %), in one pass
     * so a value that itself contains a placeholder is left alone.
     */
    public static String fill(String raw, String... replacements) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        int i = 0;
        while (true) {
            int start = raw.indexOf('%', i);
            int end = start < 0 ? -1 : raw.indexOf('%', start + 1);
            if (end < 0) {
                break;
            }
            int index = indexOf(raw.substring(start + 1, end), replacements);
            if (index < 0) {
                out.append(raw, i, end);
                i = end;
            } else {
                out.append(raw, i, start).append(replacements[index + 1]);
                i = end + 1;
            }
        }
        return out.append(raw, i, raw.length()).toString();
    }

    private static int indexOf(String name, String... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            if (replacements[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}

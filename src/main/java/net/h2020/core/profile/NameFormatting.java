package net.h2020.core.profile;

import org.bukkit.ChatColor;

/** Legacy name formatting shared by rank, title and scoreboard prefixes. */
public final class NameFormatting {
    private NameFormatting() {}

    public static String text(String input, boolean colorsOnly) {
        if (input == null) return null;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isISOControl(ch) || ch == ChatColor.COLOR_CHAR) return null;
            if (ch == '&' && i + 1 < input.length()) {
                if (input.charAt(i + 1) == '&') {
                    result.append('&');
                    i++;
                    continue;
                }
                ChatColor code = ChatColor.getByChar(Character.toLowerCase(input.charAt(i + 1)));
                if (code != null) {
                    if (colorsOnly && !code.isColor()) return null;
                    result.append(code);
                    i++;
                    continue;
                }
            }
            result.append(ch);
        }
        String translated = result.toString().trim();
        return ChatColor.stripColor(translated).trim().isEmpty() ? null : translated;
    }

    /** Input is already translated; older saved ranks may omit their brackets. */
    public static String bracketedRank(String input, ChatColor fallback) {
        String plain = ChatColor.stripColor(input);
        int open = input.indexOf('['), close = input.lastIndexOf(']');
        String before, body;
        if (plain.startsWith("[") && plain.endsWith("]") && open >= 0 && close > open) {
            before = input.substring(0, open);
            body = input.substring(open + 1, close);
            if (!ChatColor.stripColor(input.substring(close + 1)).trim().isEmpty()) return null;
        } else {
            before = "";
            body = input;
        }
        String visible = ChatColor.stripColor(body);
        if (visible.trim().isEmpty() || visible.indexOf('[') >= 0 || visible.indexOf(']') >= 0) return null;
        ChatColor left = leadingColor(before, leadingColor(body, fallback));
        // Uncolored brackets inherit the nearest text color.
        return compact(left + "[" + body + "]");
    }

    private static ChatColor leadingColor(String text, ChatColor fallback) {
        ChatColor result = fallback;
        for (int i = 0; i + 1 < text.length() && text.charAt(i) == ChatColor.COLOR_CHAR; i += 2) {
            ChatColor code = ChatColor.getByChar(Character.toLowerCase(text.charAt(i + 1)));
            if (code != null && code.isColor()) result = code;
        }
        return result;
    }

    private static String compact(String text) {
        StringBuilder result = new StringBuilder();
        ChatColor previous = null;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ChatColor.COLOR_CHAR && i + 1 < text.length()) {
                ChatColor color = ChatColor.getByChar(Character.toLowerCase(text.charAt(i + 1)));
                if (color != null && color.isColor()) {
                    if (color != previous) result.append(color);
                    previous = color;
                    i++;
                    continue;
                }
            }
            result.append(ch);
        }
        return result.toString();
    }

    /** Keep formatting pairs and Unicode code points whole; reserve the final name color. */
    public static String teamPrefix(String text, ChatColor nameColor) {
        String best = nameColor.toString();
        for (int end = 0; end < text.length();) {
            if (text.charAt(end) == ChatColor.COLOR_CHAR && end + 1 < text.length()) end += 2;
            else end += Character.charCount(text.codePointAt(end));
            if (end > 16) break;
            String candidate = text.substring(0, end);
            if (!ChatColor.getLastColors(candidate).equals(nameColor.toString())) candidate += nameColor;
            if (candidate.length() <= 16) best = candidate;
        }
        return best;
    }
}

package net.h2020.core.profile;

import net.h2020.api.core.Rank;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class NameFormattingTest {
    private static ProfileData profile() {
        return new ProfileData(UUID.randomUUID(), "Steve");
    }

    private static String rank(String text, ChatColor fallback) {
        String translated = NameFormatting.text(text, true);
        return translated == null ? null : NameFormatting.bracketedRank(translated, fallback);
    }

    @Test public void autoBracketsFollowNearestText() {
        assertEquals(ChatColor.AQUA + "[Builder]", rank("&bBuilder", ChatColor.GRAY));
        assertEquals(ChatColor.AQUA + "[Builder]", rank("[&bBuilder]", ChatColor.GRAY));
        assertEquals(ChatColor.GOLD + "[Builder]", rank("Builder", ChatColor.GOLD));
        assertEquals(ChatColor.GRAY + "[Builder]", rank("Builder", ChatColor.GRAY));
        assertEquals(ChatColor.GREEN + "[AB" + ChatColor.AQUA + "C]",
                rank("&aAB&bC", ChatColor.GRAY));
    }

    @Test public void explicitBracketsKeepIndependentColors() {
        assertEquals(ChatColor.RED + "[" + ChatColor.AQUA + "AB" + ChatColor.GOLD + "]",
                rank("&c[&bAB&6]", ChatColor.GRAY));
        assertEquals(ChatColor.RED + "[AB]", rank("&c[AB]", ChatColor.GRAY));
        assertEquals(ChatColor.GRAY + "[" + ChatColor.AQUA + "Builder" + ChatColor.GRAY + "]",
                rank("&7[&bBuilder&7]", ChatColor.GRAY));
    }

    @Test public void missingLeftColorLooksAheadAndRightColorInherits() {
        assertEquals(ChatColor.AQUA + "[A" + ChatColor.GREEN + "B]",
                rank("[&bA&aB]", ChatColor.GOLD));
        assertEquals(ChatColor.AQUA + "[AB" + ChatColor.RED + "]",
                rank("[&bAB&c]", ChatColor.GRAY));
    }

    @Test public void invalidRanksDoNotBecomeBrokenBrackets() {
        for (String input : new String[]{"", " ", "&a", "[]", "[&a]", "[A", "A]", "A[B]", "[A][B]", "[[A]]", "A\nB", "&lA", "\u00a7aA"}) {
            assertNull(input, rank(input, ChatColor.GRAY));
        }
    }

    @Test public void legacySavedRankGetsCorrectedWithoutDoubleBrackets() {
        ProfileData data = profile();
        data.customRankTitle = ChatColor.AQUA + "Builder";
        String legacy = data.rankPrefix();
        data.customRankTitle = rank("[&bBuilder]", ChatColor.GRAY);
        assertEquals(legacy, data.rankPrefix());
        assertEquals(ChatColor.AQUA + "[Builder]", data.rankName());
    }

    @Test public void dominantRankColorDoesNotIncludeTitle() {
        ProfileData data = profile();
        data.customRankTitle = rank("&7[&bBuilder&7]", ChatColor.GRAY);
        data.title = ChatColor.RED + "A very long red title";
        data.titleColor = ChatColor.RED;
        assertEquals(ChatColor.AQUA, data.nameColor());
        assertEquals(ChatColor.AQUA.toString(), ChatColor.getLastColors(data.displayPrefix()));
        assertEquals("A very long red title [Builder] ", ChatColor.stripColor(data.displayPrefix()));
    }

    @Test public void dominantTieUsesFirstVisibleColor() {
        assertEquals(ChatColor.RED, ProfileData.dominantColor(ChatColor.RED + "A" + ChatColor.BLUE + "B", ChatColor.GRAY));
        assertEquals(ChatColor.BLUE, ProfileData.dominantColor(ChatColor.RED + " " + ChatColor.BLUE + "AB", ChatColor.GRAY));
    }

    @Test public void builtinRankColorsRemainCompatible() {
        ProfileData data = profile();
        for (Rank rank : Rank.values()) {
            data.rank = rank;
            assertEquals(rank.name(), rank == Rank.YOUTUBE ? ChatColor.WHITE : rank.color, data.nameColor());
            assertEquals(data.nameColor().toString(), ChatColor.getLastColors(data.rankPrefix()));
            data.rankColor = ChatColor.DARK_AQUA;
            assertEquals(ChatColor.DARK_AQUA, data.nameColor());
            data.rankColor = null;
        }
    }

    @Test public void titleSupportsArbitraryTextAndFormatting() {
        assertEquals("[Event] R&D % 100", NameFormatting.text("[Event] R&&D % 100", false));
        assertEquals(ChatColor.GOLD + "" + ChatColor.BOLD + "Winner",
                NameFormatting.text("&6&lWinner", false));
        assertNull(NameFormatting.text("A\nB", false));
        assertNull(NameFormatting.text("A\tB", false));
        assertNull(NameFormatting.text("\nAB", false));
        assertNull(NameFormatting.text("&a", false));
        assertNull(NameFormatting.text("&r", false));
    }

    @Test public void titleNeverLeaksFormattingIntoNameWithoutRank() {
        ProfileData data = profile();
        data.title = ChatColor.RED + "" + ChatColor.BOLD + "Winner";
        assertEquals(ChatColor.GRAY.toString(), ChatColor.getLastColors(data.displayPrefix()));
        assertEquals("Winner ", ChatColor.stripColor(data.displayPrefix()));
    }

    @Test public void fullTextCanExceedTeamLimit() {
        ProfileData data = profile();
        data.customRankTitle = rank("&bVeryLongRankName", ChatColor.GRAY);
        data.title = ChatColor.GOLD + "A long separate prefix";
        String full = data.displayPrefix();
        assertTrue(full.length() > 16);
        String compact = NameFormatting.teamPrefix(full, data.nameColor());
        assertTrue(compact.length() <= 16);
        assertFalse(compact.endsWith(String.valueOf(ChatColor.COLOR_CHAR)));
        assertEquals(data.nameColor().toString(), ChatColor.getLastColors(compact));
        assertEquals("A long separate prefix [VeryLongRankName] ", ChatColor.stripColor(full));
    }

    @Test public void compactPrefixNeverSplitsColorOrSurrogatePairs() {
        ProfileData data = profile();
        data.customRankTitle = rank("&aAB&bC&dDE", ChatColor.GRAY);
        for (String text : new String[]{data.rankPrefix(), "\u00a76abcdefghijkl\uD83D\uDE00Z", "\u00a76abc\u00a7ldefghijklmnop", "\u00a7c[AB\u00a7bC]"}) {
            String compact = NameFormatting.teamPrefix(text, ChatColor.GREEN);
            assertTrue(compact.length() <= 16);
            assertFalse(compact.endsWith("\u00a7"));
            assertEquals(ChatColor.GREEN.toString(), ChatColor.getLastColors(compact));
            for (int i = 0; i < compact.length(); i++) {
                if (Character.isHighSurrogate(compact.charAt(i))) assertTrue(i + 1 < compact.length() && Character.isLowSurrogate(compact.charAt(++i)));
                else assertFalse(Character.isLowSurrogate(compact.charAt(i)));
            }
        }
    }

    @Test public void titleAndBracketsRoundTripThroughYaml() throws Exception {
        ProfileData data = profile();
        data.customRankTitle = rank("&c[&bBuilder&6]", ChatColor.GRAY);
        data.title = NameFormatting.text("&6[Event] &lWinner", false);
        data.titleColor = ChatColor.GREEN;
        YamlConfiguration yaml = new YamlConfiguration();
        data.save(yaml);
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(yaml.saveToString());
        ProfileData copy = profile();
        copy.load(parsed);
        assertEquals(data.displayPrefix(), copy.displayPrefix());
        assertEquals(data.title, copy.title);
        assertEquals(data.titleColor, copy.titleColor);
        assertEquals(data.nameColor(), copy.nameColor());
    }

    @Test public void clearingRankAndTitleAreIndependent() {
        ProfileData data = profile();
        data.customRankTitle = rank("&bBuilder", ChatColor.GRAY);
        data.title = "Winner";
        data.title = null;
        assertEquals("[Builder] ", ChatColor.stripColor(data.displayPrefix()));
        data.title = "Winner";
        data.customRankTitle = null;
        data.rank = Rank.NONE;
        assertEquals("Winner ", ChatColor.stripColor(data.displayPrefix()));
        YamlConfiguration yaml = new YamlConfiguration();
        data.title = null;
        data.titleColor = null;
        data.save(yaml);
        assertFalse(yaml.contains("title"));
        assertFalse(yaml.contains("title-color"));
        ProfileData copy = profile();
        copy.title = "Old";
        copy.load(yaml);
        assertNull(copy.title);
        assertNull(copy.titleColor);
    }
}

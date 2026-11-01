package net.h2020.core.command;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class NameCommandTest {
    private static CommandSender sender(boolean allowed, List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(NameCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) return allowed;
                    if (method.getName().equals("sendMessage")) {
                        messages.add((String) args[0]);
                        return null;
                    }
                    throw new AssertionError("Unexpected call: " + method.getName());
                });
    }

    @Test public void bothCommandsDenyBeforeLookingUpPlayers() {
        List<String> messages = new ArrayList<>();
        CommandSender denied = sender(false, messages);
        assertTrue(new RankCommand(null).onCommand(denied, null, "rank", new String[]{"Steve", "ADMIN"}));
        assertTrue(new TitleCommand(null).onCommand(denied, null, "title", new String[]{"Steve", "Winner"}));
        assertEquals(2, messages.size());
        for (String message : messages) assertTrue(message.contains("permission"));
    }

    @Test public void noArgumentsProduceUsageWithoutServer() {
        List<String> messages = new ArrayList<>();
        CommandSender allowed = sender(true, messages);
        new RankCommand(null).onCommand(allowed, null, "rank", new String[0]);
        new TitleCommand(null).onCommand(allowed, null, "title", new String[0]);
        assertTrue(messages.get(0).contains("Usage: /rank"));
        assertTrue(messages.get(messages.size() - 1).contains("Usage: /title"));
    }

    @Test public void completionsRespectPermissionAndColorOnlyParameters() {
        List<String> messages = new ArrayList<>();
        CommandSender denied = sender(false, messages), allowed = sender(true, messages);
        RankCommand ranks = new RankCommand(null);
        TitleCommand titles = new TitleCommand(null);
        assertTrue(ranks.onTabComplete(denied, null, "rank", new String[]{"Steve", "VIP", ""}).isEmpty());
        assertTrue(titles.onTabComplete(denied, null, "title", new String[]{"Steve", "Winner", ""}).isEmpty());
        assertEquals(16, titles.onTabComplete(allowed, null, "title", new String[]{"Steve", "Winner", ""}).size());
        assertFalse(titles.onTabComplete(allowed, null, "title", new String[]{"Steve", "Winner", ""}).contains("bold"));
        assertEquals("clear", titles.onTabComplete(allowed, null, "title", new String[]{"Steve", "cl"}).get(0));
    }

    @Test public void rankCommandUsesBracketNormalizer() {
        assertEquals(ChatColor.AQUA + "[Builder]", RankCommand.customTitle("&bBuilder", null));
        assertEquals(ChatColor.AQUA + "[Builder]", RankCommand.customTitle("[&bBuilder]", null));
        assertEquals(ChatColor.RED + "[" + ChatColor.AQUA + "Builder" + ChatColor.GOLD + "]",
                RankCommand.customTitle("&c[&bBuilder&6]", null));
        assertNull(RankCommand.customTitle("[[Builder]]", null));
        assertNull(RankCommand.customTitle("&lBuilder", null));
        assertEquals(ChatColor.GOLD, RankCommand.parseColor("gold"));
        assertNull(RankCommand.parseColor("&l"));
    }

    @Test public void pluginDeclaresBothCommandsWithAdminPermission() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("plugin.yml"), "UTF-8")) {
            yaml.load(reader);
        }
        assertEquals("h2020.admin", yaml.getString("commands.rank.permission"));
        assertEquals("h2020.admin", yaml.getString("commands.title.permission"));
        assertTrue(yaml.getString("commands.title.usage").contains("clear"));
    }
}

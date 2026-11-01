package net.h2020;

import net.h2020.api.Module;
import net.h2020.api.game.GameType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class MergedModulesTest {
    @Test public void completeModulesStillLoadAndAppearInBootstrap() throws Exception {
        byte[] bootstrap;
        try (InputStream in = getClass().getResourceAsStream("/net/h2020/H2020Plugin.class");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertNotNull(in);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            bootstrap = out.toByteArray();
        }
        String constants = new String(bootstrap, StandardCharsets.ISO_8859_1);
        for (String module : new String[]{
                "net.h2020.core.CoreModule", "net.h2020.maps.MapsModule",
                "net.h2020.bots.BotsModule", "net.h2020.bedwars.BedwarsModule",
                "net.h2020.skywars.SkywarsModule", "net.h2020.duels.DuelsModule",
                "net.h2020.pit.PitModule", "net.h2020.friend.FriendModule",
                "net.h2020.social.party.PartyModule"}) {
            Class<?> type = Class.forName(module, false, getClass().getClassLoader());
            assertTrue(module, Module.class.isAssignableFrom(type));
            assertTrue(module, constants.contains(module.replace('.', '/')));
        }
    }

    @Test public void completeAndNewCommandsAreDeclaredTogether() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        for (String command : new String[]{"pit", "duels", "friend", "party", "pchat",
                "chat", "play", "lobby", "nocheatplus", "h2020", "rank", "title"}) {
            assertTrue(command, yaml.isConfigurationSection("commands." + command));
        }
        assertEquals("1.1.0-merged", yaml.getString("version"));
        assertEquals("h2020.admin", yaml.getString("commands.title.permission"));
    }

    @Test public void duelsIdsAndPolicyDependencyArePresent() throws Exception {
        for (String mode : new String[]{"duels_classic_duel", "duels_boxing_duel",
                "duels_potion_duel", "duels_bridge_duel"}) {
            assertNotNull(mode, GameType.byId(mode));
        }
        ClassLoader loader = getClass().getClassLoader();
        Class<?> policy = Class.forName("fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy", false, loader);
        assertNotNull(policy);
        Class<?> component = Class.forName("fr.neatmonster.nocheatplus.NoCheatPlus", false, loader);
        assertNotNull(component.getConstructor(org.bukkit.plugin.java.JavaPlugin.class));
        assertNotNull(Class.forName("net.h2020.pit.PitBotManager", false, loader));
    }
}

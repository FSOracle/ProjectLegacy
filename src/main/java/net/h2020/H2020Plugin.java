package net.h2020;
import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.bedwars.BedwarsModule;
import net.h2020.bots.BotsModule;
import net.h2020.core.CoreModule;
import net.h2020.maps.MapsModule;
import net.h2020.skywars.SkywarsModule;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import java.util.logging.Level;
public final class H2020Plugin extends JavaPlugin {
    private final List<Module> enabled = new ArrayList<>();
    private NcpEngine engine;
    private boolean setupFailed;
    @Override public void onLoad() {
        try {
            saveDefaultConfig();
            java.io.File file = new java.io.File(getDataFolder(), "config.yml");
            YamlConfiguration config = new YamlConfiguration();
            config.load(file);
            if (config.contains("watchdog") && !config.isConfigurationSection("watchdog"))
                throw new IllegalArgumentException("watchdog must be a configuration section");
            Object value = config.get("watchdog.enabled");
            if (value != null && !(value instanceof Boolean))
                throw new IllegalArgumentException("watchdog.enabled must be a boolean");
            if (value == null || Boolean.TRUE.equals(value)) {
                Class<?> type = Class.forName("net.h2020.NcpBootstrap", true, getClass().getClassLoader());
                java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor(H2020Plugin.class);
                ctor.setAccessible(true);
                engine = (NcpEngine)ctor.newInstance(this);
                engine.load();
            } else getLogger().info("Watchdog and NCP disabled until restart");
        } catch (Throwable failure) {
            setupFailed=true;
            getLogger().log(Level.SEVERE, "Anticheat configuration or initialization failed; host will not start", failure);
        }
    }
    @Override public void onEnable() {
        if (setupFailed) { getServer().getPluginManager().disablePlugin(this); return; }
        try { if (engine != null) engine.enable(); }
        catch (Throwable t) { getLogger().log(Level.SEVERE,"NCP enable failed",t); getServer().getPluginManager().disablePlugin(this); return; }
        H2020.init(this);
        saveDefaultConfig();
        List<Module> order = new ArrayList<>();
        order.add(new CoreModule()); order.add(new MapsModule()); order.add(new net.h2020.friend.FriendModule());
        if (engine != null) order.add(engine.watchdogModule());
        order.add(new BotsModule()); order.add(new net.h2020.social.party.PartyModule()); order.add(new BedwarsModule()); order.add(new SkywarsModule()); order.add(new net.h2020.pit.PitModule()); order.add(new net.h2020.duels.DuelsModule());
        for (Module m : order) {
            try { m.enable(this); enabled.add(m); getLogger().info("Enabled module " + m.name()); }
            catch (Throwable t) {
                getLogger().log(Level.SEVERE,"Failed to enable module " + m.name(),t);
                if ("watchdog".equals(m.name())) { getServer().getPluginManager().disablePlugin(this); return; }
            }
        }
    }
    @Override public void onDisable() {
        List<Module> rev = new ArrayList<>(enabled); Collections.reverse(rev);
        for (Module m : rev) try { m.disable(); } catch (Throwable t) { getLogger().log(Level.SEVERE,"Failed to disable " + m.name(),t); }
        enabled.clear();
        if (engine != null) try { engine.disable(); } catch (Throwable t) { getLogger().log(Level.SEVERE,"Failed to disable NCP",t); }
    }
}

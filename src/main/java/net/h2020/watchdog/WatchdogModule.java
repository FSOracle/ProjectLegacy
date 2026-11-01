package net.h2020.watchdog;

import net.h2020.api.H2020;
import net.h2020.watchdog.ncp.NcpBridge;
import net.h2020.watchdog.ncp.OrderedMainDispatcher;
import net.h2020.api.Module;
import net.h2020.api.packet.PacketHub;
import net.h2020.api.watchdog.WatchdogService;
import net.h2020.watchdog.checks.blockbreak.BlockBreakChecks;
import net.h2020.watchdog.checks.blockplace.BlockPlaceChecks;
import net.h2020.watchdog.checks.fight.FightChecks;
import net.h2020.watchdog.checks.inventory.InventoryChecks;
import net.h2020.watchdog.checks.moving.MovingChecks;
import net.h2020.watchdog.command.ReportCommand;
import net.h2020.watchdog.command.WatchdogCommand;
import net.h2020.watchdog.framework.PacketRouter;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Watchdog: NoCheatPlus-3.16-style anticheat with Hypixel 2020 presentation (delayed bans, announcements, reports).
 * Builds the framework, registers the WatchdogService, the combat/block/inventory checks of this module and finally
 * the movement checks of the movement builder.
 */
public final class WatchdogModule implements Module {
    private WatchdogServiceImpl service;
    private NcpBridge bridge;

    @Override public String name() { return "watchdog"; }

    @Override
    public void enable(JavaPlugin plugin) {
        WatchdogConfig config = WatchdogConfig.load(plugin);
        service = new WatchdogServiceImpl(plugin, config);
        final org.bukkit.scheduler.BukkitScheduler scheduler = org.bukkit.Bukkit.getScheduler();
        bridge = new NcpBridge(plugin.getClass().getClassLoader(), (player, check, total, added, nativeName) ->
                service.ncpFlag((org.bukkit.entity.Player) player, check, total, added, nativeName),
                new OrderedMainDispatcher(task -> scheduler.runTask(plugin, task)));
        service.setNcpInvalidate(bridge::invalidate);
        try {
            service.start();
            bridge.register();
            H2020.register(WatchdogService.class, service);
        } catch (Throwable failure) {
            try { bridge.unregister(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            try { service.shutdown(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            H2020.unregister(WatchdogService.class, service);
            throw new IllegalStateException("Cannot start Watchdog/NCP integration", failure);
        }

        WatchdogCommand wd = new WatchdogCommand(service);
        PluginCommand c = plugin.getCommand("wd");
        if (c != null) { c.setExecutor(wd); c.setTabCompleter(wd); }
        ReportCommand rc = new ReportCommand(service);
        PluginCommand r = plugin.getCommand("report");
        if (r != null) { r.setExecutor(rc); r.setTabCompleter(rc); }

    }

    @Override
    public void disable() {
        try {
            if (bridge != null) bridge.unregister();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot unregister NCP hook", e);
        } finally {
            if (service != null) {
                H2020.unregister(WatchdogService.class, service);
                service.shutdown();
            }
        }
    }
}

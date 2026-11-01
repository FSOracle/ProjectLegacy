package net.h2020;
import fr.neatmonster.nocheatplus.NoCheatPlus;
import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy;
import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.watchdog.WatchdogService;
import net.h2020.watchdog.WatchdogModule;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.ncp.HostDirectActionPolicy;
import net.h2020.watchdog.ncp.SafeActionFactory;
import org.bukkit.plugin.Plugin;
final class NcpBootstrap implements NcpEngine {
    private final H2020Plugin host;
    private final NoCheatPlus component;
    private HostDirectActionPolicy policy;
    private boolean installed;
    NcpBootstrap(H2020Plugin host) { this.host=host; this.component=new NoCheatPlus(host); }
    public void load() {
        component.onLoad();
        final org.bukkit.scheduler.BukkitScheduler scheduler = host.getServer().getScheduler();
        policy = new HostDirectActionPolicy(task -> scheduler.runTask(host, task), (player, reason) -> {
            WatchdogService wd=H2020.find(WatchdogService.class);
            if (wd instanceof WatchdogServiceImpl) ((WatchdogServiceImpl)wd).ncpDirectRequest(player, reason);
        });
        AutomaticActionPolicy.setPolicy(policy);
        NCPAPIProvider.getNoCheatPlusAPI().registerGenericInstance(Plugin.class, host);
        NCPAPIProvider.getNoCheatPlusAPI().setActionFactoryFactory(SafeActionFactory::new);
        installed=true;
    }
    public void enable() {
        if (!installed) throw new IllegalStateException("NCP policy unavailable");
        component.onEnable();
    }
    public Module watchdogModule() { return new WatchdogModule(); }
    public void disable() {
        try { component.onDisable(); }
        finally { if (policy != null) policy.stop(); AutomaticActionPolicy.setPolicy(null); installed=false; }
    }
}

package net.h2020.watchdog.ncp;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import fr.neatmonster.nocheatplus.hooks.ExemptionContext;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;

/** Main-thread, nested leases. Release exactly the exemptions that Watchdog owns. */
public final class NcpExemptions {
    private final JavaPlugin plugin;
    private final Map<UUID,List<Lease>> leases = new HashMap<>();
    private final class Lease {
        final UUID id;
        final IPlayerData data;
        final fr.neatmonster.nocheatplus.checks.CheckType check;
        BukkitTask task;
        Lease(UUID id, IPlayerData data, fr.neatmonster.nocheatplus.checks.CheckType check) {
            this.id=id; this.data=data; this.check=check;
        }
        void release() {
            List<Lease> list=leases.get(id);
            if (list == null || !list.remove(this)) return;
            if (list.isEmpty()) leases.remove(id);
            if (task != null) task.cancel();
            data.unexempt(check, ExemptionContext.ANONYMOUS_NESTED);
        }
    }
    public NcpExemptions(JavaPlugin plugin) { this.plugin=plugin; }
    public void exempt(Player player, net.h2020.api.watchdog.CheckType check, long durationMs) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("NCP exemptions require main thread");
        if (durationMs <= 0) return;
        final fr.neatmonster.nocheatplus.checks.CheckType type;
        try { type=fr.neatmonster.nocheatplus.checks.CheckType.valueOf(check == net.h2020.api.watchdog.CheckType.NET_KEEPALIVE ? "NET_KEEPALIVEFREQUENCY" : check.name()); }
        catch (IllegalArgumentException unsupported) { return; }
        exempt(player, type, durationMs);
    }
    public void exempt(Player player, fr.neatmonster.nocheatplus.checks.CheckType type, long durationMs) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("NCP exemptions require main thread");
        if (durationMs <= 0) return;
        IPlayerData data=DataManager.getPlayerData(player);
        if (data == null) throw new IllegalStateException("NCP player data unavailable for " + player.getName());
        Lease lease=new Lease(player.getUniqueId(),data,type);
        data.exempt(type, ExemptionContext.ANONYMOUS_NESTED);
        leases.computeIfAbsent(lease.id, k -> new ArrayList<>()).add(lease);
        long ticks = Math.max(1L, durationMs / 50L + (durationMs % 50L == 0 ? 0 : 1));
        try { lease.task=Bukkit.getScheduler().runTaskLater(plugin, lease::release, ticks); }
        catch (RuntimeException ex) { lease.release(); throw ex; }
    }
    public void releasePlayer(UUID id) {
        List<Lease> list=leases.get(id);
        if (list != null) for (Lease lease : new ArrayList<>(list)) lease.release();
    }
    public void shutdown() {
        for (UUID id : new ArrayList<>(leases.keySet())) releasePlayer(id);
    }
}

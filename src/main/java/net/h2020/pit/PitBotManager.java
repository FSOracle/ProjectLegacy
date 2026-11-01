package net.h2020.pit;

import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotBrain;
import net.h2020.api.bot.BotService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Owns only bots spawned for this Pit; all work is synchronous on the main thread. */
final class PitBotManager {
    private final PitModule pit;
    private final Map<UUID, Bot> owned = new LinkedHashMap<>();
    private BukkitTask task;
    private boolean stopped;
    PitBotManager(PitModule pit) { this.pit = pit; }
    void start() {
        task = Bukkit.getScheduler().runTaskTimer(pit.plugin, new Runnable() {
            public void run() { reconcile(); }
        }, 20L, 20L);
    }
    boolean owns(Player p) { return p != null && owned.containsKey(p.getUniqueId()); }
    boolean validTarget(Player p) {
        return p != null && p.isOnline() && !p.isDead()
                && p.getHealth() > 0 && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && pit.inPit(p)
                && !PitLayout.isSafe(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ());
    }
    void reconcile() {
        BotService svc = H2020.find(BotService.class);
        int humans = 0;
        if (svc != null && pit.world != null) for (Player p : pit.world.getPlayers())
            if (p.isOnline() && pit.inPit(p) && !svc.isBot(p)) humans++;
        int max = Math.max(0, Math.min(50, pit.plugin.getConfig().getInt("pit.bots.max", 50)));
        int desired = !stopped && !pit.storageFailed && svc != null
                && pit.plugin.getConfig().getBoolean("pit.bots.enabled", true) && humans > 0 ? max : 0;
        for (Bot b : new ArrayList<>(owned.values())) {
            try {
                if (b.isRemoved() || b.player() == null || !b.player().isOnline() || !pit.inPit(b.player()) || owned.size() > desired)
                    remove(b);
            } catch (Throwable e) {
                pit.plugin.getLogger().log(Level.WARNING, "Pit bot reconciliation failed; attempting removal", e);
                remove(b);
            }
        }
        // Spawn synchronously at most one per tick cycle: no delayed reservations can outlive humans.
        if (owned.size() < desired) {
            Bot b = null;
            try {
                b = svc.spawn(svc.randomProfile(), PitWorld.spawn(pit.world));
                owned.put(b.profile().uuid, b);
                pit.onCommand(b.player(), null, "pit", new String[0]);
                if (!pit.inPit(b.player())) { remove(b); return; }
                b.setBrain(new PitBrain(this));
            } catch (Throwable e) {
                pit.plugin.getLogger().log(Level.WARNING, "Pit bot spawn failed", e);
                if (b != null) remove(b);
            }
        }
    }
    // Non-BaseBrain prevents the global lobby reconciler from rearming a failed removal.
    private static final BotBrain QUARANTINE = new BotBrain() {
        public void start(Bot b) { }
        public void tick(Bot b) { }
        public void stop(Bot b) { }
    };
    private boolean remove(Bot b) {
        boolean ok = true;
        try { if (!b.isRemoved()) b.setBrain(QUARANTINE); }
        catch (Throwable e) { ok = false; pit.plugin.getLogger().log(Level.WARNING, "Pit bot quarantine failed", e); }
        try { if (b.player() != null) pit.leave(b.player()); }
        catch (Throwable e) { ok = false; pit.plugin.getLogger().log(Level.WARNING, "Pit bot session cleanup failed", e); }
        try { if (!b.isRemoved()) b.remove(); }
        catch (Throwable e) { ok = false; pit.plugin.getLogger().log(Level.WARNING, "Pit bot removal failed; ownership retained", e); }
        try {
            Player p = b.player();
            if (b.isRemoved() && (p == null || !p.isOnline())) owned.remove(b.profile().uuid);
            else ok = false;
        } catch (Throwable e) {
            ok = false;
            pit.plugin.getLogger().log(Level.WARNING, "Pit bot removal could not be verified; ownership retained", e);
        }
        return ok;
    }
    boolean stop() {
        stopped = true;
        boolean ok = true;
        try { if (task != null) task.cancel(); }
        catch (Throwable e) { ok = false; pit.plugin.getLogger().log(Level.WARNING, "Pit bot task cancellation failed", e); }
        for (Bot b : new ArrayList<>(owned.values())) if (!remove(b)) ok = false;
        return ok && owned.isEmpty();
    }
}

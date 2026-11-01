package net.h2020.pit;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/** Pit rules: safe spawn, PvP in the pit, death without a death screen, rewards, cleanup. */
final class PitListener implements Listener {
    private final PitModule m;
    PitListener(PitModule m) { this.m = m; }

    private static Player attacker(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player) return (Player) e.getDamager();
        if (e.getDamager() instanceof Arrow && ((Arrow) e.getDamager()).getShooter() instanceof Player)
            return (Player) ((Arrow) e.getDamager()).getShooter();
        return null;
    }

    private static boolean safe(Location l) { return PitLayout.isSafe(l.getX(), l.getY(), l.getZ()); }

    @EventHandler(priority = EventPriority.LOW)
    public void onPvp(EntityDamageByEntityEvent e) {
        Player a = attacker(e);
        if (m.bots != null && m.bots.owns(a)
                && (!(e.getEntity() instanceof Player) || !m.bots.validTarget((Player) e.getEntity()))) {
            e.setCancelled(true); return;
        }
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        if (a == null || !m.inPit(victim)) return;
        if (!m.inPit(a) || a == victim || safe(victim.getLocation()) || safe(a.getLocation())) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void protect(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (!m.inPit(p)) return;
        if (safe(p.getLocation()) || e.getCause() == EntityDamageEvent.DamageCause.FALL) e.setCancelled(true);
    }

    // Resolve after HIGHEST; other MONITOR listeners must obey the read-only convention.
    // We cancel lethal damage here to prevent a real death after final cancellation.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (!m.inPit(p)) return;
        if (e instanceof EntityDamageByEntityEvent && e.getFinalDamage() > 0) {
            Player a = attacker((EntityDamageByEntityEvent) e);
            if (a != null && a != p && m.inPit(a)) {
                PitModule.Session s = m.session(p);
                s.lastHitter = a.getUniqueId();
                s.lastHitAt = System.currentTimeMillis();
            }
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled(true);
            die(p);
        }
    }

    /** Hypixel Pit death: no death screen, no drops; back to spawn with a fresh kit. */
    void die(Player victim) { die(victim, true); }

    private void die(Player victim, boolean virtualRespawn) {
        PitModule.Session vs = m.session(victim);
        if (vs == null || vs.awaitingRespawnMove) return;
        vs.awaitingRespawnMove = true;
        Player killer = null;
        if (vs.lastHitter != null && System.currentTimeMillis() - vs.lastHitAt <= PitRewards.ASSIST_WINDOW_MS)
            killer = org.bukkit.Bukkit.getPlayer(vs.lastHitter);
        vs.lastHitter = null;
        vs.streak = 0;
        if (killer == victim || killer == null || !m.inPit(killer)) killer = null;
        // Autonomous bot-only fights are ephemeral; do not persist or reward them.
        // Human-involved deaths retain the existing transaction and reward path.
        if (m.bots != null && m.bots.owns(victim) && m.bots.owns(killer)) {
            if (virtualRespawn) {
                vs.respawning = true;
                try { m.respawn(victim); } finally { vs.respawning = false; }
            }
            return;
        }
        PitModule.Session ks = killer == null ? null : m.session(killer);
        int before = ks == null ? 0 : PitLevels.levelFor(ks.stats.xp);
        boolean saved = false;
        try {
            if (m.storageFailed || m.store == null) throw new IllegalStateException("Pit storage blocked");
            saved = m.store.apply(UUID.randomUUID(), victim.getUniqueId(), killer == null ? null : killer.getUniqueId(),
                    killer == null ? 0 : PitRewards.KILL_GOLD, killer == null ? 0 : PitRewards.KILL_XP, null);
            if (!saved) throw new IllegalStateException("unexpected duplicate event");
            // Database is authoritative: never write session snapshots over committed rows.
            PitStatsStore.Stats v = m.store.load(victim.getUniqueId());
            PitStatsStore.Stats k = killer == null ? null : m.store.load(killer.getUniqueId());
            vs.stats = v;
            if (ks != null) ks.stats = k;
        } catch (Exception ex) {
            saved = false;
            m.storageFailed = true;
            m.blockedStats.add(victim.getUniqueId());
            if (killer != null) m.blockedStats.add(killer.getUniqueId());
            m.plugin.getLogger().log(Level.SEVERE, "Pit transaction failed or outcome uncertain; storage blocked; reconcile before further use", ex);
            victim.sendMessage(ChatColor.RED + "Pit save failed or outcome is uncertain; death may not be saved. Contact staff.");
            if (killer != null) killer.sendMessage(ChatColor.RED + "Pit save failed or outcome is uncertain; reward may not be saved. Contact staff.");
        }
        if (saved && killer != null) {
            ks.streak++;
            int after = PitLevels.levelFor(ks.stats.xp);
            if (after > before) killer.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "PIT LEVEL UP! " + ChatColor.GRAY + before + " -> " + after);
            killer.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "KILL! " + ChatColor.GRAY + "on " + victim.getName()
                    + " " + ChatColor.AQUA + "+" + PitRewards.KILL_XP + "XP " + ChatColor.GOLD + "+" + PitRewards.KILL_GOLD + ".00g");
            // Kill reward: restore full health and give golden apple
            killer.setHealth(killer.getMaxHealth());
            ItemStack goldenApple = new ItemStack(Material.GOLDEN_APPLE, 1);
            for (ItemStack remainder : killer.getInventory().addItem(goldenApple).values())
                killer.getWorld().dropItem(killer.getLocation(), remainder);
            victim.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "DEATH! " + ChatColor.GRAY + "by " + killer.getName());
            PitBoard.update(m, killer);
        } else if (saved) {
            victim.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "DEATH!");
        }
        if (virtualRespawn) {
            vs.respawning = true;
            try { m.respawn(victim); } finally { vs.respawning = false; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {   // fallback for /kill and anything bypassing the damage hook
        if (!m.inPit(e.getEntity())) return;
        // A genuine Bukkit death is a separate event even if the prior virtual
        // respawn has not yet received a movement packet. Do not teleport a dead
        // player here; onRespawn restores the kit after vanilla respawn.
        m.session(e.getEntity()).awaitingRespawnMove = false;
        die(e.getEntity(), false);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (m.sessions.containsKey(e.getPlayer().getUniqueId()) && PitWorld.isPit(e.getPlayer().getWorld())) {
            e.setRespawnLocation(PitWorld.spawn(m.world));
            final Player p = e.getPlayer();
            m.plugin.getServer().getScheduler().runTask(m.plugin, new Runnable() { public void run() { if (m.inPit(p)) m.respawn(p); } });
        }
    }

    @EventHandler public void onFood(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player && m.inPit((Player) e.getEntity())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (m.inPit(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent e) { if (m.inPit(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (m.inPit(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPickup(PlayerPickupItemEvent e) { if (m.inPit(e.getPlayer())) e.setCancelled(true); }

    /** Falling out of the map (below VOID_Y) counts as a death; the last hitter gets the kill. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!m.inPit(e.getPlayer())) return;
        PitModule.Session s = m.session(e.getPlayer());
        if (s.awaitingRespawnMove) {
            if (!s.respawning && e.getFrom().getY() >= PitLayout.VOID_Y && e.getTo().getY() >= PitLayout.VOID_Y) s.awaitingRespawnMove = false;
            return;
        }
        if (e.getTo().getY() < PitLayout.VOID_Y) die(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PitModule.Session s = m.session(e.getPlayer());
        if (s != null && !s.respawning && m.inPit(e.getPlayer()) && e.getFrom().getWorld().equals(e.getTo().getWorld()) && e.getFrom().distanceSquared(e.getTo()) > 1.0) s.awaitingRespawnMove = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { if (!PitWorld.isPit(e.getPlayer().getWorld())) m.leave(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { m.leave(e.getPlayer()); }
}

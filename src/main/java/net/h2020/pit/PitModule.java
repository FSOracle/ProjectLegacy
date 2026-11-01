package net.h2020.pit;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.game.GameManager;
import net.h2020.core.game.GameManagerImpl;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** The Hypixel Pit: one persistent generated world, /pit to join, /lobby to leave. */
public final class PitModule implements Module, CommandExecutor {
    /** Per-player in-Pit state; stats mirror the persisted record. */
    static final class Session {
        PitStatsStore.Stats stats;
        int streak;
        boolean awaitingRespawnMove, respawning;
        UUID lastHitter;
        long lastHitAt, lastCombatAt;
        Session(PitStatsStore.Stats s) { stats = s; }
    }

    JavaPlugin plugin;
    SqlitePitStore store;
    boolean storageFailed;
    PitRunGuard runGuard;
    World world;
    final Map<UUID, Session> sessions = new HashMap<>();
    // Uncertain writes must not be overwritten or replayed within this process.
    final Set<UUID> blockedStats = new HashSet<>();
    private BukkitTask ticker;
    PitBotManager bots;

    @Override public String name() { return "pit"; }

    @Override
    public void enable(JavaPlugin plugin) {
        this.plugin = plugin;
        PluginCommand c = plugin.getCommand("pit");
        if (c != null) c.setExecutor(this);
        try {
            runGuard = PitRunGuard.acquire(plugin.getDataFolder().toPath().resolve("pit-run.state"));
            store = new SqlitePitStore(plugin.getDataFolder().toPath().resolve("pit-stats"));
            world = PitWorld.create(plugin.getLogger(), plugin.getDataFolder().toPath());
        } catch (Exception e) {
            storageFailed = true;
            plugin.getLogger().log(Level.SEVERE, "Pit initialization unavailable; entry disabled", e);
            return;
        }
        bots = new PitBotManager(this);
        bots.start();
        Bukkit.getPluginManager().registerEvents(new PitListener(this), plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { for (Player p : world.getPlayers()) PitBoard.update(PitModule.this, p); }
        }, 20L, 20L);
    }

    @Override
    public void disable() {
        boolean cleanShutdown = !storageFailed && store != null && runGuard != null;
        try {
            if (bots != null && !bots.stop()) { cleanShutdown = false; storageFailed = true; }
            if (ticker != null) ticker.cancel();
            if (world != null) for (Player p : world.getPlayers()) {
                GameManagerImpl.resetPlayer(p, GameMode.ADVENTURE);
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
            sessions.clear();
        } catch (Throwable e) {
            cleanShutdown = false;
            plugin.getLogger().log(Level.SEVERE, "Pit shutdown failed; marker remains dirty", e);
        }
        if (store != null) try { store.close(); } catch (Throwable e) {
            cleanShutdown = false;
            plugin.getLogger().log(Level.SEVERE, "Pit storage close failed", e);
        }
        if (runGuard != null) {
            if (cleanShutdown) try { runGuard.clean(); } catch (Throwable e) {
                plugin.getLogger().log(Level.SEVERE, "Pit marker clean failed; attempting to restore dirty marker", e);
                try { runGuard.invalidate(); } catch (Throwable dirty) {
                    e.addSuppressed(dirty);
                    plugin.getLogger().log(Level.SEVERE, "Could not restore dirty marker; storage outcome is untrusted", dirty);
                }
            }
            try { runGuard.close(); } catch (Throwable e) { plugin.getLogger().log(Level.SEVERE, "Pit guard close failed; offline inspection required", e); }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
        Player p = (Player) sender;
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null && gm.gameOf(p) != null) {
            p.sendMessage(ChatColor.RED + "You are already in a game! Use /lobby first.");
            return true;
        }
        if (sessions.containsKey(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "You are already in The Pit!"); return true; }
        if (storageFailed || store == null || blockedStats.contains(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Your Pit save status is uncertain. Contact staff; offline inspection is required.");
            return true;
        }
        PitStatsStore.Stats s;
        try { s = store.load(p.getUniqueId()); }
        catch (Exception e) {
            storageFailed = true;
            plugin.getLogger().log(Level.WARNING, "Pit stats unreadable for " + p.getName(), e);
            p.sendMessage(ChatColor.RED + "Your Pit data could not be loaded. Please contact staff.");
            return true;
        }
        org.bukkit.Location origin = p.getLocation().clone();
        org.bukkit.Location destination = PitWorld.spawn(world);
        boolean moved = p.teleport(destination);
        if (!moved || !p.getWorld().equals(world)
                || p.getLocation().distanceSquared(destination) > 4.0) {
            if (!p.getWorld().equals(origin.getWorld())
                    || p.getLocation().distanceSquared(origin) > 0.01) {
                if (!p.teleport(origin)) plugin.getLogger().warning("Pit entry failed; return teleport was refused for " + p.getName());
            }
            p.sendMessage(ChatColor.RED + "Could not enter The Pit. Please try again later.");
            return true;
        }
        sessions.put(p.getUniqueId(), new Session(s));
        GameManagerImpl.resetPlayer(p, GameMode.SURVIVAL);
        PitKit.give(p);
        PitBoard.update(this, p);
        p.sendMessage(ChatColor.GREEN + "Sending you to " + ChatColor.YELLOW + "The Pit" + ChatColor.GREEN + "! Jump into the hole to fight.");
        return true;
    }

    Session session(Player p) { return sessions.get(p.getUniqueId()); }

    boolean inPit(Player p) { return PitWorld.isPit(p.getWorld()) && sessions.containsKey(p.getUniqueId()); }

    /** Back to the spawn platform with a full kit; no death screen, nothing dropped. */
    void respawn(Player p) {
        p.setFallDistance(0);
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        p.teleport(PitWorld.spawn(world));
        for (org.bukkit.potion.PotionEffect e : new java.util.ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        PitKit.give(p);
        PitBoard.update(this, p);
    }

    /** Drop all in-Pit state (quit, world change, /lobby). Stats are already persisted on every credit. */
    void leave(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        for (Session o : sessions.values()) if (p.getUniqueId().equals(o.lastHitter)) o.lastHitter = null;
        plugin.getLogger().info("Pit session closed for " + p.getName());
    }
}

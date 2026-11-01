package net.h2020.duels;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameProvider;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapTemplate;
import net.h2020.core.CoreUtil;
import net.h2020.core.prefs.BotMatchPrefs;
import net.h2020.duels.rules.BotFillPolicy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Duels family: one GameProvider for GameFamily.DUELS that dispatches by GameType (Classic, Boxing, NoDebuff,
 * Bridge). Humans queue through the core /play; a lone waiting human who accepts bots for that mode gets a bot
 * opponent after duels.bot-wait-seconds (default 10). /duels bots [mode on|off] manages the per-mode preference.
 */
public final class DuelsModule implements Module, GameProvider, Listener, CommandExecutor {
    static final GameType[] MODES = {GameType.DUELS_CLASSIC, GameType.DUELS_BOXING, GameType.DUELS_NODEBUFF, GameType.DUELS_BRIDGE};
    static final String[] MODE_KEYS = {"classic", "boxing", "nodebuff", "bridge"};

    private JavaPlugin plugin;
    private final List<DuelGame> games = new ArrayList<>();
    private BotMatchPrefs prefs;
    private long botWaitMs = 10000;
    private BukkitTask matcher;
    private final Map<UUID, Long> lobbyConfirm = new HashMap<>();
    /** victim -> (attacker, ms) for void-kill credit and boxing hit pacing */
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, Long> lastAttackAt = new HashMap<>();
    private final Map<UUID, Long> lastCountedHit = new HashMap<>();

    @Override public String name() { return "duels"; }
    @Override public GameFamily family() { return GameFamily.DUELS; }
    JavaPlugin plugin() { return plugin; }
    void log(String s) { plugin.getLogger().info(s); }

    @Override
    public void enable(JavaPlugin p) {
        plugin = p;
        botWaitMs = Math.max(0L, (long) (p.getConfig().getDouble("duels.bot-wait-seconds", 10.0) * 1000));
        prefs = new BotMatchPrefs(new File(p.getDataFolder(), "bot-match-prefs.txt").toPath());
        if (!prefs.available()) p.getLogger().severe(prefs.unavailableReason());
        H2020.get(GameManager.class).registerProvider(this);
        Bukkit.getPluginManager().registerEvents(this, p);
        PluginCommand cmd = p.getCommand("duels");
        if (cmd != null) cmd.setExecutor(this);
        matcher = Bukkit.getScheduler().runTaskTimer(p, new Runnable() { @Override public void run() { matchBots(); } }, 20L, 10L);
        log("DUELS_ENABLED bot-wait-ms=" + botWaitMs);
    }

    @Override
    public void disable() {
        if (matcher != null) { matcher.cancel(); matcher = null; }
        for (DuelGame g : new ArrayList<>(games)) {
            try { g.shutdown(); } catch (Throwable t) { plugin.getLogger().warning("duel shutdown failed: " + t); }
        }
        games.clear();
        HandlerList.unregisterAll(this);
    }

    @Override
    public Game create(GameType type, MapTemplate map, String gameId) {
        if (type == null || type.family != GameFamily.DUELS) throw new IllegalArgumentException("not a duels mode: " + type);
        DuelGame g = new DuelGame(this, type, gameId);
        games.add(g);
        return g;
    }

    void forget(DuelGame g) { games.remove(g); }

    DuelGame in(World w) {
        if (w == null) return null;
        for (DuelGame g : games) if (!g.closed() && g.world.equals(w)) return g;
        return null;
    }

    private DuelGame of(Player p) {
        DuelGame g = in(p.getWorld());
        return g != null && g.side(p) >= 0 ? g : null;
    }

    // ------------------------------------------------------------------ bots
    BotMatchPrefs prefs() { return prefs; }

    private void matchBots() {
        BotService bs = H2020.find(BotService.class);
        long now = System.currentTimeMillis();
        for (final DuelGame g : new ArrayList<>(games)) {
            if (g.closed() || g.state != GameState.WAITING || g.reservedBot != null) continue;
            Player human = g.firstHuman();
            if (human == null) continue;
            boolean accepts = prefs.accepts(human.getUniqueId(), g.type);
            if (!BotFillPolicy.shouldAddBot(accepts, g.humans(), g.bots(), g.type.maxPlayers(), now - g.waitingSince, botWaitMs)) continue;
            if (bs == null) continue;
            final Bot bot;
            try { bot = bs.spawn(bs.randomProfile(), g.arena.spawn(1)); }
            catch (Throwable t) { plugin.getLogger().warning("duel bot spawn failed: " + t); continue; }
            if (bot == null || bot.player() == null) continue;
            g.reservedBot = bot.player().getUniqueId();
            log("DUELS_BOT_RESERVED game=" + g.id() + " mode=" + g.type.id() + " bot=" + bot.player().getName() + " for=" + human.getName() + " waitedMs=" + (now - g.waitingSince));
            final UUID waitingHumanId = human.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    Player bp = bot.player();
                    Player waitingHuman = g.firstHuman();
                    boolean ok = !bot.isRemoved() && bp != null && bp.isOnline() && !g.closed() && g.state == GameState.WAITING
                            && g.humans() == 1 && waitingHuman != null && waitingHumanId.equals(waitingHuman.getUniqueId())
                            && prefs.accepts(waitingHumanId, g.type);
                    if (ok) {
                        try { ok = g.addPlayer(bp); } catch (Throwable t) { ok = false; }
                    }
                    if (!ok) {
                        if (bp != null && bp.getUniqueId().equals(g.reservedBot)) g.reservedBot = null;
                        if (!bot.isRemoved()) bot.remove();
                    }
                }
            }, 2L);
        }
    }

    void brainFor(Player p, DuelGame g) {
        BotService bs = H2020.find(BotService.class);
        Bot b = bs == null ? null : bs.bot(p);
        if (b != null) b.setBrain(new DuelBrain(g));
    }

    void removeBot(Player p) {
        BotService bs = H2020.find(BotService.class);
        Bot b = bs == null ? null : bs.bot(p);
        if (b != null && !b.isRemoved()) b.remove();
    }

    // ------------------------------------------------------------------ command
    static GameType modeOf(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.equals("potion") || k.equals("nodebuff") || k.equals("nd")) return GameType.DUELS_NODEBUFF;
        for (int i = 0; i < MODES.length; i++) if (MODE_KEYS[i].equals(k)) return MODES[i];
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
        Player p = (Player) sender;
        if (args.length >= 1 && args[0].equalsIgnoreCase("lobby")) {
            GameManager gm = H2020.find(GameManager.class);
            if (gm != null) gm.sendToLobby(p, GameFamily.DUELS);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("leave")) { leave(p); return true; }
        if (args.length >= 1 && args[0].equalsIgnoreCase("bots")) {
            if (args.length == 1) {
                if (!prefs.available()) { p.sendMessage(ChatColor.RED + prefs.unavailableReason()); return true; }
                StringBuilder sb = new StringBuilder(ChatColor.YELLOW + "Duels bot opponents:");
                for (int i = 0; i < MODES.length; i++) {
                    boolean on = prefs.accepts(p.getUniqueId(), MODES[i]);
                    sb.append(' ').append(ChatColor.AQUA).append(MODE_KEYS[i]).append('=').append(on ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF");
                }
                p.sendMessage(sb.toString());
                return true;
            }
            if (args.length == 3) {
                GameType t = modeOf(args[1]);
                String v = args[2].toLowerCase(Locale.ROOT);
                if (t != null && (v.equals("on") || v.equals("off"))) {
                    boolean on = v.equals("on");
                    try { prefs.set(p.getUniqueId(), t, on); }
                    catch (IllegalStateException failure) {
                        plugin.getLogger().warning("DUELS_PREF_FAILED player=" + p.getName() + " mode=" + t.id() + " " + failure.getMessage());
                        p.sendMessage(ChatColor.RED + "Bot preference unavailable: " + failure.getMessage());
                        return true;
                    }
                    log("DUELS_PREF player=" + p.getName() + " mode=" + t.id() + " bots=" + (on ? "on" : "off"));
                    p.sendMessage(ChatColor.YELLOW + "Bot opponents for " + t.displayName + " are now " + (on ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF") + ChatColor.YELLOW + ".");
                    return true;
                }
            }
        }
        p.sendMessage(ChatColor.RED + "Usage: /duels bots [classic|boxing|nodebuff|bridge] [on|off]  |  /duels leave");
        return true;
    }

    private void leave(Player p) {
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null) gm.sendToLobby(p, GameFamily.DUELS);
    }

    /** /lobby, /l, /hub, /leave inside a duel go to the main lobby (there is no Duels lobby world). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommandPre(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        DuelGame g = of(p);
        if (g == null) return;
        String c = e.getMessage().toLowerCase(Locale.ROOT).trim().split("\\s+")[0];
        if (!(c.equals("/lobby") || c.equals("/l") || c.equals("/hub") || c.equals("/leave"))) return;
        e.setCancelled(true);
        if (g.state == GameState.PLAYING) {
            Long at = lobbyConfirm.get(p.getUniqueId());
            long now = System.currentTimeMillis();
            if (at == null || now - at > 10000) {
                lobbyConfirm.put(p.getUniqueId(), now);
                p.sendMessage(ChatColor.YELLOW + "Are you sure? Type /lobby again if you really want to quit.");
                return;
            }
        }
        lobbyConfirm.remove(p.getUniqueId());
        String[] parts = e.getMessage().toLowerCase(Locale.ROOT).trim().split("\\s+");
        GameFamily target = GameFamily.DUELS;
        if (parts.length > 1) {
            String arg = parts[1];
            if (arg.startsWith("b")) target = GameFamily.BEDWARS;
            else if (arg.startsWith("s")) target = GameFamily.SKYWARS;
            else if (!arg.equals("duels")) target = null;
        }
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null) gm.sendToLobby(p, target);
    }

    // ------------------------------------------------------------------ rules
    private static Player attacker(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent)) return null;
        Entity d = ((EntityDamageByEntityEvent) e).getDamager();
        if (d instanceof Projectile) { Object s = ((Projectile) d).getShooter(); d = s instanceof Entity ? (Entity) s : null; }
        return d instanceof Player ? (Player) d : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        DuelGame g = in(e.getEntity().getWorld());
        if (g == null) return;
        if (!(e.getEntity() instanceof Player)) { if (e instanceof EntityDamageByEntityEvent) e.setCancelled(true); return; }
        Player victim = (Player) e.getEntity();
        if (g.side(victim) < 0 || g.state != GameState.PLAYING || g.paused) { e.setCancelled(true); return; }
        Player att = attacker(e);
        if (e instanceof EntityDamageByEntityEvent && (att == null || att.equals(victim) || g.side(att) < 0)) { e.setCancelled(true); return; }
        if (g.type == GameType.DUELS_BOXING) {
            if (att == null) { e.setCancelled(true); return; }
            for (EntityDamageEvent.DamageModifier m : EntityDamageEvent.DamageModifier.values())
                if (e.isApplicable(m)) e.setDamage(m, 0);
            return;
        }
        if (att != null) { lastAttacker.put(victim.getUniqueId(), att.getUniqueId()); lastAttackAt.put(victim.getUniqueId(), System.currentTimeMillis()); }
        if (e.getFinalDamage() >= victim.getHealth()) {
            e.setCancelled(true);
            Player killer = att != null ? att : recentAttacker(victim);
            if (g.type == GameType.DUELS_BRIDGE) g.bridgeRespawn(victim, killer);
            else g.finish(g.opponent(victim), victim, "KILL");
        }
    }

    private Player recentAttacker(Player victim) {
        Long at = lastAttackAt.get(victim.getUniqueId());
        UUID id = lastAttacker.get(victim.getUniqueId());
        if (at == null || id == null || System.currentTimeMillis() - at > 10000) return null;
        return Bukkit.getPlayer(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player) || !(e.getDamager() instanceof Player)) return;
        DuelGame g = in(e.getEntity().getWorld());
        if (g == null || g.type != GameType.DUELS_BOXING || g.state != GameState.PLAYING) return;
        Player victim = (Player) e.getEntity(), att = (Player) e.getDamager();
        int side = g.side(att);
        if (side < 0 || g.side(victim) < 0) return;
        long now = System.currentTimeMillis();
        Long last = lastCountedHit.get(victim.getUniqueId());
        if (last != null && now - last < 450) return; // one valid hit per hurt window
        lastCountedHit.put(victim.getUniqueId(), now);
        boolean won = g.boxing.hit(side);
        int n = g.boxing.hits(side);
        if (n % 10 == 0 || won) log("DUELS_HITS game=" + g.id() + " player=" + att.getName() + " bot=" + CoreUtil.isBot(att) + " hits=" + n);
        att.setLevel(n);
        if (won) g.finish(att, victim, "HITS");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        final Player p = e.getEntity();
        DuelGame g = in(p.getWorld());
        if (g == null) return;
        e.getDrops().clear(); e.setDroppedExp(0); e.setDeathMessage(null);
        if (g.side(p) >= 0 && g.state == GameState.PLAYING) g.finish(g.opponent(p), p, "KILL");
        if (!CoreUtil.isBot(p)) Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { if (p.isOnline() && p.isDead()) p.spigot().respawn(); }
        }, 2L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        DuelGame g = in(e.getPlayer().getWorld());
        if (g != null) e.setRespawnLocation(g.arena.spawn(Math.max(0, g.side(e.getPlayer()))));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Location from = e.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Player p = e.getPlayer();
        DuelGame g = of(p);
        if (g == null) return;
        int side = g.side(p);
        if (to.getY() < g.arena.voidY) {
            if (g.state == GameState.PLAYING && !g.paused) {
                if (g.type == GameType.DUELS_BRIDGE) g.bridgeRespawn(p, recentAttacker(p));
                else g.finish(g.opponent(p), p, "VOID");
            } else {
                p.setFallDistance(0);
                p.teleport(g.arena.spawn(side));
            }
            return;
        }
        if (g.type != GameType.DUELS_BRIDGE || g.state != GameState.PLAYING) return;
        if (g.paused) {
            Location s = g.arena.spawn(side);
            double dx = to.getX() - s.getX(), dz = to.getZ() - s.getZ();
            if (dx * dx + dz * dz > 4) e.setTo(s);
            return;
        }
        for (int i = 0; i < 2; i++) if (g.arena.goals[i].contains(to)) { g.bridgeScore(p, i); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        DuelGame g = in(e.getBlock().getWorld());
        if (g == null) return;
        Block b = e.getBlock();
        if (g.type == GameType.DUELS_CLASSIC && b.getType() == Material.FIRE && g.state == GameState.PLAYING) return;
        boolean ok = g.type == GameType.DUELS_BRIDGE && g.state == GameState.PLAYING && !g.paused && g.side(e.getPlayer()) >= 0
                && g.arena.build.contains(b) && !nearGoal(g, b);
        if (!ok) {
            e.setCancelled(true);
            if (g.type == GameType.DUELS_BRIDGE && g.state == GameState.PLAYING) log("DUELS_PLACE_DENY game=" + g.id() + " player=" + e.getPlayer().getName() + " at=" + b.getX() + "," + b.getY() + "," + b.getZ());
            return;
        }
        g.placed.add(b);
        log("DUELS_PLACE game=" + g.id() + " player=" + e.getPlayer().getName() + " at=" + b.getX() + "," + b.getY() + "," + b.getZ() + " tracked=" + g.placed.size());
    }

    private static boolean nearGoal(DuelGame g, Block b) {
        for (DuelArena.Box box : g.arena.goals)
            if (b.getX() >= box.x1 - 1 && b.getX() <= box.x2 + 1 && b.getZ() >= box.z1 - 1 && b.getZ() <= box.z2 + 1 && b.getY() >= box.y1 && b.getY() <= box.y2 + 3) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        DuelGame g = in(e.getBlock().getWorld());
        if (g == null) return;
        if (g.type == GameType.DUELS_BRIDGE && g.state == GameState.PLAYING && g.placed.remove(e.getBlock())) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        DuelGame g = in(e.getEntity().getWorld());
        if (g != null && (g.type == GameType.DUELS_BOXING || g.type == GameType.DUELS_BRIDGE || g.state != GameState.PLAYING)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (in(e.getPlayer().getWorld()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) { if (in(e.getPlayer().getWorld()) != null) { e.setCancelled(true); e.getItem().remove(); } }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        final Player p = (Player) e.getEntity();
        final DuelGame g = of(p);
        if (g == null || g.type != GameType.DUELS_BRIDGE) return;
        log("DUELS_BOW game=" + g.id() + " player=" + p.getName() + " cooldownTicks=70");
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (g.closed() || g.state != GameState.PLAYING || g.side(p) < 0 || !p.isOnline()) return;
                if (!p.getInventory().contains(Material.ARROW)) {
                    p.getInventory().setItem(8, new ItemStack(Material.ARROW, 1));
                    log("DUELS_ARROW_REFILL game=" + g.id() + " player=" + p.getName());
                }
            }
        }, 70L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        Object s = e.getPotion().getShooter();
        if (!(s instanceof Player)) return;
        Player p = (Player) s;
        DuelGame g = of(p);
        if (g == null) return;
        log("DUELS_POTION game=" + g.id() + " thrower=" + p.getName() + " bot=" + CoreUtil.isBot(p) + " health=" + String.format(Locale.ROOT, "%.1f", p.getHealth()));
    }
}

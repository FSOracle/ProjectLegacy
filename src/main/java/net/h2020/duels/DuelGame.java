package net.h2020.duels;

import net.h2020.api.H2020;
import net.h2020.api.core.PlayerNames;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.api.game.GameType;
import net.h2020.api.game.HumanPriorityGame;
import net.h2020.api.game.events.GameStateChangeEvent;
import net.h2020.core.CoreUtil;
import net.h2020.core.game.GameManagerImpl;
import net.h2020.duels.rules.BoxingScore;
import net.h2020.duels.rules.BridgeScore;
import net.h2020.maps.Worlds;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** One 1v1 Duels match in its own generated world. All methods run on the main thread. */
final class DuelGame implements Game, HumanPriorityGame {
    static final int COUNTDOWN = 5, END_SECONDS = 5, BRIDGE_PAUSE = 3;

    private final DuelsModule module;
    private final String id;
    final GameType type;
    final World world;
    final DuelArena arena;
    private final List<Player> players = new ArrayList<>();
    GameState state = GameState.WAITING;
    private BukkitTask timer;
    private int seconds = -1;
    private boolean closed;
    /** when the current lone human started waiting (ms) */
    long waitingSince = System.currentTimeMillis();
    /** bot spawned for this game but not added yet */
    UUID reservedBot;
    final BoxingScore boxing = new BoxingScore(100);
    final BridgeScore bridge = new BridgeScore(5);
    boolean paused;
    final Set<Block> placed = new HashSet<>();

    DuelGame(DuelsModule module, GameType type, String id) {
        this.module = module; this.type = type; this.id = id;
        String leaf = "duel_" + UUID.randomUUID().toString();
        String name = Worlds.INSTANCE_DIR + "/" + leaf;
        try { Worlds.reserveInstance(leaf); }
        catch (java.io.IOException e) { throw new IllegalStateException("could not reserve duel world " + name, e); }
        world = Worlds.loadVoid(name, type == GameType.DUELS_NODEBUFF ? Biome.HELL : Biome.PLAINS, 0.5, 65, 0.5);
        if (world == null) throw new IllegalStateException("could not create duel world " + name);
        Worlds.applyRules(world, true);
        boolean regen = type == GameType.DUELS_BRIDGE || type == GameType.DUELS_BOXING;
        world.setGameRuleValue("naturalRegeneration", String.valueOf(regen));
        world.setGameRuleValue("doTileDrops", "false");
        world.setSpawnLocation(0, 65, 0);
        arena = DuelArena.build(world, type);
        module.log("DUELS_CREATE game=" + id + " mode=" + type.id() + " map=" + arena.name.replace(' ', '_') + " world=" + world.getName());
    }

    // ------------------------------------------------------------------ Game
    @Override public String id() { return id; }
    @Override public GameType type() { return type; }
    @Override public GameState state() { return state; }
    @Override public World world() { return world; }
    @Override public String mapName() { return arena.name; }
    @Override public List<Player> players() { return Collections.unmodifiableList(new ArrayList<>(players)); }
    @Override public List<Player> alivePlayers() { return state == GameState.ENDING ? Collections.<Player>emptyList() : players(); }
    @Override public List<? extends GameTeam> teams() { return Collections.emptyList(); }
    @Override public GameTeam teamOf(Player p) { return null; }
    @Override public boolean isSpectator(Player p) { return false; }
    @Override public int secondsUntilStart() { return state == GameState.STARTING ? seconds : -1; }
    @Override public Location spectatorSpawn() { return arena.spawn(0); }

    boolean closed() { return closed; }
    void log(String s) { module.log(s); }
    int side(Player p) { return players.indexOf(p); }
    Player opponent(Player p) {
        for (Player q : players) if (!q.equals(p)) return q;
        return null;
    }
    int humans() { int n = 0; for (Player p : players) if (!CoreUtil.isBot(p)) n++; return n; }
    int bots() { int n = 0; for (Player p : players) if (CoreUtil.isBot(p)) n++; return n; }
    Player firstHuman() { for (Player p : players) if (!CoreUtil.isBot(p)) return p; return null; }

    private void change(GameState to) {
        GameState from = state;
        state = to;
        module.log("DUELS_STATE game=" + id + " mode=" + type.id() + " " + from + "->" + to + " players=" + names());
        Bukkit.getPluginManager().callEvent(new GameStateChangeEvent(this, from, to));
    }

    String names() {
        StringBuilder sb = new StringBuilder();
        for (Player p : players) { if (sb.length() > 0) sb.append(','); sb.append(p.getName()); if (CoreUtil.isBot(p)) sb.append("(bot)"); }
        return sb.toString();
    }

    /** Before play begins, a real queued player can take a bot's slot. */
    @Override
    public boolean releaseBotForHuman() {
        if (closed || state != GameState.STARTING || humans() != 1 || bots() != 1 || players.size() != 2) return false;
        Player bot = null;
        for (Player p : players) if (CoreUtil.isBot(p)) { bot = p; break; }
        if (bot == null) return false;
        removePlayer(bot); // cancels countdown and returns to WAITING
        module.removeBot(bot);
        return !closed && state == GameState.WAITING && humans() == 1 && bots() == 0;
    }

    @Override
    public boolean addPlayer(Player p) {
        if (closed || p == null || state != GameState.WAITING || players.size() >= 2 || players.contains(p)) return false;
        boolean bot = CoreUtil.isBot(p);
        if (bot && !p.getUniqueId().equals(reservedBot)) return false; // bots only through the Duels matcher
        if (bot && humans() != 1) return false;
        int side = players.size();
        GameManagerImpl.resetPlayer(p, GameMode.ADVENTURE);
        if (!p.teleport(arena.spawn(side))) return false;
        players.add(p);
        if (bot) reservedBot = null;
        else if (humans() == 1) waitingSince = System.currentTimeMillis();
        broadcast(PlayerNames.display(p) + ChatColor.YELLOW + " has joined (" + ChatColor.AQUA + players.size() + ChatColor.YELLOW + "/" + ChatColor.AQUA + "2" + ChatColor.YELLOW + ")!");
        if (!bot) p.sendMessage(ChatColor.YELLOW + type.displayName + " on " + ChatColor.GREEN + arena.name + ChatColor.YELLOW + ". Waiting for an opponent...");
        if (!bot && !module.prefs().available()) p.sendMessage(ChatColor.RED + module.prefs().unavailableReason());
        module.log("DUELS_JOIN game=" + id + " mode=" + type.id() + " player=" + p.getName() + " bot=" + bot + " side=" + side);
        if (bot) module.brainFor(p, this);
        if (players.size() == 2) startCountdown();
        return true;
    }

    private void startCountdown() {
        change(GameState.STARTING);
        seconds = COUNTDOWN;
        timer = Bukkit.getScheduler().runTaskTimer(module.plugin(), new Runnable() {
            @Override public void run() {
                if (closed || state != GameState.STARTING) { cancelTimer(); return; }
                if (seconds > 0) {
                    broadcast(ChatColor.YELLOW + "The game starts in " + ChatColor.RED + seconds + ChatColor.YELLOW + " second" + (seconds == 1 ? "" : "s") + "!");
                    seconds--;
                    return;
                }
                cancelTimer();
                begin();
            }
        }, 0L, 20L);
    }

    private void begin() {
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            p.teleport(arena.spawn(i));
            DuelKits.give(type, p, i);
            module.log("DUELS_KIT game=" + id + " player=" + p.getName() + " " + DuelKits.describe(p));
        }
        change(GameState.PLAYING);
        Player a = players.get(0), b = players.get(1);
        broadcast(ChatColor.GREEN + "" + ChatColor.BOLD + "FIGHT! " + ChatColor.RESET + ChatColor.YELLOW + a.getName() + " vs " + b.getName());
        if (type == GameType.DUELS_BOXING) broadcast(ChatColor.YELLOW + "First to " + boxing.target() + " hits wins!");
        if (type == GameType.DUELS_BRIDGE) broadcast(ChatColor.YELLOW + "Score " + bridge.target() + " goals in the enemy portal to win!");
    }

    private void cancelTimer() {
        if (timer != null) { timer.cancel(); timer = null; }
        seconds = -1;
    }

    @Override
    public void removePlayer(Player p) {
        if (!players.contains(p)) return;
        boolean bot = CoreUtil.isBot(p);
        module.log("DUELS_LEAVE game=" + id + " mode=" + type.id() + " player=" + p.getName() + " bot=" + bot + " state=" + state);
        if (state == GameState.PLAYING) {
            Player other = opponent(p);
            players.remove(p);
            finish(other, p, "FORFEIT");
            return;
        }
        players.remove(p);
        if (state == GameState.ENDING) return;
        if (state == GameState.STARTING) {
            cancelTimer();
            change(GameState.WAITING);
            broadcast(ChatColor.RED + p.getName() + " left. Countdown cancelled, waiting for a new opponent...");
            for (int i = 0; i < players.size(); i++) players.get(i).teleport(arena.spawn(i));
        }
        if (humans() == 0) { shutdown(); return; }
        // lone human again: bots left over go away, the wait for an opponent restarts
        for (Player q : new ArrayList<>(players)) if (CoreUtil.isBot(q)) { players.remove(q); module.removeBot(q); }
        waitingSince = System.currentTimeMillis();
    }

    void broadcast(String msg) { for (Player p : players) if (p.isOnline()) p.sendMessage(msg); }

    /** End the match; winner may be null (cancelled). Idempotent. */
    void finish(Player winner, Player loser, String reason) {
        if (closed || state == GameState.ENDING) return;
        cancelTimer();
        paused = false;
        String detail = "";
        if (type == GameType.DUELS_BOXING) detail = " hits=" + boxing.hits(0) + "-" + boxing.hits(1);
        if (type == GameType.DUELS_BRIDGE) detail = " goals=" + bridge.points(0) + "-" + bridge.points(1);
        change(GameState.ENDING);
        module.log("DUELS_RESULT game=" + id + " mode=" + type.id() + " winner=" + (winner == null ? "none" : winner.getName())
                + " winnerBot=" + (winner != null && CoreUtil.isBot(winner)) + " loser=" + (loser == null ? "none" : loser.getName())
                + " reason=" + reason + detail);
        String line = winner == null ? ChatColor.RED + "The match was cancelled." : PlayerNames.display(winner) + ChatColor.YELLOW + " WON the " + type.displayName + "! " + ChatColor.GRAY + "(" + reason + ")";
        broadcast(ChatColor.GREEN + "" + ChatColor.BOLD + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        broadcast(line + ChatColor.GRAY + detail);
        broadcast(ChatColor.GREEN + "" + ChatColor.BOLD + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        for (Player p : players) {
            p.setHealth(p.getMaxHealth());
            p.setFireTicks(0);
            p.setFallDistance(0);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getInventory().setArmorContents(new ItemStack[4]);
            p.setAllowFlight(true);
        }
        if (!module.plugin().isEnabled()) { shutdown(); return; }
        timer = Bukkit.getScheduler().runTaskLater(module.plugin(), new Runnable() {
            @Override public void run() { timer = null; shutdown(); }
        }, 20L * END_SECONDS);
    }

    /** Evacuate (humans to lobby with a full reset, bots disconnected), unregister, unload + delete the world. */
    @Override
    public void shutdown() {
        if (closed) return;
        closed = true;
        cancelTimer();
        if (state != GameState.ENDING) {
            GameState from = state; state = GameState.ENDING;
            try { Bukkit.getPluginManager().callEvent(new GameStateChangeEvent(this, from, GameState.ENDING)); } catch (Throwable ignored) {}
        }
        List<Player> everyone = new ArrayList<>(players);
        for (Player p : world.getPlayers()) if (!everyone.contains(p)) everyone.add(p);
        players.clear();
        boolean live = module.plugin().isEnabled();
        GameManager gm = H2020.find(GameManager.class);
        for (Player p : everyone) {
            try {
                if (CoreUtil.isBot(p)) { module.removeBot(p); continue; }
                if (!p.isOnline()) continue;
                if (live && gm != null) gm.sendToLobby(p, null);
                else { GameManagerImpl.resetPlayer(p, GameMode.ADVENTURE); p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()); }
                module.log("DUELS_EVAC game=" + id + " player=" + p.getName() + " world=" + p.getWorld().getName() + " health=" + p.getHealth() + " gm=" + p.getGameMode() + " armor=" + (p.getInventory().getChestplate() == null ? "none" : p.getInventory().getChestplate().getType()) + " effects=" + p.getActivePotionEffects().size());
            } catch (Throwable t) { module.plugin().getLogger().log(Level.WARNING, "duel evacuate failed", t); }
        }
        if (reservedBot != null) { Player rb = Bukkit.getPlayer(reservedBot); if (rb != null) module.removeBot(rb); reservedBot = null; }
        module.forget(this);
        if (live && gm != null) { try { gm.finished(this); } catch (Throwable t) { module.plugin().getLogger().log(Level.WARNING, "finished() failed", t); } }
        for (Player p : world.getPlayers()) p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        String wname = world.getName();
        boolean unloaded = Bukkit.getWorld(wname) == world && Bukkit.unloadWorld(world, false);
        module.log("DUELS_CLEANUP game=" + id + " mode=" + type.id() + " world=" + wname + " unloaded=" + unloaded + " deleted=false retained=true");
        module.plugin().getLogger().warning("[duels] retained instance " + wname + "; monitor disk use");
    }

    // ------------------------------------------------------------------ bridge
    void bridgeScore(Player p, int goal) {
        int side = side(p);
        if (side < 0 || paused) return;
        if (goal == side) { // jumped into own portal: back to spawn
            bridgeRespawn(p, null);
            return;
        }
        BridgeScore.Result r = bridge.score(side, goal);
        if (r == BridgeScore.Result.REJECTED) return;
        module.log("DUELS_GOAL game=" + id + " scorer=" + p.getName() + " bot=" + CoreUtil.isBot(p) + " score=" + bridge.points(0) + "-" + bridge.points(1));
        broadcast(ChatColor.YELLOW + p.getName() + " scored! " + ChatColor.RED + bridge.points(0) + ChatColor.GRAY + " - " + ChatColor.BLUE + bridge.points(1));
        if (r == BridgeScore.Result.WIN) { finish(p, opponent(p), "GOALS"); return; }
        resetRound();
    }

    void resetRound() {
        int restored = placed.size();
        for (Block b : placed) b.setType(Material.AIR);
        placed.clear();
        module.log("DUELS_RESTORE game=" + id + " removed=" + restored);
        for (int i = 0; i < players.size(); i++) {
            Player q = players.get(i);
            q.teleport(arena.spawn(i));
            DuelKits.give(type, q, i);
        }
        paused = true;
        Bukkit.getScheduler().runTaskLater(module.plugin(), new Runnable() {
            @Override public void run() {
                if (closed || state != GameState.PLAYING) return;
                bridge.resume();
                paused = false;
                broadcast(ChatColor.GREEN + "Go!");
            }
        }, 20L * BRIDGE_PAUSE);
    }

    void bridgeRespawn(Player p, Player killer) {
        int side = side(p);
        if (side < 0) return;
        if (killer != null) broadcast(ChatColor.GRAY + p.getName() + " was killed by " + killer.getName() + ".");
        module.log("DUELS_RESPAWN game=" + id + " player=" + p.getName() + " killer=" + (killer == null ? "none" : killer.getName()));
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.teleport(arena.spawn(side));
        DuelKits.give(type, p, side);
    }
}

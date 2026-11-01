package net.h2020.maps;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import net.h2020.maps.gen.GeneratedMap;
import net.h2020.maps.gen.LobbyGenerator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MapService implementation. On start: cleans stale instance folders, loads every template folder under
 * plugins/Hypixel2020/maps, generates the default procedural rotation for any (family, team count) group that
 * has no maps, then loads (or generates once) the three hub worlds. Everything happens synchronously during
 * module enable, so templates are ready before any game can be created.
 *
 * Optional config section "maps": always-generate-defaults (false), regenerate (false),
 * main-lobby-world / bedwars-lobby-world / skywars-lobby-world (use an existing world folder instead).
 */
public final class MapServiceImpl implements MapService {
    static final int LOBBY_VERSION = 1;

    final JavaPlugin plugin;
    private final Logger log;
    final File mapsDir;
    private final Map<String, StoredMapTemplate> templates = new LinkedHashMap<String, StoredMapTemplate>();
    /** instance world name (lower case) -> template */
    final Map<String, StoredMapTemplate> instances = new ConcurrentHashMap<String, StoredMapTemplate>();
    final Map<String, World> ownedWorlds = new ConcurrentHashMap<String, World>();
    private World mainLobby, bedwarsLobby, skywarsLobby, duelsLobby;
    private volatile boolean ready;

    public MapServiceImpl(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.mapsDir = new File(plugin.getDataFolder(), "maps");
    }

    // ============================================================================================ lifecycle
    public void start() {
        long t0 = System.currentTimeMillis();
        if (!mapsDir.isDirectory() && !mapsDir.mkdirs()) log.warning("[maps] could not create " + mapsDir);
        writeFormatHelp();
        cleanupStale();
        loadAll();
        generateMissing();
        loadLobbies();
        writeLobbyNpcs();
        ready = true;
        StringBuilder sb = new StringBuilder();
        for (StoredMapTemplate t : templates.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.name).append(' ').append(t.teamCounts);
        }
        log.info("[maps] ready in " + (System.currentTimeMillis() - t0) + "ms: " + templates.size() + " templates (" + sb + ")");
    }

    public void shutdown() {
        ready = false;
        for (World w : new ArrayList<World>(Bukkit.getWorlds())) {
            StoredMapTemplate t = instances.get(w.getName().toLowerCase());
            if (t != null) {
                try { t.destroyNow(w); } catch (Throwable e) { log.log(Level.WARNING, "[maps] failed to remove " + w.getName(), e); }
            }
        }
        instances.clear();
        ownedWorlds.clear();
    }

    public boolean isReady() { return ready; }

    private ConfigurationSection config() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("maps");
        return s != null ? s : new YamlConfiguration();
    }

    /** A name or an unloaded world is not proof of ownership. Preserve all old data. */
    private void cleanupStale() {
        File inst = new File(Worlds.container(), Worlds.INSTANCE_DIR);
        if (java.nio.file.Files.exists(inst.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            log.warning("[maps] retaining existing instances without ownership proof: " + inst + "; monitor disk use");
        File[] roots = Worlds.container().listFiles();
        if (roots != null) for (File f : roots)
            if (f.getName().startsWith(Worlds.GEN_PREFIX))
                log.warning("[maps] retaining generation path without ownership proof: " + f);
    }

    // ============================================================================================ templates
    private void loadAll() {
        templates.clear();
        File[] dirs = mapsDir.listFiles();
        if (dirs == null) return;
        java.util.Arrays.sort(dirs);
        boolean regenerate = config().getBoolean("regenerate", false);
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            if (!new File(dir, MapFiles.MAP_YML).isFile()) {
                if (new File(dir, "level.dat").isFile() || new File(dir, "world").isDirectory())
                    log.warning("[maps] " + dir.getName() + " has no map.yml - skipped (see maps/FORMAT.txt)");
                continue;
            }
            try {
                StoredMapTemplate t = MapFiles.read(this, dir);
                if (t.generated && (t.generatorVersion < DefaultMaps.VERSION || regenerate) && DefaultMaps.byName(t.name) != null) {
                    log.warning("[maps] retaining outdated generated map without ownership proof: " + dir);
                }
                if (!new File(t.worldFolder(), "level.dat").isFile()) {
                    log.warning("[maps] " + dir.getName() + ": missing world/level.dat - skipped");
                    continue;
                }
                register(t);
            } catch (Throwable e) {
                log.warning("[maps] could not load map " + dir.getName() + ": " + e.getMessage());
            }
        }
    }

    private void register(StoredMapTemplate t) {
        String key = t.name.toLowerCase(Locale.ROOT);
        if (templates.containsKey(key)) {
            log.warning("[maps] duplicate map name " + t.name + " in " + t.folder + " - skipped");
            return;
        }
        templates.put(key, t);
    }

    private void generateMissing() {
        boolean always = config().getBoolean("always-generate-defaults", false);
        for (DefaultMaps.Def d : DefaultMaps.all()) {
            if (templates.containsKey(d.name.toLowerCase(Locale.ROOT))) continue;
            if (!always && hasGroup(d.family, d.teams, d.name)) continue;
            try {
                StoredMapTemplate t = bake(d);
                if (t != null) register(t);
            } catch (Throwable e) {
                log.log(Level.SEVERE, "[maps] failed to generate " + d.name, e);
            }
        }
    }

    /** True if a non-default map (or an already present default) serves this family/team-count group. */
    private boolean hasGroup(GameFamily family, int teams, String excluding) {
        for (StoredMapTemplate t : templates.values()) {
            if (t.family != family || !t.teamCounts.contains(teams)) continue;
            if (t.generated && DefaultMaps.byName(t.name) != null) continue; // defaults never block other defaults
            if (!t.name.equalsIgnoreCase(excluding)) return true;
        }
        return false;
    }

    /** Generate one default map: paint, write into a scratch world, save, move into maps/<folder>/world. */
    private StoredMapTemplate bake(DefaultMaps.Def d) throws IOException {
        long t0 = System.currentTimeMillis();
        log.info("[maps] generating " + d.family + " map '" + d.name + "' (" + d.teams + " teams, " + d.theme + ")...");
        GeneratedMap gm = d.generate();
        File target = new File(mapsDir, d.folder());
        String scratch = Worlds.GEN_PREFIX + d.folder();
        File scratchDir = new File(Worlds.container(), scratch);
        if (Bukkit.getWorld(scratch) != null || java.nio.file.Files.exists(scratchDir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("generation world already exists; preserving " + scratchDir);
        java.nio.file.Files.createDirectory(target.toPath()); // never reuse or replace a pre-existing target
        writeWorld(gm, scratch);
        Worlds.copyWorld(scratchDir, new File(target, "world"));
        log.warning("[maps] retaining generated scratch world " + scratchDir + "; monitor disk use");
        MapFiles.write(gm, target, DefaultMaps.VERSION, d.seed());
        log.info("[maps] generated '" + d.name + "' (" + gm.canvas.size() + " blocks) in " + (System.currentTimeMillis() - t0) + "ms");
        return MapFiles.read(this, target);
    }

    /** Create a void world, flush the canvas into it, save fully and unload. */
    private void writeWorld(GeneratedMap gm, String worldName) {
        World w = Worlds.loadVoid(worldName, gm.biome, gm.spawn[0], gm.spawn[1], gm.spawn[2]);
        if (w == null) throw new IllegalStateException("could not create scratch world " + worldName);
        try {
            Worlds.applyRules(w, true);
            w.setAutoSave(true);
            gm.canvas.flush(w, log);
            w.setSpawnLocation((int) Math.floor(gm.spawn[0]), (int) Math.floor(gm.spawn[1]), (int) Math.floor(gm.spawn[2]));
            Worlds.saveFully(w, log);
        } finally {
            if (!Bukkit.unloadWorld(w, false)) log.warning("[maps] could not unload scratch world " + worldName);
        }
    }

    // ============================================================================================ lobbies
    private void loadLobbies() {
        ConfigurationSection cfg = config();
        mainLobby = lobby(cfg.getString("main-lobby-world", ""), LobbyGenerator.MAIN, 0);
        bedwarsLobby = lobby(cfg.getString("bedwars-lobby-world", ""), LobbyGenerator.BEDWARS, 1);
        skywarsLobby = lobby(cfg.getString("skywars-lobby-world", ""), LobbyGenerator.SKYWARS, 2);
        duelsLobby = lobby("", LobbyGenerator.DUELS, 3);
    }

    private World lobby(String override, String name, int kind) {
        try {
            if (override != null && !override.isEmpty()) {
                World w = Bukkit.getWorld(override);
                if (w == null && new File(Worlds.container(), override + "/level.dat").isFile()) w = Bukkit.createWorld(new org.bukkit.WorldCreator(override));
                if (w != null) { Worlds.applyRules(w, false); return w; }
                log.warning("[maps] configured lobby world '" + override + "' not found, using generated " + name);
            }
            World loaded = Bukkit.getWorld(name);
            File dir = new File(Worlds.container(), name);
            File marker = new File(dir, "h2020-lobby.yml");
            if (kind == 3 && java.nio.file.Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (java.nio.file.Files.isSymbolicLink(dir.toPath()) ||
                        !java.nio.file.Files.isDirectory(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                        !java.nio.file.Files.isRegularFile(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                        !java.nio.file.Files.isRegularFile(new File(dir, "level.dat").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                        !"h2020-duels-lobby-v1".equals(YamlConfiguration.loadConfiguration(marker).getString("owner")) ||
                        YamlConfiguration.loadConfiguration(marker).getInt("version", 0) != LOBBY_VERSION)
                    throw new IOException("Duels lobby is not owned; preserving " + dir);
            }
            if (loaded != null) return loaded;
            boolean fresh = !marker.isFile() || YamlConfiguration.loadConfiguration(marker).getInt("version", 0) < LOBBY_VERSION;
            GeneratedMap gm = null;
            if (fresh) {
                log.info("[maps] generating lobby world " + name + "...");
                if (java.nio.file.Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("lobby path exists without a current marker; preserving " + dir);
                gm = kind == 0 ? LobbyGenerator.main(31L) : kind == 1 ? LobbyGenerator.bedwars(32L) : LobbyGenerator.skywars(33L);
            }
            org.bukkit.block.Biome biome = gm != null ? gm.biome : org.bukkit.block.Biome.PLAINS;
            if (gm == null && marker.isFile()) {
                try { biome = org.bukkit.block.Biome.valueOf(YamlConfiguration.loadConfiguration(marker).getString("biome", "PLAINS")); }
                catch (IllegalArgumentException ignored) {}
            }
            World w = Worlds.loadVoid(name, biome, 0.5, LobbyGenerator.PY + 1, 0.5);
            if (w == null) throw new IllegalStateException("could not load " + name);
            if (gm != null) {
                w.setAutoSave(true);
                gm.canvas.flush(w, log);
                w.setSpawnLocation(0, LobbyGenerator.PY + 1, 0);
                Worlds.saveFully(w, log);
                YamlConfiguration y = new YamlConfiguration();
                y.set("version", LOBBY_VERSION);
                if (kind == 3) y.set("owner", "h2020-duels-lobby-v1");
                y.set("biome", gm.biome.name());
                y.set("generated", System.currentTimeMillis());
                y.save(marker);
            }
            Worlds.applyRules(w, false);
            w.setSpawnLocation(0, LobbyGenerator.PY + 1, 0);
            for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) w.loadChunk(cx, cz, true);
            return w;
        } catch (Throwable e) {
            log.log(Level.SEVERE, "[maps] lobby " + name + " unavailable", e);
            return null;
        }
    }

    /** Merge defaults without ever truncating an existing configuration. */
    private void writeLobbyNpcs() {
        Path path = new File(plugin.getDataFolder(), "lobby-npcs.yml").toPath();
        Path temp = null;
        try {
            boolean exists = true;
            try {
                BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!a.isRegularFile()) throw new IOException("not a regular file (symlinks refused)");
            } catch (NoSuchFileException e) { exists = false; }
            YamlConfiguration y = new YamlConfiguration();
            if (exists) {
                try (java.io.Reader r = new java.io.InputStreamReader(
                        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                        StandardCharsets.UTF_8.newDecoder())) {
                    y.load(r); // strict: unlike loadConfiguration, parsing errors are propagated
                }
            }
            String before = y.saveToString();
            for (String parent : new String[]{"extra", "bedwars_lobby", "skywars_lobby", "duels_lobby"})
                if (y.contains(parent) && !y.isConfigurationSection(parent))
                    throw new IOException("configuration collision at " + parent + "; preserving original");
            if (!y.contains("version") || y.getInt("version") < LOBBY_VERSION) y.set("version", LOBBY_VERSION);
            if (!y.contains("world")) y.set("world", LobbyGenerator.MAIN);
            if (LobbyGenerator.MAIN.equals(y.getString("world"))) {
                if (!y.contains("spawn")) y.set("spawn", pos(new double[]{0.5, LobbyGenerator.PY + 1, 0.5, 180}));
                if (!y.contains("duels") && validLegacyPad(y.getConfigurationSection("extra.duels")))
                    y.set("duels", y.getConfigurationSection("extra.duels").getValues(true));
                for (Map.Entry<String, double[]> e : LobbyGenerator.npcPads(LobbyGenerator.MAIN).entrySet()) {
                    String name = e.getKey();
                    String key = name.equals("bedwars") || name.equals("skywars") || name.equals("duels") || name.equals("pit") ? name : "extra." + name;
                    if (!y.contains(key)) y.set(key, pos(e.getValue()));
                }
            }
            section(y, "bedwars_lobby", LobbyGenerator.BEDWARS);
            section(y, "skywars_lobby", LobbyGenerator.SKYWARS);
            section(y, "duels_lobby", LobbyGenerator.DUELS);
            String after = y.saveToString();
            if (exists && before.equals(after)) return;
            y.options().header("NPC pads of the generated hubs; configured positions are preserved.");
            byte[] bytes = y.saveToString().getBytes(StandardCharsets.UTF_8);
            temp = Files.createTempFile(path.getParent(), ".lobby-npcs-", ".tmp");
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                if (exists) {
                    // Fail closed if the replacement cannot retain the original inode's access metadata.
                    java.nio.file.attribute.PosixFileAttributes original = Files.readAttributes(
                            path, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
                    java.nio.file.attribute.PosixFileAttributeView view = Files.getFileAttributeView(
                            temp, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    if (view == null) throw new IOException("POSIX metadata unavailable");
                    view.setOwner(original.owner());
                    view.setGroup(original.group());
                    Files.setAttribute(temp, "unix:mode", mode, LinkOption.NOFOLLOW_LINKS);
                    java.nio.file.attribute.PosixFileAttributes copy = view.readAttributes();
                    int copiedMode = ((Number) Files.getAttribute(temp, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
                    if (!original.owner().equals(copy.owner()) || !original.group().equals(copy.group()) || mode != copiedMode)
                        throw new IOException("NPC configuration metadata could not be preserved");
                }
                out.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            log.log(Level.WARNING, "[maps] lobby NPC configuration not saved; inspect " + path, e);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); }
            catch (IOException e) { log.log(Level.WARNING, "[maps] could not remove temporary NPC config " + temp, e); }
        }
    }

    private static boolean validLegacyPad(ConfigurationSection p) {
        if (p == null) return false;
        for (String key : new String[]{"x", "y", "z", "yaw"}) {
            Object v = p.get(key);
            if (v == null && key.equals("yaw")) continue;
            if (!(v instanceof Number) || !Double.isFinite(((Number) v).doubleValue())) return false;
        }
        return true;
    }

    private static void section(YamlConfiguration y, String key, String world) {
        if (!y.contains(key + ".world")) y.set(key + ".world", world);
        if (!world.equals(y.getString(key + ".world"))) return;
        if (!y.contains(key + ".spawn")) y.set(key + ".spawn", pos(new double[]{0.5, LobbyGenerator.PY + 1, 0.5, 180}));
        for (Map.Entry<String, double[]> e : LobbyGenerator.npcPads(world).entrySet())
            if (!y.contains(key + "." + e.getKey())) y.set(key + "." + e.getKey(), pos(e.getValue()));
    }

    private static Map<String, Object> pos(double[] p) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", p[0]); m.put("y", p[1]); m.put("z", p[2]); m.put("yaw", (double) (float) p[3]);
        return m;
    }

    private void writeFormatHelp() {
        File f = new File(mapsDir, "FORMAT.txt");
        if (f.isFile()) return;
        String text = "Hypixel2020 maps\r\n"
                + "================\r\n"
                + "Each map is a folder here: <folder>/world (a normal Minecraft 1.8 world folder) + <folder>/map.yml.\r\n"
                + "Generated maps are rebuilt when deleted. To import a community map, copy its world folder into\r\n"
                + "<folder>/world and write map.yml like the generated ones:\r\n\r\n"
                + "name: My Map\r\nfamily: BEDWARS            # or SKYWARS\r\nteams: [8]                 # bedwars 8 or 4, skywars 12 (solo) or 8 (teams)\r\n"
                + "build-limit: 91\r\nbiome: PLAINS\r\nbounds: {min: {x: -120, y: 0, z: -120}, max: {x: 120, y: 91, z: 120}}\r\n"
                + "points:\r\n  waiting.lobby: [{x: 0.5, y: 106, z: 0.5}]\r\n  spectator: [{x: 0.5, y: 95, z: 0.5}]\r\n  mid: [{x: 0.5, y: 69, z: 5.5}]\r\n"
                + "  team.red.spawn: [{x: -27.5, y: 66, z: -80.5, yaw: 0}]\r\n  team.red.bed: [\"-28,66,-74\", \"-28,66,-73\"]   # foot then head\r\n"
                + "  team.red.generator: [{x: -27.5, y: 66, z: -85.5}]\r\n  team.red.shop: [{x: -33.5, y: 66, z: -80.5, yaw: 90}]\r\n"
                + "  team.red.upgrades: [{x: -21.5, y: 66, z: -80.5, yaw: 270}]\r\n  team.red.base: [\"-41,53,-91\", \"-15,79,-65\"]   # min, max\r\n"
                + "  gen.diamond: [...]\r\n  gen.emerald: [...]\r\n\r\n"
                + "SkyWars keys: island.<i>.cage (feet position inside the cage), island.<i>.chests (block positions),\r\n"
                + "island.<i>.bounds (min,max), mid.chests. Team colours: red blue green yellow aqua white pink gray.\r\n"
                + "Points may also be written as nested YAML (team: {red: {spawn: [...]}}) or as \"x,y,z[,yaw]\" strings.\r\n";
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(text);
        } catch (IOException ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (IOException ignored) {}
        }
    }

    // ============================================================================================ MapService
    @Override
    public List<MapTemplate> templates(GameFamily family) {
        List<MapTemplate> l = new ArrayList<MapTemplate>();
        for (StoredMapTemplate t : templates.values()) if (family == null || t.family == family) l.add(t);
        return Collections.unmodifiableList(l);
    }

    @Override
    public List<MapTemplate> templatesFor(GameType type) {
        List<MapTemplate> l = new ArrayList<MapTemplate>();
        if (type == null) return l;
        for (StoredMapTemplate t : templates.values())
            if (t.family == type.family && t.teamCounts.contains(type.teams)) l.add(t);
        return Collections.unmodifiableList(l);
    }

    @Override
    public MapTemplate byName(String name) {
        if (name == null) return null;
        StoredMapTemplate t = templates.get(name.toLowerCase(Locale.ROOT));
        if (t != null) return t;
        for (StoredMapTemplate s : templates.values())
            if (s.folder.getName().equalsIgnoreCase(name) || s.name.replace(" ", "").equalsIgnoreCase(name.replace(" ", "").replace("_", "")))
                return s;
        return null;
    }

    @Override
    public World mainLobby() {
        if (mainLobby != null && Bukkit.getWorld(mainLobby.getUID()) == null) mainLobby = null;
        return mainLobby;
    }

    @Override
    public World familyLobby(GameFamily family) {
        if (family == null) return mainLobby();
        World w = family == GameFamily.BEDWARS ? bedwarsLobby : family == GameFamily.DUELS ? duelsLobby : skywarsLobby;
        if (w != null && Bukkit.getWorld(w.getUID()) == null) w = null;
        return w != null ? w : mainLobby();
    }

    @Override
    public MapTemplate templateOf(World w) {
        return w == null ? null : instances.get(w.getName().toLowerCase());
    }
}

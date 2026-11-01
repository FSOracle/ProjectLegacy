package net.h2020.maps;

import net.h2020.api.game.GameFamily;
import net.h2020.api.map.MapKeys;
import net.h2020.api.map.MapTemplate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * A map template on disk: plugins/Hypixel2020/maps/&lt;folder&gt;/world + map.yml. Instances are copies of the
 * world folder under &lt;worldContainer&gt;/instances/&lt;name&gt;; the Bukkit world name of an instance is therefore
 * "instances/&lt;name&gt;".
 */
public final class StoredMapTemplate implements MapTemplate {
    final MapServiceImpl service;
    final File folder;
    final String name;
    final GameFamily family;
    final List<Integer> teamCounts;
    final int buildLimitY;
    final Vector boundsMin, boundsMax;
    final Map<String, List<Vector>> points;
    final Map<String, List<Float>> yaws;
    final Biome biome;
    final boolean generated;
    final int generatorVersion;
    final String theme;

    StoredMapTemplate(MapServiceImpl service, File folder, String name, GameFamily family, List<Integer> teamCounts,
                      int buildLimitY, Vector boundsMin, Vector boundsMax, Map<String, List<Vector>> points,
                      Map<String, List<Float>> yaws, Biome biome, boolean generated, int generatorVersion, String theme) {
        this.service = service;
        this.folder = folder;
        this.name = name;
        this.family = family;
        this.teamCounts = Collections.unmodifiableList(new ArrayList<Integer>(teamCounts));
        this.buildLimitY = buildLimitY;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        Map<String, List<Vector>> p = new LinkedHashMap<String, List<Vector>>();
        for (Map.Entry<String, List<Vector>> e : points.entrySet())
            p.put(e.getKey(), Collections.unmodifiableList(new ArrayList<Vector>(e.getValue())));
        this.points = Collections.unmodifiableMap(p);
        this.yaws = yaws;
        this.biome = biome;
        this.generated = generated;
        this.generatorVersion = generatorVersion;
        this.theme = theme;
    }

    @Override public String name() { return name; }
    @Override public GameFamily family() { return family; }
    @Override public List<Integer> supportedTeamCounts() { return teamCounts; }

    @Override
    public List<Vector> points(String key) {
        List<Vector> l = points.get(key);
        if (l == null) return Collections.emptyList();
        List<Vector> copy = new ArrayList<Vector>(l.size());
        for (Vector v : l) copy.add(v.clone());
        return copy;
    }

    @Override
    public Vector point(String key) {
        List<Vector> l = points.get(key);
        return l == null || l.isEmpty() ? null : l.get(0).clone();
    }

    @Override
    public float yaw(String key, int index) {
        List<Float> l = yaws.get(key);
        if (l == null || index < 0 || index >= l.size()) return 0f;
        return l.get(index);
    }

    @Override
    public Map<String, List<Vector>> allPoints() {
        Map<String, List<Vector>> m = new LinkedHashMap<String, List<Vector>>();
        for (String k : points.keySet()) m.put(k, points(k));
        return m;
    }

    @Override public int buildLimitY() { return buildLimitY; }
    @Override public Vector boundsMin() { return boundsMin.clone(); }
    @Override public Vector boundsMax() { return boundsMax.clone(); }

    public File worldFolder() { return new File(folder, "world"); }
    public File folder() { return folder; }
    public Biome biome() { return biome; }
    public boolean generated() { return generated; }
    public String theme() { return theme; }

    /** Number of SkyWars islands (cage points), 0 for bed wars maps. */
    public int islandCount() {
        int n = 0;
        while (points.containsKey(MapKeys.islandCage(n))) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------ instances
    @Override
    public World createInstance(String instanceWorldName) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("createInstance must run on the main thread");
        File src = worldFolder();
        if (!new File(src, "level.dat").isFile())
            throw new IllegalStateException("Map " + name + " has no world (missing " + src + "/level.dat)");
        String worldName = Worlds.INSTANCE_DIR + "/" + instanceWorldName;
        File dst;
        try {
            dst = Worlds.reserveInstance(instanceWorldName);
            Worlds.copyWorld(src, dst);
        } catch (IOException e) {
            throw new IllegalStateException("Could not reserve/copy map " + name + "; partial data retained", e);
        }
        Vector spawn = point(MapKeys.WAITING_LOBBY);
        if (spawn == null) spawn = point(MapKeys.MID);
        if (spawn == null) spawn = new Vector(0.5, 100, 0.5);
        World w = Worlds.loadVoid(worldName, biome, spawn.getX(), spawn.getY(), spawn.getZ());
        if (w == null) throw new IllegalStateException("Bukkit refused to load " + worldName);
        Worlds.applyRules(w, true);
        w.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
        preload(w);
        service.instances.put(w.getName().toLowerCase(), this);
        service.ownedWorlds.put(w.getName().toLowerCase(), w);
        return w;
    }

    /** Load the chunks players are teleported into (waiting lobby, spawns, cages, mid) so nobody falls in. */
    private void preload(World w) {
        Set<Long> done = new HashSet<Long>();
        for (Map.Entry<String, List<Vector>> e : points.entrySet()) {
            String k = e.getKey();
            boolean wanted = k.equals(MapKeys.WAITING_LOBBY) || k.equals(MapKeys.MID) || k.equals(MapKeys.SPECTATOR)
                    || k.endsWith(".spawn") || k.endsWith(".cage");
            if (!wanted) continue;
            for (Vector v : e.getValue()) {
                int cx = v.getBlockX() >> 4, cz = v.getBlockZ() >> 4;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        long key = ((long) (cx + dx) << 32) | ((cz + dz) & 0xFFFFFFFFL);
                        if (done.add(key)) w.loadChunk(cx + dx, cz + dz, true);
                    }
            }
        }
    }

    @Override
    public void destroyInstance(World world) {
        if (world == null) return;
        if (!Bukkit.isPrimaryThread()) {
            final World w = world;
            Bukkit.getScheduler().runTask(service.plugin, new Runnable() { @Override public void run() { destroyInstance(w); } });
            return;
        }
        destroyLater(world, 0);
    }

    private void destroyLater(final World world, final int attempt) {
        final String wname = world.getName();
        if (service.instances.get(wname.toLowerCase()) != this || service.ownedWorlds.get(wname.toLowerCase()) != world || Bukkit.getWorld(wname) != world) {
            service.plugin.getLogger().warning("[maps] refusing to unload unowned instance " + wname);
            return;
        }
        evacuate(world);
        boolean ok = Bukkit.unloadWorld(world, false);
        if (!ok) {
            if (attempt >= 10) {
                service.plugin.getLogger().warning("[maps] could not unload instance " + wname + " after 10 attempts");
                return;
            }
            Bukkit.getScheduler().runTaskLater(service.plugin, new Runnable() {
                @Override public void run() {
                    World w = Bukkit.getWorld(wname);
                    if (w == world) destroyLater(w, attempt + 1);
                    else service.plugin.getLogger().warning("[maps] instance changed or unloaded; retaining " + wname);
                }
            }, 20L);
            return;
        }
        service.instances.remove(wname.toLowerCase());
        service.ownedWorlds.remove(wname.toLowerCase());
        service.plugin.getLogger().warning("[maps] retained instance " + wname + "; monitor disk use");
    }

    /** Only unload an instance owned by this template; retain its files. */
    void destroyNow(World world) {
        String wname = world.getName();
        if (service.instances.get(wname.toLowerCase()) != this || service.ownedWorlds.get(wname.toLowerCase()) != world || Bukkit.getWorld(wname) != world) {
            service.plugin.getLogger().warning("[maps] refusing to unload unowned instance " + wname);
            return;
        }
        evacuate(world);
        if (Bukkit.unloadWorld(world, false)) {
            service.instances.remove(wname.toLowerCase());
        service.ownedWorlds.remove(wname.toLowerCase());
            service.plugin.getLogger().warning("[maps] retained instance " + wname + "; monitor disk use");
        }
    }

    private void evacuate(World world) {
        if (world.getPlayers().isEmpty()) return;
        World lobby = service.mainLobby();
        if (lobby == null || lobby.equals(world)) lobby = Bukkit.getWorlds().get(0);
        Location to = lobby.getSpawnLocation().add(0.5, 0, 0.5);
        for (Player p : new ArrayList<Player>(world.getPlayers())) p.teleport(to);
    }

    @Override public String toString() { return "MapTemplate[" + name + " " + family + " " + teamCounts + "]"; }
}

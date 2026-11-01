package net.h2020.duels;

import net.h2020.api.game.GameType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

/**
 * Code-generated themed arenas, one per mode, built into a fresh void world per match (the world is deleted when
 * the match ends, so every game has its own isolated copy).
 */
final class DuelArena {
    static final int FLOOR = 64;

    static final class Box {
        final int x1, y1, z1, x2, y2, z2;
        Box(int x1, int y1, int z1, int x2, int y2, int z2) {
            this.x1 = Math.min(x1, x2); this.y1 = Math.min(y1, y2); this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2); this.y2 = Math.max(y1, y2); this.z2 = Math.max(z1, z2);
        }
        boolean contains(Location l) {
            return l.getX() >= x1 && l.getX() < x2 + 1 && l.getY() >= y1 && l.getY() < y2 + 1 && l.getZ() >= z1 && l.getZ() < z2 + 1;
        }
        boolean contains(Block b) {
            return b.getX() >= x1 && b.getX() <= x2 && b.getY() >= y1 && b.getY() <= y2 && b.getZ() >= z1 && b.getZ() <= z2;
        }
        Location center(World w) { return new Location(w, (x1 + x2 + 1) / 2.0, y1, (z1 + z2 + 1) / 2.0); }
    }

    final String name;
    private final Location[] spawns = new Location[2];
    final double voidY;
    /** Bridge only: goal[i] is side i's own portal (the other side scores by entering it). */
    final Box[] goals = new Box[2];
    /** Bridge only: where blocks may be placed. */
    Box build;

    private DuelArena(String name, double voidY) { this.name = name; this.voidY = voidY; }

    Location spawn(int side) { return spawns[side == 1 ? 1 : 0].clone(); }

    static DuelArena build(World w, GameType type) {
        switch (type) {
            case DUELS_BOXING: return boxing(w);
            case DUELS_NODEBUFF: return nodebuff(w);
            case DUELS_BRIDGE: return bridge(w);
            default: return classic(w);
        }
    }

    @SuppressWarnings("deprecation")
    private static void set(World w, int x, int y, int z, Material m, int data) {
        Block b = w.getBlockAt(x, y, z);
        b.setTypeIdAndData(m.getId(), (byte) data, false);
    }

    private static void set(World w, int x, int y, int z, Material m) { set(w, x, y, z, m, 0); }

    private static void load(World w, int r) {
        for (int cx = -r; cx <= r; cx++) for (int cz = -r; cz <= r; cz++) w.loadChunk(cx, cz, true);
    }

    /** Ruined courtyard: grass and cobble floor, mossy stone-brick walls, log pillars for cover. */
    private static DuelArena classic(World w) {
        load(w, 2);
        Random r = new Random(7);
        int R = 16;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            set(w, x, FLOOR - 1, z, Material.DIRT);
            double n = r.nextDouble();
            set(w, x, FLOOR, z, n < 0.12 ? Material.COBBLESTONE : n < 0.18 ? Material.GRAVEL : Material.GRASS);
            if (Math.abs(x) == R || Math.abs(z) == R)
                for (int y = FLOOR + 1; y <= FLOOR + 7; y++) {
                    double m = r.nextDouble();
                    set(w, x, y, z, Material.SMOOTH_BRICK, m < 0.25 ? 1 : m < 0.4 ? 2 : 0);
                }
        }
        int[][] pillars = {{-5, -8}, {5, -8}, {-5, 8}, {5, 8}, {0, -12}, {0, 12}};
        for (int[] p : pillars) for (int y = FLOOR + 1; y <= FLOOR + 4; y++) set(w, p[0], y, p[1], Material.LOG);
        for (int[] p : pillars) set(w, p[0], FLOOR + 5, p[1], Material.LEAVES);
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++)
            if (Math.abs(x) == R || Math.abs(z) == R) set(w, x, FLOOR + 8, z, Material.BARRIER);
        DuelArena a = new DuelArena("Ruins", FLOOR - 10);
        a.spawns[0] = new Location(w, -10.5, FLOOR + 1, 0.5, -90, 0);
        a.spawns[1] = new Location(w, 11.5, FLOOR + 1, 0.5, 90, 0);
        return a;
    }

    /** Boxing ring: checkered canvas, red and blue corners, fence ropes and a glass cage. */
    private static DuelArena boxing(World w) {
        load(w, 1);
        int R = 10;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            set(w, x, FLOOR - 1, z, Material.QUARTZ_BLOCK);
            boolean edge = Math.abs(x) == R || Math.abs(z) == R;
            set(w, x, FLOOR, z, Material.WOOL, edge ? 15 : ((x + z) & 1) == 0 ? 0 : 8);
            if (edge) {
                set(w, x, FLOOR + 1, z, Material.FENCE);
                set(w, x, FLOOR + 2, z, Material.FENCE);
                for (int y = FLOOR + 3; y <= FLOOR + 6; y++) set(w, x, y, z, Material.GLASS);
            }
        }
        for (int s = -1; s <= 1; s += 2) for (int t = -1; t <= 1; t += 2) {
            int x = s * (R - 1), z = t * (R - 1);
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) set(w, x, y, z, Material.WOOL, s < 0 ? 14 : 11);
            set(w, x, FLOOR + 4, z, Material.GLOWSTONE);
        }
        DuelArena a = new DuelArena("Ring", FLOOR - 10);
        a.spawns[0] = new Location(w, -4.5, FLOOR + 1, 0.5, -90, 0);
        a.spawns[1] = new Location(w, 5.5, FLOOR + 1, 0.5, 90, 0);
        return a;
    }

    /** Nether keep: nether-brick court, netherrack walls with fence battlements, glowstone braziers. */
    private static DuelArena nodebuff(World w) {
        load(w, 2);
        Random r = new Random(11);
        int R = 18;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            set(w, x, FLOOR - 1, z, Material.NETHERRACK);
            set(w, x, FLOOR, z, (Math.abs(x) % 6 == 0 || Math.abs(z) % 6 == 0) ? Material.NETHER_BRICK : (r.nextDouble() < 0.1 ? Material.SOUL_SAND : Material.NETHERRACK));
            if (Math.abs(x) == R || Math.abs(z) == R) {
                for (int y = FLOOR + 1; y <= FLOOR + 6; y++) set(w, x, y, z, y % 3 == 0 ? Material.NETHER_BRICK : Material.NETHERRACK);
                set(w, x, FLOOR + 7, z, Material.NETHER_FENCE);
                set(w, x, FLOOR + 8, z, Material.BARRIER);
            }
        }
        // soul sand slows players: keep the fighting lanes clean
        for (int x = -R + 1; x < R; x++) for (int z = -2; z <= 2; z++) set(w, x, FLOOR, z, Material.NETHER_BRICK);
        int[][] braziers = {{-12, -12}, {12, -12}, {-12, 12}, {12, 12}};
        for (int[] b : braziers) {
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) set(w, b[0], y, b[1], Material.NETHER_BRICK);
            set(w, b[0], FLOOR + 4, b[1], Material.GLOWSTONE);
        }
        DuelArena a = new DuelArena("Nether Keep", FLOOR - 10);
        a.spawns[0] = new Location(w, -12.5, FLOOR + 1, 0.5, -90, 0);
        a.spawns[1] = new Location(w, 13.5, FLOOR + 1, 0.5, 90, 0);
        return a;
    }

    /**
     * Bridge "Urban": red island (x &lt; 0) and blue island (x &gt; 0) joined by a 5-wide bridge with a mid platform.
     * Each island has its goal portal (3x3 pit lined with team glass and an end-portal-coloured floor) behind its
     * spawn. Below the islands is the void.
     */
    private static DuelArena bridge(World w) {
        load(w, 3);
        DuelArena a = new DuelArena("Urban", FLOOR - 14);
        for (int side = 0; side < 2; side++) {
            int dir = side == 0 ? -1 : 1, color = side == 0 ? 14 : 11;
            for (int ax = 18; ax <= 31; ax++) for (int z = -7; z <= 7; z++) {
                int x = dir * ax;
                set(w, x, FLOOR - 2, z, Material.STAINED_CLAY, 15);
                set(w, x, FLOOR - 1, z, Material.STAINED_CLAY, color);
                set(w, x, FLOOR, z, (Math.abs(z) == 7 || ax == 18 || ax == 31) ? Material.STAINED_CLAY : Material.STAINED_CLAY, (Math.abs(z) == 7 || ax == 31) ? 0 : color);
            }
            // goal pit at |x| 26..28, z -1..1, 3 deep
            for (int ax = 25; ax <= 29; ax++) for (int z = -2; z <= 2; z++) {
                int x = dir * ax;
                boolean inner = ax >= 26 && ax <= 28 && Math.abs(z) <= 1;
                for (int y = FLOOR - 4; y <= FLOOR; y++) {
                    if (inner) set(w, x, y, z, y == FLOOR - 4 ? Material.OBSIDIAN : Material.AIR);
                    else set(w, x, y, z, Material.STAINED_GLASS, color);
                }
            }
            a.goals[side] = new Box(dir * 26, FLOOR - 3, -1, dir * 28, FLOOR, 1);
            // spawn hut posts
            for (int z = -3; z <= 3; z += 6) for (int y = FLOOR + 1; y <= FLOOR + 3; y++) set(w, dir * 20, y, z, Material.STAINED_CLAY, color);
            a.spawns[side] = new Location(w, dir * 21 + 0.5, FLOOR + 1, 0.5, side == 0 ? -90 : 90, 0);
        }
        // bridge deck
        for (int x = -17; x <= 17; x++) for (int z = -2; z <= 2; z++) {
            set(w, x, FLOOR, z, Material.STAINED_CLAY, Math.abs(z) == 2 ? 8 : 0);
        }
        for (int x = -4; x <= 4; x++) for (int z = -5; z <= 5; z++) set(w, x, FLOOR, z, Material.STAINED_CLAY, Math.abs(x) == 4 || Math.abs(z) == 5 ? 7 : 0);
        set(w, 0, FLOOR + 1, 0, Material.AIR);
        a.build = new Box(-32, FLOOR - 6, -14, 32, FLOOR + 8, 14);
        return a;
    }

    Location goalCenter(int side) { return goals[side].center(spawn(0).getWorld()).add(0, 4, 0); }
}

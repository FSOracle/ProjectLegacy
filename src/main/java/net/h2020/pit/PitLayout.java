package net.h2020.pit;

/**
 * Deterministic Hypixel-Pit-style map: a floating spawn platform (safe zone, no PvP) with a centre drop hole above a
 * walled circular arena. Pure function of coordinates, so the world is regenerated identically on every start.
 * {@link #block} returns legacy 1.8 block ids packed as (id << 4) | data; 0 is air.
 */
public final class PitLayout {
    private PitLayout() {}

    public static final int FLOOR_Y = 64;
    public static final int ARENA_R = 80;
    public static final int WALL_TOP = FLOOR_Y + 20;
    public static final int PLATFORM_Y = 84;
    public static final int PLATFORM_HALF = 11;
    public static final int HOLE_HALF = 2;
    public static final int MIN = -(ARENA_R + 3), MAX = ARENA_R + 3;
    public static final int Y_MIN = FLOOR_Y - 4, Y_MAX = PLATFORM_Y + 3;
    public static final int VOID_Y = 40;
    public static final double SPAWN_X = 0.5, SPAWN_Y = PLATFORM_Y + 1, SPAWN_Z = 7.5;
    public static final float SPAWN_YAW = 180f;

    private static final int AIR = 0, STONE = 1, GRASS = 2, DIRT = 3, LOG = 17, LEAVES = 18, MOSSY = 48, OBSIDIAN = 49,
            FENCE = 85, GLOWSTONE = 89, STONE_BRICK = 98, QUARTZ = 155, CLAY = 159;

    private static int b(int id) { return id << 4; }
    private static int b(int id, int data) { return (id << 4) | (data & 15); }

    /** True inside the spawn platform's no-PvP zone. */
    public static boolean isSafe(double x, double y, double z) {
        return y >= PLATFORM_Y - 1 && Math.abs(x) <= PLATFORM_HALF + 1.5 && Math.abs(z) <= PLATFORM_HALF + 1.5;
    }

    public static int block(int x, int y, int z) {
        if (x < MIN || x > MAX || z < MIN || z > MAX || y < Y_MIN || y > Y_MAX) return AIR;
        int cheb = Math.max(Math.abs(x), Math.abs(z));
        // ---- spawn platform ----
        if (cheb <= PLATFORM_HALF) {
            if (y == PLATFORM_Y) {
                if (cheb <= HOLE_HALF) return AIR;
                if (cheb == HOLE_HALF + 1 || cheb == PLATFORM_HALF) return b(CLAY, 4); // yellow trim
                return b(QUARTZ);
            }
            if (y == PLATFORM_Y + 1 && cheb == PLATFORM_HALF) return b(FENCE);
            if (y == PLATFORM_Y - 1 && cheb > HOLE_HALF && (x % 4 == 0) && (z % 4 == 0)) return b(GLOWSTONE);
            if (Math.abs(x) == 9 && Math.abs(z) == 9 && y > FLOOR_Y && y < PLATFORM_Y) return b(QUARTZ, 2); // pillars
        }
        double r = Math.sqrt((double) x * x + (double) z * z);
        if (r > ARENA_R + 2.5) return AIR;
        // ---- arena ground ----
        if (y == FLOOR_Y) {
            if (r > ARENA_R) return b(STONE_BRICK);
            if (r <= 4) return b(OBSIDIAN);
            if (r >= 14 && r <= 16) return b(STONE_BRICK);
            return b(GRASS);
        }
        if (y < FLOOR_Y) return y == Y_MIN ? b(STONE) : b(DIRT);
        // ---- outer wall ----
        if (r > ARENA_R) {
            if (y <= WALL_TOP) return (y == FLOOR_Y + 4 && Math.floorMod(x + z, 7) == 0) ? b(GLOWSTONE) : b(STONE_BRICK);
            return AIR;
        }
        // ---- rocks on the diagonals ----
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
            int dx = x - sx * 24, dz = z - sz * 24, dy = y - FLOOR_Y;
            if (dx * dx + dz * dz + dy * dy * 2 <= 9) return ((dx + dz + dy) & 1) == 0 ? b(STONE) : b(MOSSY);
        }
        // ---- trees on the axes ----
        int[][] trees = {{28, 0}, {-28, 0}, {0, 28}, {0, -28}};
        for (int[] t : trees) {
            int dx = x - t[0], dz = z - t[1], dy = y - (FLOOR_Y + 5);
            if (dx == 0 && dz == 0 && y > FLOOR_Y && y <= FLOOR_Y + 5) return b(LOG);
            if (dx * dx + dz * dz + dy * dy <= 5 && y > FLOOR_Y + 2) return b(LEAVES, 4);
        }
        return AIR;
    }

    /** FNV-1a over every block in bounds: proves the generator is deterministic. */
    public static long hash() {
        long h = 0xcbf29ce484222325L;
        for (int x = MIN; x <= MAX; x++) for (int z = MIN; z <= MAX; z++) for (int y = Y_MIN; y <= Y_MAX; y++) {
            h ^= block(x, y, z); h *= 0x100000001b3L;
        }
        return h;
    }
}

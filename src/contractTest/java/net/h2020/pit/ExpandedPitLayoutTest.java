package net.h2020.pit;

/** Geometry checks for a new 80-block-radius Pit; no Bukkit needed. */
public final class ExpandedPitLayoutTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    private static boolean solid(int x, int y, int z) { return (PitLayout.block(x, y, z) >> 4) != 0; }
    public static void main(String[] args) {
        check(PitLayout.ARENA_R == 80, "arena radius is 80");
        check(PitLayout.WALL_TOP == PitLayout.FLOOR_Y + 20, "wall height doubles with arena radius");
        for (int x = -79; x <= 79; x++) for (int z = -79; z <= 79; z++)
            if (x * x + z * z <= 79 * 79 && !solid(x, PitLayout.FLOOR_Y, z))
                throw new AssertionError("floor gap at " + x + "," + z);
        System.out.println("PASS solid floor over entire radius-79 disk");
        int[][] axes = {{79,0},{-79,0},{0,79},{0,-79}};
        for (int[] p : axes)
            check(!solid(p[0], PitLayout.FLOOR_Y + 1, p[1]) && !solid(p[0], PitLayout.FLOOR_Y + 2, p[1]),
                "walkable near boundary " + p[0] + "," + p[1]);
        int[][] walls = {{81,0},{-81,0},{0,81},{0,-81}};
        for (int[] p : walls)
            check(solid(p[0], PitLayout.FLOOR_Y + 20, p[1]), "high wall at " + p[0] + "," + p[1]);
    }
}

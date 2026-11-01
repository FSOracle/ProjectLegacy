package net.h2020.pit;

/** Pit level curve (prestige 0): XP cost per level grows every 10 levels; capped at 120. */
public final class PitLevels {
    private PitLevels() {}
    public static final int MAX_LEVEL = 120;
    private static final long[] COST = {15, 30, 50, 75, 125, 250, 600, 800, 900, 1000, 1200, 1500};

    /** XP needed to go from level to level + 1. */
    public static long cost(int level) { return COST[Math.max(0, Math.min(COST.length - 1, level / 10))]; }

    /** Total XP required to reach the level. */
    public static long xpForLevel(int level) {
        long sum = 0;
        for (int l = 1; l < Math.min(level, MAX_LEVEL); l++) sum += cost(l);
        return sum;
    }

    public static int levelFor(long xp) {
        int level = 1; long need = 0;
        while (level < MAX_LEVEL) {
            need += cost(level);
            if (xp < need) break;
            level++;
        }
        return level;
    }

    public static long xpToNext(long xp) {
        int level = levelFor(xp);
        return level >= MAX_LEVEL ? 0 : xpForLevel(level + 1) - xp;
    }

    public static float progress(long xp) {
        int level = levelFor(xp);
        if (level >= MAX_LEVEL) return 0f;
        return Math.max(0f, Math.min(0.999f, (xp - xpForLevel(level)) / (float) cost(level)));
    }
}

package net.h2020.pit;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Pure contract test for the Pit map, level curve and stats persistence (no Bukkit on the classpath). */
public final class PitContractTest {
    private static int passed;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL " + what);
        passed++;
        System.out.println("PASS " + what);
    }

    private static int id(int packed) { return packed >> 4; }

    public static void main(String[] args) throws Exception {
        layout();
        levels();
        store();
        System.out.println("PIT CONTRACT OK " + passed + " assertions");
    }

    static void layout() {
        int sx = (int) Math.floor(PitLayout.SPAWN_X), sy = (int) Math.floor(PitLayout.SPAWN_Y), sz = (int) Math.floor(PitLayout.SPAWN_Z);
        check(id(PitLayout.block(sx, sy - 1, sz)) != 0, "spawn stands on a solid platform block");
        check(id(PitLayout.block(sx, sy, sz)) == 0 && id(PitLayout.block(sx, sy + 1, sz)) == 0, "spawn feet and head are air");
        check(PitLayout.isSafe(PitLayout.SPAWN_X, PitLayout.SPAWN_Y, PitLayout.SPAWN_Z), "spawn is inside the no-PvP safe zone");
        check(id(PitLayout.block(0, PitLayout.PLATFORM_Y, 0)) == 0, "platform has a drop hole in the centre");
        boolean clear = true;
        for (int y = PitLayout.FLOOR_Y + 1; y <= PitLayout.PLATFORM_Y + 3; y++) if (id(PitLayout.block(0, y, 0)) != 0) clear = false;
        check(clear, "drop shaft from the hole to the arena floor is clear");
        check(id(PitLayout.block(0, PitLayout.FLOOR_Y, 0)) != 0, "arena floor below the hole is solid");
        check(!PitLayout.isSafe(0.5, PitLayout.FLOOR_Y + 1, 0.5), "arena floor is a PvP zone");
        check(!PitLayout.isSafe(0.5, PitLayout.FLOOR_Y + 1, 20.5), "arena away from centre is a PvP zone");
        check(id(PitLayout.block(PitLayout.ARENA_R + 1, PitLayout.FLOOR_Y + 5, 0)) != 0, "outer wall encloses the arena");
        check(id(PitLayout.block(0, PitLayout.FLOOR_Y + 5, PitLayout.ARENA_R + 1)) != 0, "outer wall encloses the arena (z)");
        check(id(PitLayout.block(PitLayout.PLATFORM_HALF, PitLayout.PLATFORM_Y + 1, 0)) != 0, "platform rim has a railing");
        check(PitLayout.VOID_Y < PitLayout.FLOOR_Y, "void respawn height is below the floor");
        long a = PitLayout.hash(), b = PitLayout.hash();
        check(a == b && a != 0, "layout is deterministic (hash " + Long.toHexString(a) + ")");
        int solid = 0;
        for (int x = PitLayout.MIN; x <= PitLayout.MAX; x++)
            for (int z = PitLayout.MIN; z <= PitLayout.MAX; z++)
                for (int y = PitLayout.Y_MIN; y <= PitLayout.Y_MAX; y++) if (id(PitLayout.block(x, y, z)) != 0) solid++;
        check(solid > 20000, "map has substantial geometry (" + solid + " blocks)");
        check(id(PitLayout.block(PitLayout.MAX + 1, PitLayout.FLOOR_Y, 0)) == 0, "nothing is generated outside the bounds");
    }

    static void levels() {
        check(PitLevels.levelFor(0) == 1, "new player is level 1");
        check(PitLevels.levelFor(14) == 1 && PitLevels.levelFor(15) == 2, "level 2 at 15 XP");
        check(PitLevels.xpToNext(0) == 15 && PitLevels.xpToNext(10) == 5, "needed XP counts down");
        check(PitLevels.levelFor(PitLevels.xpForLevel(10)) == 10 && PitLevels.levelFor(PitLevels.xpForLevel(10) - 1) == 9, "level 10 boundary");
        check(PitLevels.xpForLevel(11) - PitLevels.xpForLevel(10) == 30, "levels 10-19 cost 30 XP");
        check(PitLevels.levelFor(Long.MAX_VALUE / 4) == PitLevels.MAX_LEVEL && PitLevels.xpToNext(Long.MAX_VALUE / 4) == 0, "level caps at 120");
        check(PitLevels.progress(0) == 0f && PitLevels.progress(PitLevels.xpForLevel(120)) == 0f, "progress bounds");
        check(PitRewards.KILL_GOLD > 0 && PitRewards.KILL_XP > 0, "kills reward gold and XP");
    }

    static void store() throws Exception {
        Path dir = Files.createTempDirectory("pitstore");
        PitStatsStore s = new PitStatsStore(dir);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        PitStatsStore.Stats zero = s.load(a);
        check(zero.gold == 0 && zero.xp == 0 && zero.kills == 0 && zero.deaths == 0, "unknown player starts at zero");
        s.save(a, new PitStatsStore.Stats(25, 15, 2, 1));
        PitStatsStore.Stats re = new PitStatsStore(dir).load(a);
        check(re.gold == 25 && re.xp == 15 && re.kills == 2 && re.deaths == 1, "stats survive a new store instance (restart)");
        check(re.plus(10, 5, 1, 0).gold == 35 && re.plus(10, 5, 1, 0).kills == 3, "kill credit adds up");
        boolean threw = false;
        try { re.plus(-1, 0, 0, 0); } catch (IllegalArgumentException ex) { threw = true; }
        check(threw, "negative credit rejected");
        threw = false;
        try { new PitStatsStore.Stats(Long.MAX_VALUE, 0, 0, 0).plus(1, 0, 0, 0); } catch (ArithmeticException ex) { threw = true; }
        check(threw, "overflow rejected");
        Files.write(dir.resolve(b + ".txt"), "PIT1 7 9\n".getBytes(StandardCharsets.UTF_8));
        PitStatsStore.Stats legacy = s.load(b);
        check(legacy.gold == 7 && legacy.xp == 9 && legacy.kills == 0, "legacy PIT1 records load");
        Files.write(dir.resolve(b + ".txt"), "garbage".getBytes(StandardCharsets.UTF_8));
        threw = false;
        try { s.load(b); } catch (java.io.IOException ex) { threw = true; }
        check(threw, "corrupt record refused instead of reset to zero");
        int temps = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, ".pit-*")) { for (Path ignored : ds) temps++; }
        check(temps == 0, "no temporary files left behind");
    }
}

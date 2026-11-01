package net.h2020.pit;

import net.h2020.maps.Worlds;
import net.minecraft.server.v1_8_R3.FileIOThread;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

/** Builds a new Pit once and preserves its verified world on restarts. */
public final class PitWorld {
    public static final String NAME = "h2020_pit";
    private PitWorld() {}

    @SuppressWarnings("deprecation")
    public static World create(Logger log, Path data) {
        try {
            PitOwnership ownership = new PitOwnership(Worlds.container().toPath(), data);
            boolean fresh = ownership.prepare();
            World w = Bukkit.getWorld(NAME);
            if (w == null) {
                w = Bukkit.createWorld(new org.bukkit.WorldCreator(NAME).environment(World.Environment.NORMAL)
                        .type(org.bukkit.WorldType.FLAT).generateStructures(false).generator(new PitGenerator()));
            }
            ownership.verifyLoaded(w);
            Worlds.applyRules(w, true);
            w.setGameRuleValue("naturalRegeneration", "true");
            if (fresh) {
                w.setSpawnLocation((int) Math.floor(PitLayout.SPAWN_X), (int) PitLayout.SPAWN_Y, (int) Math.floor(PitLayout.SPAWN_Z));
                int placed = 0;
                for (int x = PitLayout.MIN; x <= PitLayout.MAX; x++)
                    for (int z = PitLayout.MIN; z <= PitLayout.MAX; z++)
                        for (int y = PitLayout.Y_MIN; y <= PitLayout.Y_MAX; y++) {
                            int packed = PitLayout.block(x, y, z);
                            Block b = w.getBlockAt(x, y, z);
                            int id = packed >> 4, blockData = packed & 15;
                            if (b.getTypeId() != id || b.getData() != blockData) b.setTypeIdAndData(id, (byte) blockData, false);
                            if (id != 0) placed++;
                        }
                w.save();
                try { FileIOThread.a().b(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("world save interrupted", e); }
                ownership.commit(w);
                log.info("Pit world " + NAME + " generated: " + placed + " blocks, layout hash " + Long.toHexString(PitLayout.hash()));
            } else log.info("Pit world " + NAME + " ownership verified; restored " + w.getUID());
            return w;
        } catch (IOException e) { throw new IllegalStateException("Pit world ownership validation failed; Pit disabled", e); }
    }

    public static Location spawn(World w) {
        return new Location(w, PitLayout.SPAWN_X, PitLayout.SPAWN_Y, PitLayout.SPAWN_Z, PitLayout.SPAWN_YAW, 0f);
    }

    public static boolean isPit(World w) { return w != null && NAME.equals(w.getName()); }
}

package net.h2020.pit;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.material.MaterialData;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Chunk generator that emits {@link PitLayout} geometry, so unloaded/reloaded chunks always come back intact. */
final class PitGenerator extends ChunkGenerator {
    @Override
    @SuppressWarnings("deprecation")
    public ChunkData generateChunkData(World world, Random random, int cx, int cz, BiomeGrid grid) {
        ChunkData d = createChunkData(world);
        for (int bx = 0; bx < 16; bx++)
            for (int bz = 0; bz < 16; bz++) {
                grid.setBiome(bx, bz, Biome.PLAINS);
                int x = (cx << 4) + bx, z = (cz << 4) + bz;
                if (x < PitLayout.MIN || x > PitLayout.MAX || z < PitLayout.MIN || z > PitLayout.MAX) continue;
                for (int y = PitLayout.Y_MIN; y <= PitLayout.Y_MAX; y++) {
                    int packed = PitLayout.block(x, y, z);
                    int id = packed >> 4;
                    if (id != 0) d.setBlock(bx, y, bz, new MaterialData(Material.getMaterial(id), (byte) (packed & 15)));
                }
            }
        return d;
    }

    @Override public boolean canSpawn(World world, int x, int z) { return true; }
    @Override public List<BlockPopulator> getDefaultPopulators(World world) { return Collections.emptyList(); }
    @Override public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, PitLayout.SPAWN_X, PitLayout.SPAWN_Y, PitLayout.SPAWN_Z);
    }
}

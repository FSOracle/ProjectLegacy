package net.h2020.pit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * UUID-keyed Pit stats, one small text file per player, written via temp file + fsync + atomic rename.
 * Single process/writer only. A corrupt record is refused rather than silently reset to zero.
 */
public final class PitStatsStore {
    public static final class Stats {
        public final long gold, xp, kills, deaths;
        public Stats(long gold, long xp, long kills, long deaths) {
            if (gold < 0 || xp < 0 || kills < 0 || deaths < 0) throw new IllegalArgumentException("negative stats");
            this.gold = gold; this.xp = xp; this.kills = kills; this.deaths = deaths;
        }
        public Stats plus(long g, long x, long k, long d) {
            if (g < 0 || x < 0 || k < 0 || d < 0) throw new IllegalArgumentException("negative credit");
            return new Stats(Math.addExact(gold, g), Math.addExact(xp, x), Math.addExact(kills, k), Math.addExact(deaths, d));
        }
        public static final Stats ZERO = new Stats(0, 0, 0, 0);
    }

    private final Path dir;

    public PitStatsStore(Path dir) throws IOException {
        this.dir = Objects.requireNonNull(dir, "dir");
        Files.createDirectories(dir);
    }

    private Path file(UUID id) { return dir.resolve(Objects.requireNonNull(id, "id").toString() + ".txt"); }

    public synchronized Stats load(UUID id) throws IOException {
        Path file = file(id);
        if (Files.notExists(file)) return Stats.ZERO;
        String[] f = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim().split("\\s+", -1);
        try {
            if (f.length == 3 && "PIT1".equals(f[0])) return new Stats(Long.parseLong(f[1]), Long.parseLong(f[2]), 0, 0);
            if (f.length == 5 && "PIT2".equals(f[0]))
                return new Stats(Long.parseLong(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid Pit stats: " + file, ex);
        }
        throw new IOException("Invalid Pit stats: " + file);
    }

    public synchronized void save(UUID id, Stats s) throws IOException {
        byte[] bytes = ("PIT2 " + s.gold + " " + s.xp + " " + s.kills + " " + s.deaths + "\n").getBytes(StandardCharsets.UTF_8);
        Path temp = Files.createTempFile(dir, ".pit-", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);
            }
            try { Files.move(temp, file(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { throw new IOException("Atomic move required", ex); }
            try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) { d.force(true); }
            catch (IOException ignored) { /* directory fsync unsupported on some filesystems */ }
        } finally { Files.deleteIfExists(temp); }
    }
}

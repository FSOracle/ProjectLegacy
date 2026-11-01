package net.h2020.pit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Advisory interprocess guard. An administrator must initialize the marker offline. */
final class PitRunGuard implements AutoCloseable {
    private static final byte[] CLEAN = "PIT-RUN-1 CLEAN\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DIRTY = "PIT-RUN-1 DIRTY\n".getBytes(StandardCharsets.US_ASCII);
    private final FileChannel channel;
    private final FileLock lock;
    private PitRunGuard(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    static PitRunGuard acquire(Path marker) throws IOException {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing or invalid Pit run marker; offline inspection required");
        FileChannel channel = FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("another Pit process owns the marker");
            if (channel.size() != CLEAN.length) throw new IOException("dirty or invalid Pit marker; offline inspection required");
            ByteBuffer b = ByteBuffer.allocate(CLEAN.length);
            while (b.hasRemaining()) if (channel.read(b) < 0) throw new IOException("incomplete Pit marker");
            if (!Arrays.equals(b.array(), CLEAN)) throw new IOException("dirty or invalid Pit marker; offline inspection required");
            PitRunGuard guard = new PitRunGuard(channel, lock);
            guard.write(DIRTY);
            return guard;
        } catch (Throwable e) {
            try { if (lock != null) lock.release(); } catch (Throwable x) { e.addSuppressed(x); }
            try { channel.close(); } catch (Throwable x) { e.addSuppressed(x); }
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("cannot establish Pit run guard", e);
        }
    }
    private void write(byte[] bytes) throws IOException {
        channel.position(0);
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) channel.write(b);
        channel.truncate(bytes.length);
        channel.force(true);
        if (channel.size() != bytes.length) throw new IOException("Pit marker size mismatch");
        channel.position(0);
        ByteBuffer check = ByteBuffer.allocate(bytes.length);
        while (check.hasRemaining()) if (channel.read(check) < 0) throw new IOException("Pit marker readback failed");
        if (!Arrays.equals(check.array(), bytes)) throw new IOException("Pit marker readback mismatch");
    }
    void clean() throws IOException { write(CLEAN); }
    void invalidate() throws IOException { write(DIRTY); }
    public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}

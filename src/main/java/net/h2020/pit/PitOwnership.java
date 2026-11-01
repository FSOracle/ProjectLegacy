package net.h2020.pit;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Linux-only ownership guard. Requires exclusive control of the filesystem namespace. */
final class PitOwnership {
    private final Path container, world, data, record;
    private UUID expected;
    private boolean fresh;

    PitOwnership(Path container, Path data) {
        this.container = container.toAbsolutePath().normalize();
        this.world = this.container.resolve(PitWorld.NAME);
        this.data = data.toAbsolutePath().normalize();
        this.record = this.data.resolve("pit-world-owner.properties");
    }

    boolean prepare() throws IOException {
        noSymlinkComponents(container);
        noSymlinkComponents(data);
        if (!Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("world container or plugin data directory unavailable");
        if (!Files.isRegularFile(Paths.get("/proc/self/mountinfo"))) throw new IOException("Linux mountinfo unavailable");
        World loaded = Bukkit.getWorld(PitWorld.NAME);
        if (!Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
            if (loaded != null) throw new IOException("unowned world already loaded");
            try { Files.createDirectory(world); }
            catch (FileAlreadyExistsException e) { throw new IOException("unowned world path exists", e); }
            validateTree();
            fresh = true;
            return true;
        }
        requirePlainFile(record);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(record, LinkOption.NOFOLLOW_LINKS)) { p.load(in); }
        if (!"1".equals(p.getProperty("version")) || !world.toString().equals(p.getProperty("path")))
            throw new IOException("invalid ownership record");
        try { expected = UUID.fromString(p.getProperty("uuid", "")); }
        catch (IllegalArgumentException ex) { throw new IOException("invalid owner UUID", ex); }
        validateTree();
        if (!key(world).equals(p.getProperty("dirKey"))) throw new IOException("world directory identity changed");
        if (!expected.equals(readUid())) throw new IOException("world UUID changed");
        World byId = Bukkit.getWorld(expected);
        if (byId != null && byId != loaded) throw new IOException("world UUID is already loaded elsewhere");
        if (loaded != null) verifyLoaded(loaded);
        return false;
    }

    void verifyLoaded(World w) throws IOException {
        if (w == null || !world.equals(w.getWorldFolder().toPath().toAbsolutePath().normalize()) ||
                !PitWorld.NAME.equals(w.getName()) || (expected != null && !expected.equals(w.getUID())))
            throw new IOException("loaded world identity mismatch");
        validateTree();
        if (expected != null && !expected.equals(readUid())) throw new IOException("world UUID changed during load");
    }

    void commit(World w) throws IOException {
        if (!fresh) throw new IOException("not newly created");
        expected = w.getUID();
        verifyLoaded(w);
        if (!expected.equals(readUid())) throw new IOException("world UUID not persisted");
        requirePlainFile(world.resolve("level.dat"));
        Properties p = new Properties();
        p.setProperty("version", "1"); p.setProperty("path", world.toString());
        p.setProperty("uuid", expected.toString()); p.setProperty("dirKey", key(world));
        try (OutputStream out = Files.newOutputStream(record, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            p.store(out, "Pit world ownership; do not edit");
        }
        try (FileChannel ch = FileChannel.open(record, StandardOpenOption.WRITE)) { ch.force(true); }
        try (FileChannel ch = FileChannel.open(data, StandardOpenOption.READ)) { ch.force(true); }
    }

    private UUID readUid() throws IOException {
        Path uid = world.resolve("uid.dat");
        requirePlainFile(uid);
        try (DataInputStream in = new DataInputStream(Files.newInputStream(uid, LinkOption.NOFOLLOW_LINKS))) {
            UUID id = new UUID(in.readLong(), in.readLong());
            if (in.read() != -1) throw new IOException("invalid uid.dat length");
            return id;
        }
    }

    private static String key(Path path) throws IOException {
        Object k = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (k == null) throw new IOException("filesystem has no stable file key");
        return k.toString();
    }

    private static void noSymlinkComponents(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("symlink component: " + current);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing path component: " + current);
        }
    }

    private static void requirePlainFile(Path p) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.isSymbolicLink()) throw new IOException("not a plain file: " + p);
        Object links = Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!(links instanceof Number) || ((Number) links).longValue() != 1) throw new IOException("hard-linked file: " + p);
    }

    private void validateTree() throws IOException {
        noSymlinkComponents(world);
        if (!Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) throw new IOException("world is not a directory");
        for (String line : Files.readAllLines(Paths.get("/proc/self/mountinfo"))) {
            String[] parts = line.split(" ");
            if (parts.length < 6) throw new IOException("malformed mountinfo");
            String m = parts[4].replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\");
            Path mount = Paths.get(m).toAbsolutePath().normalize();
            if (mount.startsWith(world)) throw new IOException("mount inside world: " + mount);
        }
        Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) throws IOException {
                if (a.isSymbolicLink() || !a.isDirectory()) throw new IOException("unsafe directory: " + p);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                requirePlainFile(p); return FileVisitResult.CONTINUE;
            }
        });
    }
}

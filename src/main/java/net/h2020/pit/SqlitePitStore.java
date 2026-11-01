package net.h2020.pit;

import java.io.IOException;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.sql.*;
import java.util.UUID;

/** SQLite Pit authority. Single-writer staging candidate; not approved for deployment. */
public final class SqlitePitStore implements AutoCloseable {
    private final Connection db;
    public SqlitePitStore(Path dir) throws Exception {
        Files.createDirectories(dir);
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("pit.sqlite").toAbsolutePath());
        try {
            try (Statement st = db.createStatement()) {
                try (ResultSet r = st.executeQuery("PRAGMA journal_mode=DELETE")) {
                    if (!r.next() || !"delete".equalsIgnoreCase(r.getString(1))) throw new SQLException("DELETE journal required");
                }
                st.execute("PRAGMA synchronous=FULL");
                try (ResultSet r = st.executeQuery("PRAGMA synchronous")) {
                    if (!r.next() || r.getInt(1) != 2) throw new SQLException("FULL synchronous required");
                }
                try (ResultSet r = st.executeQuery("PRAGMA integrity_check")) {
                    if (!r.next() || !"ok".equalsIgnoreCase(r.getString(1))) throw new SQLException("integrity check failed");
                }
            }
            db.setAutoCommit(false);
            try (Statement st = db.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS stats (id TEXT PRIMARY KEY, gold INTEGER NOT NULL, xp INTEGER NOT NULL, kills INTEGER NOT NULL, deaths INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, victim TEXT NOT NULL, killer TEXT NOT NULL, gold INTEGER NOT NULL, xp INTEGER NOT NULL)");
            }
            String version = null;
            try (PreparedStatement q = db.prepareStatement("SELECT value FROM meta WHERE key='schema'" ); ResultSet r = q.executeQuery()) {
                if (r.next()) version = r.getString(1);
            }
            if (version != null && !"1".equals(version)) throw new SQLException("unknown schema " + version);
            if (version == null) {
                Path backup = backupLegacy(dir);
                PitStatsStore legacy = new PitStatsStore(backup);
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(backup, "*.txt")) {
                    for (Path f : entries) {
                        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular legacy file: " + f);
                        String name = f.getFileName().toString();
                        UUID id;
                        try { id = UUID.fromString(name.substring(0,name.length()-4)); }
                        catch (IllegalArgumentException ex) { throw new IOException("invalid legacy filename: " + f,ex); }
                        if (!name.equals(id.toString()+".txt")) throw new IOException("noncanonical legacy filename: " + f);
                        PitStatsStore.Stats s = legacy.load(id);
                        try (PreparedStatement p = db.prepareStatement("INSERT INTO stats VALUES(?,?,?,?,?)")) {
                            p.setString(1,id.toString()); p.setLong(2,s.gold); p.setLong(3,s.xp); p.setLong(4,s.kills); p.setLong(5,s.deaths); p.executeUpdate();
                        }
                    }
                }
                try (Statement st = db.createStatement()) { st.execute("INSERT INTO meta VALUES('schema','1')"); }
            }
            db.commit(); db.setAutoCommit(true);
        } catch (Exception ex) {
            try { db.rollback(); } catch (Exception suppressed) { ex.addSuppressed(suppressed); }
            try { db.close(); } catch (Exception suppressed) { ex.addSuppressed(suppressed); }
            throw ex;
        }
    }
    // Independent, never deleted even on failure; load only the verified snapshot.
    private static Path backupLegacy(Path dir) throws IOException {
        Path root = dir.toAbsolutePath().getParent();
        Path backup = Files.createTempDirectory(root, "pit-legacy-backup-");
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path source : entries) {
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular legacy file: " + source);
                String name = source.getFileName().toString();
                names.add(name);
                byte[] contents = Files.readAllBytes(source);
                Path dest = backup.resolve(name);
                try (FileChannel out = FileChannel.open(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer bytes = ByteBuffer.wrap(contents);
                    while (bytes.hasRemaining()) out.write(bytes);
                    out.force(true);
                }
                if (!Arrays.equals(contents, Files.readAllBytes(dest)) || !Arrays.equals(contents, Files.readAllBytes(source)))
                    throw new IOException("legacy snapshot verification failed: " + source);
            }
        }
        Set<String> after = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path source : entries) {
                String name = source.getFileName().toString(); after.add(name);
                if (!names.contains(name) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) ||
                    !Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(backup.resolve(name))))
                    throw new IOException("legacy data changed during backup: " + source);
            }
        }
        if (!names.equals(after)) throw new IOException("legacy file set changed during backup");
        try (FileChannel out = FileChannel.open(backup, StandardOpenOption.READ)) { out.force(true); }
        try (FileChannel out = FileChannel.open(root, StandardOpenOption.READ)) { out.force(true); }
        return backup;
    }

    public synchronized PitStatsStore.Stats load(UUID id) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT gold,xp,kills,deaths,typeof(gold),typeof(xp),typeof(kills),typeof(deaths) FROM stats WHERE id=?")) {
            q.setString(1,id.toString());
            try (ResultSet r=q.executeQuery()) {
                if (!r.next()) return PitStatsStore.Stats.ZERO;
                for (int i=5; i<=8; i++) if (!"integer".equals(r.getString(i))) throw new SQLException("invalid stats type for " + id);
                try { return new PitStatsStore.Stats(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)); }
                catch (IllegalArgumentException ex) { throw new SQLException("invalid stats values for " + id, ex); }
            }
        }
    }
    private boolean blocked;
    private void write(UUID id, PitStatsStore.Stats v) throws SQLException {
        try (PreparedStatement p=db.prepareStatement("INSERT OR REPLACE INTO stats VALUES(?,?,?,?,?)")) {
            p.setString(1,id.toString()); p.setLong(2,v.gold); p.setLong(3,v.xp); p.setLong(4,v.kills); p.setLong(5,v.deaths); p.executeUpdate();
        }
    }
    public synchronized boolean apply(UUID event, UUID victim, UUID killer, long gold, long xp, java.util.function.Consumer<String> hook) throws Exception {
        if(blocked) throw new SQLException("store blocked after failure");
        if(event==null || victim==null || victim.equals(killer) || gold<0 || xp<0 || (killer==null && (gold!=0 || xp!=0))) throw new IllegalArgumentException("bad death");
        String killerId = killer == null ? "" : killer.toString();
        try {
            db.setAutoCommit(false);
            boolean duplicate=false;
            try(PreparedStatement q=db.prepareStatement("SELECT victim,killer,gold,xp FROM events WHERE id=?")) {
                q.setString(1,event.toString());
                try(ResultSet r=q.executeQuery()) {
                    if(r.next()) {
                        if(!victim.toString().equals(r.getString(1)) || !killerId.equals(r.getString(2)) || gold!=r.getLong(3) || xp!=r.getLong(4)) throw new SQLException("event ID conflict");
                        duplicate=true;
                    }
                }
            }
            if(duplicate) { db.rollback(); db.setAutoCommit(true); return false; }
            try(PreparedStatement p=db.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")) {
                p.setString(1,event.toString()); p.setString(2,victim.toString()); p.setString(3,killerId); p.setLong(4,gold); p.setLong(5,xp); p.executeUpdate();
            }
            if(hook!=null) hook.accept("AFTER_EVENT");
            write(victim,load(victim).plus(0,0,0,1));
            if(hook!=null) hook.accept("AFTER_VICTIM");
            if(killer!=null) write(killer,load(killer).plus(gold,xp,1,0));
            if(hook!=null) hook.accept("BEFORE_COMMIT");
            db.commit();
            if(hook!=null) hook.accept("AFTER_COMMIT");
            db.setAutoCommit(true); return true;
        } catch(Exception ex) {
            blocked=true; try { db.rollback(); } catch(Exception suppressed) { ex.addSuppressed(suppressed); } throw ex;
        }
    }
    @Override public void close() throws SQLException { db.close(); }
}

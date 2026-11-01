package net.h2020.core.prefs;

import net.h2020.api.game.GameType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One authoritative in-process store. An unreadable or malformed store fails closed. */
public final class BotMatchPrefs {
    private final Path file;
    private Map<UUID, Map<GameType, Boolean>> values = new HashMap<>();
    // null means known. An unknown state denies bots and refuses all further writes.
    private String unavailable;

    public BotMatchPrefs(Path file) { this.file = file.toAbsolutePath(); load(); }
    public synchronized boolean available() { return unavailable == null; }
    public synchronized String unavailableReason() { return unavailable; }
    public synchronized boolean accepts(UUID player, GameType type) {
        if (unavailable != null) return false;
        Map<GameType, Boolean> m = values.get(player);
        Boolean v = m == null ? null : m.get(type);
        return v == null || v;
    }
    public synchronized void set(UUID player, GameType type, boolean accept) {
        if (unavailable != null) throw new IllegalStateException(unavailable);
        Map<UUID, Map<GameType, Boolean>> next = new HashMap<>();
        for (Map.Entry<UUID, Map<GameType, Boolean>> e : values.entrySet()) {
            Map<GameType, Boolean> copy = new EnumMap<>(GameType.class);
            copy.putAll(e.getValue()); next.put(e.getKey(), copy);
        }
        Map<GameType, Boolean> m = next.get(player);
        if (m == null) { m = new EnumMap<>(GameType.class); next.put(player, m); }
        m.put(type, accept);
        save(next);
        values = next;
    }
    private void load() {
        try {
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular preference file");
            List<String> records = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (records.isEmpty()) throw new IOException("empty preference file");
            if (records.get(0).startsWith("# H2020-BOT-PREFS-V1 ")) {
                String expected = records.remove(0).substring("# H2020-BOT-PREFS-V1 ".length());
                if (!expected.equals(checksum(String.join("\n", records) + "\n"))) throw new IOException("preference checksum mismatch");
            }
            for (String line : records) {
                String[] p = line.trim().split("\\s+");
                if (p.length != 3) throw new IOException("malformed preference record");
                UUID id = UUID.fromString(p[0]);
                if (!id.toString().equalsIgnoreCase(p[0])) throw new IOException("non-canonical UUID");
                GameType type = GameType.valueOf(p[1]);
                boolean on;
                if (p[2].equalsIgnoreCase("on")) on = true;
                else if (p[2].equalsIgnoreCase("off")) on = false;
                else throw new IOException("invalid preference value");
                Map<GameType, Boolean> m = values.get(id);
                if (m == null) { m = new EnumMap<>(GameType.class); values.put(id, m); }
                if (m.put(type, on) != null) throw new IOException("duplicate preference record");
            }
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            values.clear(); unavailable = "Preference file unavailable or corrupt; bot matching and changes disabled; contact an administrator.";
        }
    }
    private static String checksum(String data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void save(Map<UUID, Map<GameType, Boolean>> next) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<UUID, Map<GameType, Boolean>> e : next.entrySet())
            for (Map.Entry<GameType, Boolean> v : e.getValue().entrySet())
                lines.add(e.getKey() + " " + v.getKey().name() + " " + (v.getValue() ? "on" : "off"));
        Path parent = file.getParent();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        boolean created = false, moveAttempted = false;
        try {
            Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                String body = String.join("\n", lines) + "\n";
                byte[] bytes = ("# H2020-BOT-PREFS-V1 " + checksum(body) + "\n" + body).getBytes(StandardCharsets.UTF_8);
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) channel.write(buf);
                channel.force(true);
            }
            moveAttempted = true;
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) { directory.force(true); }
        } catch (IOException | SecurityException e) {
            if (moveAttempted) unavailable = "Preference commit outcome uncertain; bot matching and changes disabled; contact an administrator.";
            throw new IllegalStateException(moveAttempted ? unavailable : "Preference not saved; previous setting remains effective.", e);
        } finally {
            if (created) try { Files.deleteIfExists(tmp); } catch (IOException | SecurityException ignored) { }
        }
    }
}

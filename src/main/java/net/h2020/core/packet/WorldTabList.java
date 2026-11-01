package net.h2020.core.packet;

import net.h2020.core.CoreUtil;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo.EnumPlayerInfoAction;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo.PlayerInfoData;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Filters tab packets without changing entity visibility or spectator rules. */
public final class WorldTabList implements Listener {
    private static final Field ACTION = field("a");
    private static final Field ENTRIES = field("b");
    private final JavaPlugin plugin;
    // Written on the main thread; Netty reads snapshots without accessing Bukkit worlds.
    private final Map<UUID, UUID> worlds = new ConcurrentHashMap<>();

    public WorldTabList(JavaPlugin plugin) { this.plugin = plugin; }

    private static Field field(String name) {
        try {
            Field field = PacketPlayOutPlayerInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot access Spigot 1.8.8 tab packet field " + name, e);
        }
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) track(player);
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) refresh(player);
            }
        });
    }

    private void track(Player player) {
        worlds.put(player.getUniqueId(), player.getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { changed(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) { changed(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { worlds.remove(event.getPlayer().getUniqueId()); }

    private void changed(final Player player) {
        track(player);
        // Login and teleport code can still send its own tab packets during this tick.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { if (player.isOnline()) refresh(player); }
        });
    }

    private void refresh(Player changed) {
        if (!changed.isOnline()) return;
        track(changed);
        for (Player other : Bukkit.getOnlinePlayers()) {
            update(changed, other);
            if (!other.getUniqueId().equals(changed.getUniqueId())) update(other, changed);
        }
    }

    private void update(Player viewer, Player target) {
        if (CoreUtil.isBot(viewer)) return;
        boolean visible = viewer.getWorld().equals(target.getWorld()) && viewer.canSee(target);
        CoreUtil.send(viewer, new PacketPlayOutPlayerInfo(
                visible ? EnumPlayerInfoAction.ADD_PLAYER : EnumPlayerInfoAction.REMOVE_PLAYER,
                ((CraftPlayer) target).getHandle()));
    }

    /** Never mutate a broadcast packet: the same instance may be sent to several worlds. */
    public PacketPlayOutPlayerInfo filter(UUID viewer, PacketPlayOutPlayerInfo packet) {
        UUID world = worlds.get(viewer);
        if (world == null) return packet;
        try {
            if (ACTION.get(packet) == EnumPlayerInfoAction.REMOVE_PLAYER) return packet;
            List<PlayerInfoData> original = entries(packet);
            List<PlayerInfoData> visible = new ArrayList<>();
            for (PlayerInfoData entry : original) {
                UUID targetWorld = worlds.get(entry.a().getId());
                // Packet-only NPCs are not online players; preserve their skin loading sequence.
                if (targetWorld == null || world.equals(targetWorld)) visible.add(entry);
            }
            if (visible.size() == original.size()) return packet;
            if (visible.isEmpty()) return null;
            PacketPlayOutPlayerInfo filtered = new PacketPlayOutPlayerInfo();
            ACTION.set(filtered, ACTION.get(packet));
            entries(filtered).addAll(visible);
            return filtered;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot filter tab list", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PlayerInfoData> entries(PacketPlayOutPlayerInfo packet) throws IllegalAccessException {
        return (List<PlayerInfoData>) ENTRIES.get(packet);
    }

    public void shutdown() { worlds.clear(); }
}

package net.h2020.core.packet;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.server.v1_8_R3.PacketDataSerializer;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo.EnumPlayerInfoAction;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo.PlayerInfoData;
import net.minecraft.server.v1_8_R3.WorldSettings;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class WorldTabListTest {
    private WorldTabList tabs;
    private Map<UUID, UUID> worlds;
    private final UUID lobby = UUID.randomUUID(), arena = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID(), opponent = UUID.randomUUID();
    private final UUID bot = UUID.randomUUID(), npc = UUID.randomUUID();

    @Before @SuppressWarnings("unchecked")
    public void setup() throws Exception {
        tabs = new WorldTabList(null);
        Field field = WorldTabList.class.getDeclaredField("worlds");
        field.setAccessible(true);
        worlds = (Map<UUID, UUID>) field.get(tabs);
        worlds.put(viewer, lobby);
        worlds.put(bot, lobby);
        worlds.put(opponent, arena);
    }

    @Test public void everyUpdateFiltersWorldsWithoutMutatingBroadcast() throws Exception {
        for (EnumPlayerInfoAction action : EnumPlayerInfoAction.values()) {
            if (action == EnumPlayerInfoAction.REMOVE_PLAYER) continue;
            PacketPlayOutPlayerInfo original = packet(action, viewer, opponent, bot, npc);
            assertEquals(Arrays.asList(viewer, bot, npc), ids(tabs.filter(viewer, original)));
            assertEquals(Arrays.asList(opponent, npc), ids(tabs.filter(opponent, original)));
            assertEquals(Arrays.asList(viewer, opponent, bot, npc), ids(original));
        }
    }

    @Test public void removalsAlwaysPassAndForeignOnlyAddsDrop() throws Exception {
        PacketPlayOutPlayerInfo removal = packet(EnumPlayerInfoAction.REMOVE_PLAYER, opponent);
        assertSame(removal, tabs.filter(viewer, removal));
        assertNull(tabs.filter(viewer, packet(EnumPlayerInfoAction.ADD_PLAYER, opponent)));
        PacketPlayOutPlayerInfo local = packet(EnumPlayerInfoAction.ADD_PLAYER, viewer, bot);
        assertSame(local, tabs.filter(viewer, local));
    }

    @Test public void movingBetweenLobbyAndArenaUpdatesBothViews() throws Exception {
        PacketPlayOutPlayerInfo packet = packet(EnumPlayerInfoAction.ADD_PLAYER, viewer, opponent, bot);
        worlds.put(viewer, arena);
        assertEquals(Arrays.asList(viewer, opponent), ids(tabs.filter(viewer, packet)));
        assertEquals(Arrays.asList(bot), ids(tabs.filter(bot, packet)));
        worlds.put(viewer, lobby);
        assertEquals(Arrays.asList(viewer, bot), ids(tabs.filter(viewer, packet)));
    }

    @Test public void filteredPacketsKeepWireFormatIncludingDisplayNames() throws Exception {
        for (EnumPlayerInfoAction action : EnumPlayerInfoAction.values()) {
            if (action == EnumPlayerInfoAction.REMOVE_PLAYER) continue;
            PacketPlayOutPlayerInfo filtered = tabs.filter(viewer, packet(action, viewer, opponent, bot));
            PacketDataSerializer buffer = new PacketDataSerializer(Unpooled.buffer());
            try {
                filtered.b(buffer);
                PacketPlayOutPlayerInfo decoded = new PacketPlayOutPlayerInfo();
                decoded.a(buffer);
                assertEquals(Arrays.asList(viewer, bot), ids(decoded));
                assertEquals(action, field("a").get(decoded));
            } finally { buffer.release(); }
        }
    }

    @Test public void unknownLoginAndNpcEntriesArePreserved() throws Exception {
        PacketPlayOutPlayerInfo packet = packet(EnumPlayerInfoAction.ADD_PLAYER, npc);
        assertSame(packet, tabs.filter(viewer, packet));
        assertSame(packet, tabs.filter(UUID.randomUUID(), packet));
        tabs.shutdown();
        assertTrue(worlds.isEmpty());
    }

    private static Field field(String name) throws Exception {
        Field field = PacketPlayOutPlayerInfo.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static PacketPlayOutPlayerInfo packet(EnumPlayerInfoAction action, UUID... players) throws Exception {
        PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo();
        field("a").set(packet, action);
        List<PlayerInfoData> entries = (List<PlayerInfoData>) field("b").get(packet);
        for (int i = 0; i < players.length; i++) {
            entries.add(packet.new PlayerInfoData(new GameProfile(players[i], "Player" + i),
                    50, WorldSettings.EnumGamemode.SURVIVAL, null));
        }
        return packet;
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> ids(PacketPlayOutPlayerInfo packet) throws Exception {
        List<UUID> result = new ArrayList<>();
        for (PlayerInfoData entry : (List<PlayerInfoData>) field("b").get(packet)) result.add(entry.a().getId());
        return result;
    }
}

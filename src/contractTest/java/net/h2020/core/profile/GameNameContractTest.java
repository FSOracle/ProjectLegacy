package net.h2020.core.profile;

import net.h2020.api.H2020;
import net.h2020.api.core.PlayerNames;
import net.h2020.api.core.ProfileService;
import net.h2020.api.core.Rank;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/** Uses the isolated Player stub; no world or running server is required. */
public final class GameNameContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        ProfileData data = new ProfileData(UUID.randomUUID(), "Steve");
        data.customRankTitle = NameFormatting.bracketedRank(ChatColor.AQUA + "Builder", ChatColor.GRAY);
        data.title = ChatColor.GOLD + "Winner";
        Player player = new Player() {
            @Override public UUID getUniqueId() { return data.uuid; }
            @Override public String getName() { return data.name; }
        };
        ProfileService profiles = new ProfileService() {
            @Override public Rank rank(Player p) { return data.rank; }
            @Override public ChatColor plusColor(Player p) { return data.plusColor; }
            @Override public void setRank(Player p, Rank rank, ChatColor color) {
                data.customRankTitle = null;
                data.rank = rank;
                data.plusColor = color;
            }
            @Override public int networkLevel(Player p) { return 1; }
            @Override public void setNetworkLevel(Player p, int level) {}
            @Override public String displayName(Player p) { return data.displayPrefix() + p.getName(); }
            @Override public String displayName(UUID uuid, String fallback) {
                return data.uuid.equals(uuid) ? data.displayPrefix() + data.name : ChatColor.GRAY + fallback;
            }
            @Override public ChatColor nameColor(Player p) { return data.nameColor(); }
            @Override public boolean hasRank(Player p) { return data.hasRank(); }
        };
        H2020.register(ProfileService.class, profiles);
        try {
            require(profiles.rank(player) == Rank.NONE, "custom rank keeps NONE authority");
            require(PlayerNames.hasRank(player), "custom rank counts as a visible rank");
            require(PlayerNames.color(player) == ChatColor.AQUA, "custom dominant color used");
            require(ChatColor.stripColor(PlayerNames.display(player)).equals("Winner [Builder] Steve"), "title and rank displayed");
            for (String type : new String[]{"net.h2020.bedwars.BedwarsGame", "net.h2020.skywars.game.SkywarsGame"}) {
                Method method = Class.forName(type).getDeclaredMethod("rankName", Player.class);
                method.setAccessible(true);
                require(PlayerNames.display(player).equals(method.invoke(null, player)), type + " uses full display");
            }
            Method hasRank = Class.forName("net.h2020.bedwars.BedwarsGame").getDeclaredMethod("hasRank", Player.class);
            hasRank.setAccessible(true);
            require(Boolean.TRUE.equals(hasRank.invoke(null, player)), "Bed Wars chat recognizes custom rank");
            require(PlayerNames.display(data.uuid, "OldName").equals(PlayerNames.display(player)), "UUID result retains full name");
            require(ChatColor.stripColor(PlayerNames.display(UUID.randomUUID(), "Unknown")).equals("Unknown"), "missing profile fallback");
            data.customRankTitle = null;
            data.title = null;
            require(!PlayerNames.hasRank(player), "cleared custom rank is unranked");
            data.rank = Rank.MVP_PLUS;
            require(PlayerNames.hasRank(player), "builtin rank unchanged");
            require(PlayerNames.display(player).contains("[MVP"), "builtin display retained");
        } finally { H2020.unregister(ProfileService.class, profiles); }
        require(PlayerNames.display(player).equals(ChatColor.GRAY + "Steve"), "service unavailable fallback");
        require(!PlayerNames.hasRank(player), "no service fallback is unranked");
        System.out.println("GAME_NAME_CONTRACT_OK " + checks);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

package net.h2020.api.core;

import net.h2020.api.H2020;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Shared public names for game notices and results; team-colored combat names stay separate. */
public final class PlayerNames {
    private PlayerNames() {}

    public static String display(Player player) {
        ProfileService profiles = H2020.find(ProfileService.class);
        return profiles == null ? ChatColor.GRAY + player.getName() : profiles.displayName(player);
    }

    public static String display(UUID uuid, String fallback) {
        ProfileService profiles = H2020.find(ProfileService.class);
        return profiles == null ? ChatColor.GRAY + fallback : profiles.displayName(uuid, fallback);
    }

    public static ChatColor color(Player player) {
        ProfileService profiles = H2020.find(ProfileService.class);
        return profiles == null ? ChatColor.GRAY : profiles.nameColor(player);
    }

    public static boolean hasRank(Player player) {
        ProfileService profiles = H2020.find(ProfileService.class);
        return profiles != null && profiles.hasRank(player);
    }
}

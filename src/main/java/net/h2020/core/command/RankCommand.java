package net.h2020.core.command;

import net.h2020.api.core.Rank;
import net.h2020.core.Core;
import net.h2020.core.CoreConfig;
import net.h2020.core.profile.ProfileData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** /rank <player> <rank|custom title> [color]. */
public final class RankCommand implements TabExecutor {
    private static final int MAX_CUSTOM_TITLE_LENGTH = 11;
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001F\\u007F]");
    private final Core core;

    public RankCommand(Core core) { this.core = core; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("h2020.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage ranks.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player> <rank|custom title> [color]");
            sender.sendMessage(ChatColor.YELLOW + "Ranks: " + Arrays.toString(Rank.values()));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player must be online: " + args[0]);
            return true;
        }
        int titleEnd = args.length;
        ChatColor color = null;
        if (args.length >= 3) {
            ChatColor last = parseColor(args[args.length - 1]);
            if (last != null) {
                color = last;
                titleEnd--;
            }
        }
        if (titleEnd == args.length && args.length >= 3
                && CoreConfig.parseEnum(Rank.class, args[1]) != null) {
            sender.sendMessage(ChatColor.RED + "Invalid color. Use a Minecraft color such as red, gold or &c.");
            return true;
        }
        String title = join(args, 1, titleEnd);
        Rank rank = CoreConfig.parseEnum(Rank.class, title);
        String customTitle = rank == null ? customTitle(title, color) : null;
        if (rank == null && customTitle == null) {
            sender.sendMessage(ChatColor.RED + "Invalid title. Use 1-" + MAX_CUSTOM_TITLE_LENGTH
                    + " visible characters and color codes only. Colored prefixes must fit the 16-character team limit.");
            return true;
        }
        if (rank != null) core.profiles.setRankColor(target, rank, color);
        else core.profiles.setCustomRank(target, customTitle, color);
        core.profiles.saveAll();
        sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + "'s rank to "
                + core.profiles.displayName(target));
        return true;
    }

    static ChatColor parseColor(String value) {
        ChatColor color;
        if (value.length() == 2 && value.charAt(0) == '&') {
            color = ChatColor.getByChar(Character.toLowerCase(value.charAt(1)));
        } else {
            color = CoreConfig.parseEnum(ChatColor.class, value);
        }
        return color != null && color.isColor() ? color : null;
    }

    static String customTitle(String title, ChatColor color) {
        if (title == null || title.trim().isEmpty() || CONTROL.matcher(title).find()
                || title.indexOf('[') >= 0 || title.indexOf(']') >= 0 || title.indexOf('\u00a7') >= 0) return null;
        for (int i = 0; i < title.length(); i++) {
            if (title.charAt(i) != '&') continue;
            if (i + 1 >= title.length()) return null;
            ChatColor code = ChatColor.getByChar(Character.toLowerCase(title.charAt(++i)));
            if (code == null || !code.isColor()) return null;
        }
        String translated = ChatColor.translateAlternateColorCodes('&', title);
        String plain = ChatColor.stripColor(translated);
        if (plain.trim().isEmpty() || plain.codePointCount(0, plain.length()) > MAX_CUSTOM_TITLE_LENGTH) return null;
        ProfileData preview = new ProfileData(null, "");
        preview.customRankTitle = translated;
        preview.rankColor = color;
        return preview.rankPrefix().length() <= 16 ? translated : null;
    }

    private static String join(String[] args, int from, int to) {
        StringBuilder value = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) value.append(' ');
            value.append(args[i]);
        }
        return value.toString().trim();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> matches = new ArrayList<>();
        if (!sender.hasPermission("h2020.admin") || args.length == 0) return matches;
        String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!(sender instanceof Player) || ((Player) sender).canSee(player)) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(input)) matches.add(player.getName());
                }
            }
        } else if (args.length == 2) {
            for (Rank rank : Rank.values()) {
                if (rank.name().toLowerCase(Locale.ROOT).startsWith(input)) matches.add(rank.name());
            }
        } else if (args.length >= 3) {
            for (ChatColor color : ChatColor.values()) {
                String name = color.name().toLowerCase(Locale.ROOT);
                if (color.isColor() && name.startsWith(input)) matches.add(name);
            }
        }
        return matches;
    }
}

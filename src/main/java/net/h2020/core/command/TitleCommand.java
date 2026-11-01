package net.h2020.core.command;

import net.h2020.core.Core;
import net.h2020.core.profile.NameFormatting;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /title <player> <text> [color]; /title <player> clear. */
public final class TitleCommand implements TabExecutor {
    private final Core core;

    public TitleCommand(Core core) { this.core = core; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("h2020.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage titles.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player> <text> [color] | /" + label + " <player> clear");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player must be online: " + args[0]);
            return true;
        }
        String title = null;
        ChatColor color = null;
        if (!(args.length == 2 && args[1].equalsIgnoreCase("clear"))) {
            int end = args.length;
            if (args.length >= 3) {
                color = RankCommand.parseColor(args[end - 1]);
                if (color != null) end--;
            }
            title = NameFormatting.text(RankCommand.join(args, 1, end), false);
            if (title == null) {
                sender.sendMessage(ChatColor.RED + "Title must contain visible text and no control characters.");
                return true;
            }
        }
        core.profiles.setTitle(target, title, color);
        core.profiles.saveAll();
        sender.sendMessage(ChatColor.GREEN + "Updated title: " + core.profiles.displayName(target));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> matches = new ArrayList<>();
        if (!sender.hasPermission("h2020.admin") || args.length == 0) return matches;
        String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if ((!(sender instanceof Player) || ((Player) sender).canSee(player))
                        && player.getName().toLowerCase(Locale.ROOT).startsWith(input)) matches.add(player.getName());
            }
        } else if (args.length == 2) {
            if ("clear".startsWith(input)) matches.add("clear");
        } else {
            for (ChatColor color : ChatColor.values()) {
                String name = color.name().toLowerCase(Locale.ROOT);
                if (color.isColor() && name.startsWith(input)) matches.add(name);
            }
        }
        return matches;
    }
}

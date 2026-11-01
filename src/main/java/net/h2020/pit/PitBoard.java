package net.h2020.pit;

import net.h2020.api.H2020;
import net.h2020.api.core.ScoreboardService;
import net.h2020.core.HypixelFormat;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Pit sidebar: level, needed XP, gold, kills/streak and combat status. */
final class PitBoard {
    private PitBoard() {}

    static List<String> lines(PitStatsStore.Stats s, int streak, boolean fighting) {
        List<String> l = new ArrayList<>();
        l.add(HypixelFormat.dateLine("mega13P"));
        l.add("");
        l.add("Level: " + ChatColor.GRAY + "[" + ChatColor.AQUA + PitLevels.levelFor(s.xp) + ChatColor.GRAY + "]");
        long need = PitLevels.xpToNext(s.xp);
        l.add("Needed XP: " + ChatColor.AQUA + (need <= 0 ? "MAXED!" : HypixelFormat.commas((int) Math.min(Integer.MAX_VALUE, need))));
        l.add("");
        l.add("Gold: " + ChatColor.GOLD + HypixelFormat.commas((int) Math.min(Integer.MAX_VALUE, s.gold)) + "g");
        l.add("");
        l.add("Status: " + (fighting ? ChatColor.RED + "Fighting" : ChatColor.GREEN + "Idling"));
        l.add("Kills: " + ChatColor.GREEN + s.kills);
        l.add("Streak: " + ChatColor.GREEN + streak);
        l.add("");
        l.add(ChatColor.YELLOW + "hypixel.today");
        return l;
    }

    static void update(PitModule m, Player p) {
        PitModule.Session s = m.session(p);
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (s == null || sb == null) return;
        boolean fighting = System.currentTimeMillis() - s.lastCombatAt < 5000;
        sb.setSidebar(p, ChatColor.YELLOW + "" + ChatColor.BOLD + "THE HYPIXEL PIT", lines(s.stats, s.streak, fighting));
        p.setLevel(PitLevels.levelFor(s.stats.xp));
        p.setExp(Math.max(0f, Math.min(0.999f, PitLevels.progress(s.stats.xp))));
    }
}

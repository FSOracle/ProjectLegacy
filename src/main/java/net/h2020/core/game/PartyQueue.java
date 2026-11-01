package net.h2020.core.game;

import net.h2020.api.H2020;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.api.game.GameType;
import net.h2020.api.game.events.PartyMemberJoinEvent;
import net.h2020.core.CoreUtil;
import net.h2020.social.api.PartyService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** Leader-only party queue: every online member into one game (one team in team modes), all or nothing. */
final class PartyQueue {
    private final GameManagerImpl gm;

    PartyQueue(GameManagerImpl gm) { this.gm = gm; }

    Game queue(Player leader, GameType type) {
        PartyService ps = CoreUtil.isBot(leader) ? null : H2020.find(PartyService.class);
        List<UUID> ids = ps == null ? Collections.singletonList(leader.getUniqueId()) : ps.membersOf(leader.getUniqueId());
        if (ids.size() <= 1) return gm.queue(leader, type);
        if (!ps.isLeader(leader.getUniqueId())) {
            leader.sendMessage(ChatColor.RED + "Only the party leader can queue the party for a game!");
            return null;
        }
        List<Player> party = new ArrayList<>();
        for (UUID id : ids) { // leader first; offline members are not pulled in
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && !CoreUtil.isBot(p)) party.add(p);
        }
        if (party.size() <= 1 || party.get(0) != leader) return gm.queue(leader, type);
        if (type.teamSize > 1 && party.size() > type.teamSize) {
            leader.sendMessage(ChatColor.RED + "Your party is too big for " + type.displayName + " (max " + type.teamSize + " per team)!");
            return null;
        }
        for (Player p : party) {
            Game cur = gm.gameOf(p);
            if (cur != null && cur.state() == GameState.PLAYING) {
                leader.sendMessage(ChatColor.RED + p.getName() + " is in a game in progress!");
                return null;
            }
        }
        if (!gm.hasProvider(type.family)) { leader.sendMessage(ChatColor.RED + "That game is currently unavailable!"); return null; }
        Game cur = gm.gameOf(leader);
        Game target = null;
        if (cur != null && cur.type() == type && waiting(cur)) target = cur; // the party joins the leader's lobby
        else {
            for (Game g : gm.games()) {
                if (g.type() != type || !waiting(g) || !fits(g, party)) continue;
                if (target == null || g.players().size() > target.players().size()) target = g;
            }
            if (target == null) target = gm.createRandom(type);
            if (target == null) { leader.sendMessage(ChatColor.RED + "Could not find a game for " + type.displayName + "!"); return null; }
        }
        if (!fits(target, party)) {
            leader.sendMessage(ChatColor.RED + "There is not enough space in " + target.id() + " for your party!");
            return null;
        }
        return join(leader, target, party, type);
    }

    private static boolean waiting(Game g) { return g.state() == GameState.WAITING || g.state() == GameState.STARTING; }

    /** Free slots and (team modes) one team with room for every member not yet in the game. */
    private boolean fits(Game g, List<Player> party) {
        int needed = 0;
        for (Player p : party) if (!g.players().contains(p)) needed++;
        int free = g.type().maxPlayers() - g.players().size() - gm.pendingBots(g);
        List<? extends GameTeam> teams = g.teams();
        if (teams != null && !teams.isEmpty()) {
            int room = 0, best = 0;
            for (GameTeam t : teams) { int r = Math.max(0, g.type().teamSize - t.members().size()); room += r; best = Math.max(best, r); }
            free = Math.min(free, room);
            if (g.type().teamSize > 1 && best < needed) return false;
        }
        return free >= needed;
    }

    private Game join(Player leader, Game target, List<Player> party, GameType type) {
        List<Player> added = new ArrayList<>();
        String failed = null;
        for (Player p : party) {
            if (target.players().contains(p)) continue;
            PartyMemberJoinEvent ev = new PartyMemberJoinEvent(p, target, leader);
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled()) { failed = p.getName(); break; }
            Game old = gm.gameOf(p);
            if (old != null) gm.leaveGame(p, old);
            if (gm.lobbyHooks() != null) gm.lobbyHooks().leaving(p);
            GameManagerImpl.resetPlayer(p, GameMode.ADVENTURE);
            boolean ok;
            try { ok = target.addPlayer(p); } catch (Throwable t) {
                Bukkit.getLogger().log(Level.WARNING, "party addPlayer failed in " + target.id(), t);
                ok = false;
            }
            if (!ok) { failed = p.getName(); if (gm.gameOf(p) == null) gm.sendToLobby(p, type.family); break; }
            added.add(p);
        }
        if (failed == null) {
            for (Player p : party) p.sendMessage(ChatColor.GREEN + "Party: sending your party to " + target.id() + "!");
            return target;
        }
        for (Player p : added) gm.sendToLobby(p, type.family); // roll back: nobody stays behind
        for (Player p : party) p.sendMessage(ChatColor.RED + "Your party could not join " + target.id() + " (" + failed + " could not be sent).");
        return null;
    }
}

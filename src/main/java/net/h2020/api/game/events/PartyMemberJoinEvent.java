package net.h2020.api.game.events;

import net.h2020.api.game.Game;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired (main thread) right before a party member is added to the party's target game. Cancelling it aborts
 *  the whole party join: members already added are rolled back to the lobby. */
public class PartyMemberJoinEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player member, leader;
    private final Game game;
    private boolean cancelled;

    public PartyMemberJoinEvent(Player member, Game game, Player leader) { this.member = member; this.game = game; this.leader = leader; }

    public Player member() { return member; }
    public Player leader() { return leader; }
    public Game game() { return game; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean c) { cancelled = c; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

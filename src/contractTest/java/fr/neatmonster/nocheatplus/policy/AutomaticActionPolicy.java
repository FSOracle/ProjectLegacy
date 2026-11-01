package fr.neatmonster.nocheatplus.policy;
import org.bukkit.entity.Player;
public final class AutomaticActionPolicy {
 public interface Policy { boolean allow(Player p, Reason r); }
 public enum Reason { ILLEGAL_MOVE_KICK, ILLEGAL_MOVE_LOGIN_DENY, VEHICLE_KICK, HOVER_KICK, RELOG_DENY, LOGIN_FREQUENCY_DENY }
}

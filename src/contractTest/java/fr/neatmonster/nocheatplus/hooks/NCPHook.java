package fr.neatmonster.nocheatplus.hooks;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.access.IViolationInfo;
public interface NCPHook { String getHookName(); String getHookVersion(); boolean onCheckFailure(CheckType type, org.bukkit.entity.Player player, IViolationInfo info); }

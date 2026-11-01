package fr.neatmonster.nocheatplus.hooks;
import fr.neatmonster.nocheatplus.checks.CheckType;
public final class NCPHookManager {
 public static NCPHook hook;
 public static Integer addHook(CheckType type,NCPHook h) { if(type!=CheckType.ALL) throw new AssertionError(); hook=h; return 1; }
 public static NCPHook removeHook(Integer id) { NCPHook h=hook; hook=null; return h; }
}

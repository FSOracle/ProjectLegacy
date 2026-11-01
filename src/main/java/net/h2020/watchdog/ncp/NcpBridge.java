package net.h2020.watchdog.ncp;

import net.h2020.api.watchdog.CheckType;
import java.lang.reflect.*;
import java.util.logging.Logger;

/** Bind original NCP violations to Watchdog while retaining NCP setbacks. */
public final class NcpBridge {
    public interface Sink { void onViolation(Object player, CheckType check, double total, double added, String nativeName); }
    private final ClassLoader loader;
    private final Sink sink;
    private final java.util.function.Consumer<Runnable> dispatch;
    private volatile boolean active;
    private final java.util.Set<String> unknown = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> generation = new java.util.concurrent.ConcurrentHashMap<>();
    /** Drop NCP callbacks captured before this call for the player (used by /wd reset). */
    public void invalidate(java.util.UUID id) { generation.merge(id, 1L, Long::sum); }
    private long gen(Object player) {
        if (!(player instanceof org.bukkit.entity.Player)) return 0L;
        Long g = generation.get(((org.bukkit.entity.Player) player).getUniqueId());
        return g == null ? 0L : g;
    }
    private Object hook;
    private Integer id;
    private Class<?> manager;
    public NcpBridge(ClassLoader loader, Sink sink) { this(loader, sink, Runnable::run); }
    public NcpBridge(ClassLoader loader, Sink sink, java.util.function.Consumer<Runnable> dispatch) { this.loader=loader; this.sink=sink; this.dispatch=dispatch; }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void register() throws ReflectiveOperationException {
        Class<?> type=Class.forName("fr.neatmonster.nocheatplus.checks.CheckType", true, loader);
        Class<?> iface=Class.forName("fr.neatmonster.nocheatplus.hooks.NCPHook", true, loader);
        Class<?> info=Class.forName("fr.neatmonster.nocheatplus.checks.access.IViolationInfo", true, loader);
        manager=Class.forName("fr.neatmonster.nocheatplus.hooks.NCPHookManager", true, loader);
        final Method total=info.getMethod("getTotalVl"), added=info.getMethod("getAddedVl");
        hook=Proxy.newProxyInstance(loader,new Class<?>[]{iface},(proxy, method, args)-> {
            String name=method.getName();
            if(name.equals("getHookName")) return "Watchdog";
            if(name.equals("getHookVersion")) return "1.0";
            if(name.equals("hashCode")) return System.identityHashCode(proxy);
            if(name.equals("equals")) return proxy==args[0];
            if(name.equals("toString")) return "Watchdog NCP hook";
            if(name.equals("onCheckFailure")) {
                try {
                    String check = ((Enum<?>)args[0]).name();
                    CheckType mapped;
                    try { mapped=CheckType.valueOf(check.equals("NET_KEEPALIVEFREQUENCY") ? "NET_KEEPALIVE" : check); }
                    catch (IllegalArgumentException unmapped) {
                        mapped=CheckType.NCP_UNMAPPED;
                        if (unknown.add(check)) Logger.getLogger("Minecraft").warning("[Watchdog] NCP check " + check + " uses generic fallback; NCP command actions are disabled; automatic punishment requires an explicit ncp-unmapped-ban-threshold.");
                    }
                    final Object player=args[1];
                    final CheckType target=mapped;
                    final double vl=((Number)total.invoke(args[2])).doubleValue();
                    final double delta=((Number)added.invoke(args[2])).doubleValue();
                    final long captured=gen(player);
                    dispatch.accept(() -> { if (active && gen(player)==captured) sink.onViolation(player,target,vl,delta,check); });
                } catch (Throwable failure) {
                    Logger.getLogger("Minecraft").warning("[Watchdog] NCP violation forwarding failed: " + failure);
                }
                // false retains NCP cancel/setback; its command actions are disabled at creation.
                return false;
            }
            throw new UnsupportedOperationException(name);
        });
        Object all=Enum.valueOf((Class<? extends Enum>)type.asSubclass(Enum.class),"ALL");
        active=true;
        id=(Integer)manager.getMethod("addHook",type,iface).invoke(null,all,hook);
    }
    public void unregister() throws ReflectiveOperationException {
        active=false;
        if(id!=null) { manager.getMethod("removeHook",Integer.class).invoke(null,id); id=null; hook=null; }
    }
}

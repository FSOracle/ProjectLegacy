package net.h2020.watchdog.ncp;
import fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy;
import org.bukkit.entity.Player;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
/** Suppress NCP direct automatic actions; hand the request to the main thread. */
public final class HostDirectActionPolicy implements AutomaticActionPolicy.Policy {
    private final Consumer<Runnable> dispatch;
    private final BiConsumer<Player, AutomaticActionPolicy.Reason> handler;
    private volatile boolean active = true;
    public HostDirectActionPolicy(Consumer<Runnable> dispatch, BiConsumer<Player, AutomaticActionPolicy.Reason> handler) {
        this.dispatch=dispatch; this.handler=handler;
    }
    @Override public boolean allow(Player player, AutomaticActionPolicy.Reason reason) {
        try {
            if (active) dispatch.accept(() -> {
                if (!active) return;
                try { handler.accept(player, reason); }
                catch (Throwable t) { Logger.getLogger("Minecraft").warning("[Watchdog] direct request failed closed: " + t); }
            });
        } catch (Throwable t) { Logger.getLogger("Minecraft").warning("[Watchdog] direct request failed closed: " + t); }
        return false;
    }
    public void stop() { active=false; }
}

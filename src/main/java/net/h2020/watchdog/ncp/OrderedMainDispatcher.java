package net.h2020.watchdog.ncp;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
/** Serialize NCP notifications on Bukkit main thread, including across tick boundaries. */
public final class OrderedMainDispatcher implements Consumer<Runnable> {
    private final Consumer<Runnable> schedule;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    public OrderedMainDispatcher(Consumer<Runnable> schedule) { this.schedule=schedule; }
    @Override public void accept(Runnable task) {
        queue.add(task);
        requestDrain();
    }
    private void requestDrain() {
        if (!scheduled.compareAndSet(false, true)) return;
        try { schedule.accept(this::drain); }
        catch (Throwable t) { scheduled.set(false); queue.clear(); throw t; }
    }
    private void drain() {
        try {
            for (int i=0; i<1024; i++) {
                Runnable task=queue.poll();
                if (task==null) break;
                try { task.run(); }
                catch (Throwable t) { Logger.getLogger("Minecraft").warning("[Watchdog] queued NCP notification failed: "+t); }
            }
        } finally {
            scheduled.set(false);
            if (!queue.isEmpty()) requestDrain();
        }
    }
}

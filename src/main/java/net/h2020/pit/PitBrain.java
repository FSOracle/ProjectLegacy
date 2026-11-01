package net.h2020.pit;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotBrain;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.motor.Motor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Dedicated Pit brain. Not a BaseBrain, so global lobby reconciliation leaves it alone. */
final class PitBrain implements BotBrain {
    private final PitBotManager owner;
    private BotContext ctx;
    private Player selected;
    PitBrain(PitBotManager owner) { this.owner = owner; }
    public void start(Bot b) {
        ctx = new BotContext(b);
        ctx.combat.allowPearl = false;
        ctx.combat.allowFireball = false;
        ctx.combat.allowThrowables = false;
        ctx.combat.allowRod = false;
        ctx.combat.allowBow = false;
        ctx.combat.allowGapple = false;
        ctx.combat.items.allowPotions = false;
        ctx.combat.items.allowLava = false;
        ctx.combat.items.allowFire = false;
    }
    public void tick(Bot b) {
        if (ctx == null || b.isRemoved()) return;
        ctx.tick++;
        ctx.motor.beginTick();
        Location pos = ctx.pos();
        if (!ctx.player.isOnline() || ctx.player.isDead() || ctx.player.getHealth() <= 0) {
            ctx.motor.halt(); return;
        }
        if (PitLayout.isSafe(pos.getX(), pos.getY(), pos.getZ())) {
            selected = null;
            ctx.combat.disengage();
            walk(new Location(pos.getWorld(), 0.5, pos.getY(), 0.5));
        } else {
            if (selected != null && (selected == ctx.player || !owner.validTarget(selected)
                    || selected.getWorld() != pos.getWorld() || selected.getLocation().distanceSquared(pos) > 1024)) {
                selected = null;
                ctx.combat.disengage();
            }
            // Staggered re-evaluation limits ray casts and avoids constant target thrashing.
            if (ctx.tick % 10 == 0) {
                Player best = null;
                double bestScore = 1024;
                for (Player p : pos.getWorld().getPlayers()) {
                    if (p == ctx.player || !owner.validTarget(p)) continue;
                    double distance = p.getLocation().distanceSquared(pos);
                    if (distance > 1024) continue;
                    // A modest preference for the current opponent avoids oscillating.
                    double score = distance * (p == selected ? 0.81 : 1.0);
                    if (score < bestScore && ctx.player.hasLineOfSight(p)) {
                        bestScore = score;
                        best = p;
                    }
                }
                selected = best;
            }
            if (selected == null) ctx.combat.disengage();
            else {
                ctx.perception.tick();
                if (ctx.combat.target() != selected) ctx.combat.engage(selected);
                ctx.combat.tick();
                if (!ctx.combat.engaged()) walk(selected.getLocation());
            }
        }
        ctx.motor.flush();
    }
    private void walk(Location target) {
        Location pos = ctx.pos();
        ctx.motor.aimPoint(Motor.P_TASK, target.clone().add(0, 1.4, 0), AimIntent.NAVIGATE, 4.0);
        ctx.motor.moveToward(target.getX() - pos.getX(), target.getZ() - pos.getZ());
        ctx.motor.sprint(true);
        ctx.motor.jump(ctx.c.collidedHorizontally());
    }
    public void stop(Bot b) {
        if (ctx == null) return;
        ctx.combat.disengage();
        ctx.nav.stop();
        ctx.motor.halt();
    }
}

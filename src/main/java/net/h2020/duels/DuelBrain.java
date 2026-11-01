package net.h2020.duels;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameType;
import net.h2020.bots.ai.brain.GameBrain;
import net.h2020.bots.ai.combat.Combat;
import net.h2020.bots.ai.motor.Motor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Bot opponent for Duels. Melee modes reuse the shared combat AI with mode-appropriate options (Boxing: sword only;
 * NoDebuff: splash heals; Classic: rod/bow/flint). Bridge pushes for the enemy goal along the deck and turns to
 * fight when the opponent threatens its own goal or hits it at close range.
 */
public final class DuelBrain extends GameBrain {
    private final DuelGame g;
    private long lastHurtTick = -1000;
    private boolean configured, defending;

    DuelBrain(DuelGame g) { super(g); this.g = g; }

    private void configure() {
        if (configured) return;
        configured = true;
        Combat c = ctx.combat;
        c.leash = 100;
        c.allowPearl = false;
        c.allowFireball = false;
        c.allowThrowables = false;
        switch (g.type) {
            case DUELS_BOXING:
                c.allowRod = false; c.allowBow = false; c.allowGapple = false;
                c.items.allowPotions = false; c.items.allowLava = false; c.items.allowFire = false;
                break;
            case DUELS_NODEBUFF:
                c.allowRod = false; c.allowBow = false; c.allowGapple = false;
                c.items.allowPotions = true; c.items.allowLava = false; c.items.allowFire = false;
                break;
            case DUELS_BRIDGE:
                c.allowRod = false; c.allowBow = true; c.allowGapple = true;
                c.items.allowLava = false; c.items.allowFire = false;
                break;
            default:
                c.allowRod = true; c.allowBow = true; c.allowGapple = false;
                c.items.allowLava = false; c.items.allowFire = true;
        }
    }

    @Override protected void onGameStart() { configure(); }

    @Override
    public void onHurt(Player attacker, double damage) {
        super.onHurt(attacker, damage);
        if (ctx != null) lastHurtTick = ctx.tick;
    }

    @Override
    protected void think() {
        pollState();
        configure();
        if (g.state != GameState.PLAYING || g.paused) { if (ctx.combat.engaged()) ctx.combat.disengage(); return; }
        Player opp = g.opponent(ctx.player);
        if (opp == null || !opp.isOnline() || opp.getWorld() != ctx.player.getWorld()) return;
        if (g.type != GameType.DUELS_BRIDGE) { fight(opp); return; }
        int side = g.side(ctx.player);
        if (side < 0) return;
        Location me = ctx.pos();
        Location myGoal = g.arena.goals[side].center(g.world);
        double oppToMyGoal = hd(opp.getLocation(), myGoal), meToMyGoal = hd(me, myGoal);
        boolean defend = oppToMyGoal < 12 && oppToMyGoal < meToMyGoal + 4;
        boolean threatened = ctx.tick - lastHurtTick < 60 && me.distance(opp.getLocation()) < 5;
        if (defend != defending) { defending = defend; if (defend) g.log("DUELS_BOT_DEFEND game=" + g.id() + " bot=" + ctx.player.getName() + " oppToGoal=" + Math.round(oppToMyGoal)); }
        if (defend || threatened) { fight(opp); return; }
        if (ctx.combat.engaged()) ctx.combat.disengage();
        Location enemy = g.arena.goals[1 - side].center(g.world);
        double gx = enemy.getX();
        Location target;
        if (Math.abs(me.getX() - gx) > 3.5) target = new Location(g.world, gx - Math.signum(gx) * 3.0, DuelArena.FLOOR + 1, 1.5);
        else target = new Location(g.world, gx, DuelArena.FLOOR + 1, enemy.getZ());
        walk(target);
    }

    private void fight(Player opp) {
        if (ctx.combat.target() != opp) ctx.combat.engage(opp);
        ctx.combat.tick();
        if (!ctx.combat.engaged()) walk(opp.getLocation());
    }

    private void walk(Location t) {
        Location me = ctx.pos();
        ctx.motor.aimPoint(Motor.P_TASK, t.clone().add(0, 1.4, 0), AimIntent.NAVIGATE, 4.0);
        ctx.motor.moveToward(t.getX() - me.getX(), t.getZ() - me.getZ());
        ctx.motor.sprint(true);
        ctx.motor.jump(ctx.c.collidedHorizontally());
    }

    private static double hd(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override public String toString() { return "DuelBrain[" + g.type.id() + "]"; }
}

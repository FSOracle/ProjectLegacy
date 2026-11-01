package net.h2020.pit;

/** Base kill rewards (no perks, bounties or streak bonuses yet). */
public final class PitRewards {
    private PitRewards() {}
    public static final long KILL_GOLD = 10;
    public static final long KILL_XP = 5;
    public static final int MAX_GOLDEN_APPLES = 2;
    /** A hit this recent makes the attacker the killer of a void/fall death. */
    public static final long ASSIST_WINDOW_MS = 15000;
}

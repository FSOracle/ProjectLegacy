package net.h2020.duels.rules;

/** Boxing: first side to {@code target} valid hits wins. Damage and deaths never decide the match. */
public final class BoxingScore {
    private final int target;
    private final int[] hits = new int[2];
    private int winner = -1;

    public BoxingScore(int target) { this.target = target; }

    /** Count one valid hit for side 0/1. Returns true if this hit won the match. */
    public synchronized boolean hit(int side) {
        if (winner >= 0 || side < 0 || side > 1) return false;
        if (++hits[side] >= target) { winner = side; return true; }
        return false;
    }

    public synchronized int hits(int side) { return hits[side]; }
    public synchronized int winner() { return winner; }
    public int target() { return target; }
}

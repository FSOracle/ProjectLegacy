package net.h2020.duels.rules;

/** Bridge scoring: a team scores by entering the opposing goal; play pauses until resume(); first to target wins. */
public final class BridgeScore {
    public enum Result { REJECTED, POINT, WIN }

    private final int target;
    private final int[] points = new int[2];
    private boolean paused;
    private int winner = -1;

    public BridgeScore(int target) { this.target = target; }

    /** team = scorer side (0/1), goal = side whose goal was entered. */
    public synchronized Result score(int team, int goal) {
        if (winner >= 0 || paused) return Result.REJECTED;
        if (team < 0 || team > 1 || goal < 0 || goal > 1 || team == goal) return Result.REJECTED;
        points[team]++;
        paused = true;
        if (points[team] >= target) { winner = team; return Result.WIN; }
        return Result.POINT;
    }

    /** Called after players and placed blocks were reset. */
    public synchronized void resume() { if (winner < 0) paused = false; }

    public synchronized boolean paused() { return paused; }
    public synchronized int points(int team) { return points[team]; }
    public synchronized int winner() { return winner; }
    public int target() { return target; }
}

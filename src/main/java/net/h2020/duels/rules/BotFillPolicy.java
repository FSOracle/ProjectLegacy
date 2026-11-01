package net.h2020.duels.rules;

/**
 * When a waiting Duels game may receive a bot opponent: exactly one human is waiting, that human accepts bots for
 * this mode, there is room and no bot yet, and the human has waited at least the configured threshold.
 */
public final class BotFillPolicy {
    private BotFillPolicy() {}

    public static boolean shouldAddBot(boolean humanAccepts, int humans, int bots, int maxPlayers, long waitedMs, long thresholdMs) {
        if (!humanAccepts) return false;
        if (humans != 1 || bots != 0) return false;
        if (humans + bots >= maxPlayers) return false;
        return waitedMs >= thresholdMs;
    }
}

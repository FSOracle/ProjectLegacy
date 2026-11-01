package net.h2020.watchdog.ncp;
/** Automatic actions are observational for humans unless explicitly opted in. */
public final class PunishmentPolicy {
    private PunishmentPolicy() {}
    public static boolean allowUnmapped(double total, double threshold, boolean bot, boolean banHumans) {
        return Double.isFinite(total) && Double.isFinite(threshold) && threshold > 0 && total >= threshold && allowAutomatic(bot, banHumans);
    }
    public static boolean allowAutomatic(boolean bot, boolean banHumans) { return bot || banHumans; }
}

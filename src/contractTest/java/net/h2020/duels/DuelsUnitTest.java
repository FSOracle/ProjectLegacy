package net.h2020.duels;

import net.h2020.api.game.GameType;
import net.h2020.core.prefs.BotMatchPrefs;
import net.h2020.duels.rules.BotFillPolicy;
import net.h2020.duels.rules.BoxingScore;
import net.h2020.duels.rules.BridgeScore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Bukkit-free unit checks for Duels rules and the per-mode bot preference store. Run by the duelsUnitTest task. */
public final class DuelsUnitTest {
    private static int passed;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
        System.out.println("PASS " + what);
    }

    public static void main(String[] args) throws Exception {
        // ---- ids
        check(GameType.byId("duels_classic_duel") == GameType.DUELS_CLASSIC, "classic id");
        check(GameType.byId("duels_boxing_duel") == GameType.DUELS_BOXING, "boxing id");
        check(GameType.byId("duels_potion_duel") == GameType.DUELS_NODEBUFF, "nodebuff id");
        check(GameType.byId("duels_bridge_duel") == GameType.DUELS_BRIDGE, "bridge id");
        for (GameType t : new GameType[]{GameType.DUELS_CLASSIC, GameType.DUELS_BOXING, GameType.DUELS_NODEBUFF, GameType.DUELS_BRIDGE})
            check(t.maxPlayers() == 2 && t.family.name().equals("DUELS"), t + " is a 1v1 in the DUELS family");

        // ---- boxing: first to 100 valid hits, nothing else ends it
        BoxingScore box = new BoxingScore(100);
        for (int i = 0; i < 99; i++) check(!box.hit(0) || false, "hit " + (i + 1) + " is not yet a win");
        check(box.hits(0) == 99 && box.winner() == -1, "99 hits, no winner");
        check(!box.hit(1) && box.hits(1) == 1, "opponent hit counted separately");
        check(box.hit(0) && box.winner() == 0, "100th hit wins");
        check(!box.hit(1) && box.hits(1) == 1, "no hits counted after the win");

        // ---- bridge: score in the opposing goal, 5 to win, pause between rounds
        BridgeScore br = new BridgeScore(5);
        check(br.score(0, 0) == BridgeScore.Result.REJECTED, "own goal never scores");
        check(br.score(0, 1) == BridgeScore.Result.POINT && br.points(0) == 1, "point in opponent goal");
        check(br.paused(), "goal pauses play");
        check(br.score(1, 0) == BridgeScore.Result.REJECTED && br.points(1) == 0, "no score while paused");
        br.resume();
        check(!br.paused(), "resume after reset");
        for (int i = 2; i <= 4; i++) { check(br.score(0, 1) == BridgeScore.Result.POINT, "point " + i); br.resume(); }
        check(br.score(1, 0) == BridgeScore.Result.POINT && br.points(1) == 1, "other side can score");
        br.resume();
        check(br.score(0, 1) == BridgeScore.Result.WIN && br.winner() == 0, "fifth point wins");
        br.resume();
        check(br.score(1, 0) == BridgeScore.Result.REJECTED, "finished game rejects scores");

        // ---- bot fill policy
        long t = 10_000;
        check(!BotFillPolicy.shouldAddBot(false, 1, 0, 2, 999_999, t), "off never gets a bot");
        check(!BotFillPolicy.shouldAddBot(true, 1, 0, 2, 9_999, t), "on waits for the threshold");
        check(BotFillPolicy.shouldAddBot(true, 1, 0, 2, 10_000, t), "on gets a bot after the threshold");
        check(!BotFillPolicy.shouldAddBot(true, 2, 0, 2, 99_999, t), "two humans never get a bot");
        check(!BotFillPolicy.shouldAddBot(true, 0, 0, 2, 99_999, t), "no bot-only game");
        check(!BotFillPolicy.shouldAddBot(true, 1, 1, 2, 99_999, t), "at most one bot");

        // ---- preferences: default on, per mode, immediate, persistent
        Path dir = Files.createTempDirectory("botprefs");
        Path file = dir.resolve("bot-match-prefs.txt");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        BotMatchPrefs prefs = new BotMatchPrefs(file);
        check(prefs.accepts(a, GameType.DUELS_CLASSIC) && prefs.accepts(a, GameType.DUELS_BRIDGE), "default on");
        try {
            prefs.set(a, GameType.DUELS_CLASSIC, false);
        } catch (IllegalStateException failure) {
            // The production store deliberately fails closed when directory fsync is unavailable.
            if (!System.getProperty("os.name").startsWith("Windows")
                    || !(failure.getCause() instanceof java.nio.file.AccessDeniedException)
                    || !dir.toString().equals(((java.nio.file.AccessDeniedException) failure.getCause()).getFile())) throw failure;
            check(!prefs.available(), "unsupported directory fsync marks preference state unavailable");
            check(!prefs.accepts(a, GameType.DUELS_CLASSIC), "uncertain commit disables bot matching");
            boolean refused = false;
            try { prefs.set(a, GameType.DUELS_CLASSIC, true); }
            catch (IllegalStateException expected) { refused = true; }
            check(refused, "uncertain commit refuses subsequent changes");
            System.out.println("SKIP preference restart checks: Windows directory fsync unavailable; run on Linux before deployment.");
            System.out.println("DUELS_UNIT_OK " + passed + " (persistence checks skipped)");
            return;
        }
        check(!prefs.accepts(a, GameType.DUELS_CLASSIC), "off applies immediately");
        check(prefs.accepts(a, GameType.DUELS_BOXING) && prefs.accepts(b, GameType.DUELS_CLASSIC), "other modes/players unaffected");
        prefs.set(a, GameType.DUELS_BRIDGE, false);
        prefs.set(a, GameType.DUELS_BRIDGE, true);
        BotMatchPrefs reloaded = new BotMatchPrefs(file);
        check(!reloaded.accepts(a, GameType.DUELS_CLASSIC), "off survives reload");
        check(reloaded.accepts(a, GameType.DUELS_BRIDGE), "toggled back on survives reload");
        check(reloaded.accepts(a, GameType.BEDWARS_SOLO), "generic GameType key, other families default on");
        Files.write(file, "garbage line\nnot-a-uuid DUELS_CLASSIC off\n".getBytes("UTF-8"));
        check(!new BotMatchPrefs(file).accepts(a, GameType.DUELS_CLASSIC), "malformed lines fail closed");
        System.out.println("DUELS_UNIT_OK " + passed);
    }
}

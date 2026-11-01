package net.h2020.api.game;

/** An unstarted game may release a bot slot for a queued human on the server thread. */
public interface HumanPriorityGame {
    boolean releaseBotForHuman();
}

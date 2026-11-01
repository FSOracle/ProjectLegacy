package net.h2020.social.api;

import java.util.List;
import java.util.UUID;

/** Thread-safe immutable snapshots; no party returns a singleton containing player. */
public interface PartyService {
    /** Leader is first; returned list is immutable and is a point-in-time snapshot. */
    List<UUID> membersOf(UUID player);
    /** True only for the leader of an existing party, including an invite-pending party. */
    boolean isLeader(UUID player);
}

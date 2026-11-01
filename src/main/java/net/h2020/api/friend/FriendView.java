package net.h2020.api.friend;

import java.util.Set;
import java.util.UUID;

/** Read-only UUID snapshots for UI integration. Names are intentionally not identities. */
public interface FriendView {
 boolean areFriends(UUID first, UUID second);
 Set<UUID> friendsOf(UUID player);
 /** Includes only requests unexpired at the supplied epoch millisecond timestamp. */
 Set<UUID> requestsFor(UUID player, long nowMillis);
}

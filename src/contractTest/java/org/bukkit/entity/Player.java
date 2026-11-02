package org.bukkit.entity;
public interface Player {
    default java.util.UUID getUniqueId() { return new java.util.UUID(0L, 1L); }
    default String getName() { return "TestPlayer"; }
}

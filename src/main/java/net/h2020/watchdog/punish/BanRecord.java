package net.h2020.watchdog.punish;

import java.util.UUID;

/** One persisted temporary ban (bans.yml). */
public final class BanRecord {
    public final UUID uuid;
    public final String name;
    public final String banId;
    public final String reasonCode;
    public final long created;
    public final long expires;
    /** Issued by an operator (/wd ban); enforced even when ban-humans is false. */
    public final boolean manual;

    public BanRecord(UUID uuid, String name, String banId, String reasonCode, long created, long expires) {
        this(uuid, name, banId, reasonCode, created, expires, false);
    }

    public BanRecord(UUID uuid, String name, String banId, String reasonCode, long created, long expires, boolean manual) {
        this.uuid = uuid; this.name = name; this.banId = banId; this.reasonCode = reasonCode;
        this.created = created; this.expires = expires; this.manual = manual;
    }

    public boolean active(long now) { return expires > now; }
}

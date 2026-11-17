package dev.staffcore.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Punishment {
    public static final class TaskProgress {
        public TaskStatus status = TaskStatus.OPEN;
        public long timeLeftMs;
        /** Wrong quiz answers so far. */
        public int attempts;

        public TaskProgress(TaskStatus status, long timeLeftMs) {
            this.status = status;
            this.timeLeftMs = timeLeftMs;
        }
    }

    /** Short code the player can quote, e.g. in an appeal. Unique across all punishments. */
    public String banId;
    /** IP bans only: fingerprint of the banned address (never the address itself). */
    public String ipHash;
    /** PLAYBAN, BAN, SPECBAN, MUTE or WARN */
    public String type = "PLAYBAN";
    /** Category id, or null for a custom punishment. */
    public String category;
    /** Bans only: when the ban ends, 0 = permanent. */
    public long expiresAt;
    /** Spectator bans only: game mode and place before the player was put on hold, kept so a crash cannot lose them. */
    public String prevMode;
    public String prevLocation;
    /** PlayBans: the game mode the player had before, so it can be given back. */
    public String prevGameMode;
    /** PlayBans with inventory-mode STORE: the player's inventory as YAML, given back at the end. */
    public String storedInventory;
    /** inventory-mode CLEAR: already done, so it happens exactly once even if the player was offline. */
    public boolean inventoryCleared;
    public UUID uuid;
    public String name;
    public String reason;
    public String staff;
    public long createdAt;
    public long originalMs;
    public long remainingMs;
    public long lastUpdate;
    public UUID victim;
    public String victimName;
    public final Map<String, TaskProgress> tasks = new LinkedHashMap<>();
    // only set once the punishment is over
    public long endedAt;
    public String endReason;

    public int tasksDone() {
        int n = 0;
        for (TaskProgress t : tasks.values()) if (t.status == TaskStatus.DONE) n++;
        return n;
    }
}

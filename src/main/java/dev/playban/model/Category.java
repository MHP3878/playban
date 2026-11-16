package dev.playban.model;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** A kind of offense with its own ladder of punishments: first time, second time, ... */
public final class Category {
    public static final class Step {
        /** WARN, PLAYBAN or BAN */
        public String type = "PLAYBAN";
        /** For BAN, 0 means permanent. Ignored for WARN. */
        public long durationMs = 3_600_000L;

        public Step() {}

        public Step(String type, long durationMs) {
            this.type = type;
            this.durationMs = durationMs;
        }
    }

    public String id;
    public String name;
    public Material icon = Material.PAPER;
    /** Offenses older than this no longer count for the ladder. 0 = they always count. */
    public long expireMs = 0;
    public final List<Step> steps = new ArrayList<>();
}

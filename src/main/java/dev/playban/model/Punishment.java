package dev.playban.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Punishment {
    public static final class TaskProgress {
        public TaskStatus status = TaskStatus.OPEN;
        public long timeLeftMs;

        public TaskProgress(TaskStatus status, long timeLeftMs) {
            this.status = status;
            this.timeLeftMs = timeLeftMs;
        }
    }

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

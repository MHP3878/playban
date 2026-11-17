package dev.playban.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A case the team works on: who opened it, who is involved, everything that was said and how it ended. */
public final class Ticket {
    public enum Status { OPEN, IN_PROGRESS, CLOSED, DECLINED }

    public static final class Participant {
        public UUID uuid;
        public String name;
        /** Decision at closing time: null = not decided, "INVOLVED", or "category:<id>". */
        public String verdict;
        /** What actually happened when the ticket was closed, as readable text. */
        public String outcome;
    }

    public static final class Entry {
        public long time;
        /** CHAT (a participant), STAFF (the team), NOTE (team only) or SYSTEM */
        public String kind;
        public String author;
        public String text;
        public boolean starred;
        /** STAFF only: the single participant this went to, null = everyone in the ticket. */
        public String to;
    }

    public int id;
    public UUID author;
    public String authorName;
    public String reason;
    public String text;
    public long createdAt;
    public Status status = Status.OPEN;
    public String handler;
    public UUID handlerId;
    public long acceptedAt;
    public long closedAt;
    public long lastReminder;
    /** While paused, participants are back in the normal chat. */
    public boolean paused;
    /** true = participants read each other, false = everyone only talks to the team. */
    public boolean shared = true;
    public final List<Participant> participants = new ArrayList<>();
    public final List<Entry> log = new ArrayList<>();

    public Participant participant(UUID id) {
        for (Participant p : participants) if (p.uuid.equals(id)) return p;
        return null;
    }

    public boolean active() {
        return status == Status.OPEN || status == Status.IN_PROGRESS;
    }
}

package dev.playban.model;

import java.util.UUID;

public final class Ticket {
    public String id;
    public UUID author;
    public String authorName;
    public String text;
    public long createdAt;
}

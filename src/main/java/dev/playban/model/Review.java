package dev.playban.model;

import java.util.UUID;

/** A message written by a punished player that waits for a staff decision. */
public final class Review {
    public String id;
    public UUID author;
    public String authorName;
    public String taskId;
    public UUID target;
    public String targetName;
    public String text;
    public long createdAt;
}

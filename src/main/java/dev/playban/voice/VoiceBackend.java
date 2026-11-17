package dev.playban.voice;

import java.util.List;
import java.util.UUID;

/** What PlayBan needs from a voice chat. The real one talks to Simple Voice Chat; tests use a stand-in. */
public interface VoiceBackend {
    record GroupInfo(UUID id, String name, boolean password, boolean hidden, boolean persistent, List<UUID> members) {}

    boolean available();

    List<GroupInfo> groups();

    /** The group a player is in, or null. */
    UUID groupOf(UUID player);

    /** Moves a player into a group, or out of their group when group is null. */
    boolean setGroup(UUID player, UUID group);

    /** Creates a group and returns its id, or null when that failed. */
    UUID createGroup(String name, String password, boolean hidden);

    /** Sets or removes (null) the password of an existing group. */
    boolean setPassword(UUID group, String password);

    /** Removes a group that has no members left. */
    void removeGroup(UUID group);

    /** Asks the voice chat to re-check whether this player is muted, deafened or hidden. */
    void refresh(UUID player);
}

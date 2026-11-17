package de.maxhenkel.voicechat.api;

import java.util.Collection;
import java.util.UUID;

public interface VoicechatServerApi extends VoicechatApi {
    VoicechatConnection getConnectionOf(UUID playerUuid);

    Group.Builder groupBuilder();

    boolean removeGroup(UUID groupId);

    Group getGroup(UUID groupId);

    Collection<Group> getGroups();
}

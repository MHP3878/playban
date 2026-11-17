package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;

public interface GroupEvent extends ServerEvent {
    Group getGroup();

    VoicechatConnection getConnection();
}

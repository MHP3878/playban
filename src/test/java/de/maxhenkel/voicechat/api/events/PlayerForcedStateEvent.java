package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.VoicechatConnection;

public interface PlayerForcedStateEvent extends ServerEvent {
    VoicechatConnection getConnection();

    boolean isForceMuted();

    boolean isForceDisabled();

    void forceMute();

    void forceDisable();
}

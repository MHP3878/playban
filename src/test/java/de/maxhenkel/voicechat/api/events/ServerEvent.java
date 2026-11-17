package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.VoicechatServerApi;

public interface ServerEvent extends Event {
    VoicechatServerApi getVoicechat();
}

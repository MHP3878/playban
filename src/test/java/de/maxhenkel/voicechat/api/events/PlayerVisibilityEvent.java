package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.ServerPlayer;

public interface PlayerVisibilityEvent extends ServerEvent {
    ServerPlayer getObserver();

    ServerPlayer getPlayer();

    boolean isHidden();

    void hide();
}

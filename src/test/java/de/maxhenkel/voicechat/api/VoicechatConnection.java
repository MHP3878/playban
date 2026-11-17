package de.maxhenkel.voicechat.api;

public interface VoicechatConnection {
    Group getGroup();

    void setGroup(Group group);

    void evaluateForcedState();

    void evaluateVisibility();

    ServerPlayer getPlayer();
}

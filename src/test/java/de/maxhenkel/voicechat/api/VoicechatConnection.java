package de.maxhenkel.voicechat.api;

/** As in Simple Voice Chat 2.6: the voice chat decides mute and visibility itself; plugins filter packets. */
public interface VoicechatConnection {
    Group getGroup();

    boolean isInGroup();

    void setGroup(Group group);

    boolean isConnected();

    boolean isDisabled();

    boolean isInstalled();

    ServerPlayer getPlayer();
}

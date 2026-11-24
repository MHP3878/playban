package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.packets.Packet;

public interface PacketEvent<T extends Packet> extends ServerEvent {
    T getPacket();

    VoicechatConnection getReceiverConnection();

    VoicechatConnection getSenderConnection();
}

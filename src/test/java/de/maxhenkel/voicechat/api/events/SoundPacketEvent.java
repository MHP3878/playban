package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.packets.Packet;

public interface SoundPacketEvent<T extends Packet> extends PacketEvent<T> {
    String SOURCE_GROUP = "group";
    String SOURCE_PROXIMITY = "proximity";

    String getSource();
}

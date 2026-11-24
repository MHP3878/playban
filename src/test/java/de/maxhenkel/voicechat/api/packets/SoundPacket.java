package de.maxhenkel.voicechat.api.packets;

public interface SoundPacket extends Packet {
    byte[] getOpusEncodedData();
}

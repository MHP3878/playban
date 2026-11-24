package de.maxhenkel.voicechat.api.packets;

public interface MicrophonePacket extends Packet {
    byte[] getOpusEncodedData();
}

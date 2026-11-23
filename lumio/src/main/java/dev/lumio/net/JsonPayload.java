package dev.lumio.net;

import dev.lumio.compat.Compat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/**
 * Every Lumio message is a short JSON text on its own channel. The bytes are the plain UTF-8 text, so a
 * server plugin (Paper's plugin messages) reads and writes them without any extra library.
 */
public record JsonPayload(CustomPacketPayload.Type<JsonPayload> type, String json) implements CustomPacketPayload {
    /** Server to client: "this server speaks Lumio", with a random word to send back. */
    public static final Type<JsonPayload> HELLO = Compat.type("hello");
    /** Client to server: Lumio version and the installed mods (for fair play). */
    public static final Type<JsonPayload> REPORT = Compat.type("report");
    /** Server to client: a request card with two keys (e.g. a TPA request). */
    public static final Type<JsonPayload> PROMPT = Compat.type("prompt");
    /** Client to server: the answer to a request card, when it has no command of its own. */
    public static final Type<JsonPayload> ANSWER = Compat.type("answer");
    /** Server to client: a short notice card. */
    public static final Type<JsonPayload> TOAST = Compat.type("toast");
    /** Server to client: lines for the HUD, e.g. the account balance. */
    public static final Type<JsonPayload> HUD = Compat.type("hud");

    /** Far more than any message needs; protects against a server sending something huge. */
    public static final int MAX_BYTES = 30_000;

    public static StreamCodec<FriendlyByteBuf, JsonPayload> codec(Type<JsonPayload> type) {
        return StreamCodec.of((buf, payload) -> buf.writeBytes(payload.json().getBytes(StandardCharsets.UTF_8)), buf -> {
            int n = Math.min(buf.readableBytes(), MAX_BYTES);
            byte[] bytes = new byte[n];
            buf.readBytes(bytes);
            buf.skipBytes(buf.readableBytes());
            return new JsonPayload(type, new String(bytes, StandardCharsets.UTF_8));
        });
    }
}

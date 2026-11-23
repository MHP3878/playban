package dev.lumio.serverlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lumio.net.Protocol;
import dev.lumio.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A server in the multiplayer list that supports Lumio looks like itself: its colours behind the entry
 * and its tags next to the name. The server puts this into its description as an invisible part
 * (a text without letters whose "insertion" starts with "lumio:"), so normal clients see the usual
 * description and nothing else.
 */
public final class ServerCard {
    private static final String MARK = "lumio:";
    private static final Map<Component, Look> CACHE = new WeakHashMap<>();
    private static final Look NONE = new Look(0, 0, List.of());

    public record Look(int from, int to, List<String> tags) {}

    private ServerCard() {}

    /** What the server asks for, or null for a plain entry. */
    public static Look look(ServerData data) {
        if (data == null || data.motd == null) return null;
        Look l = CACHE.computeIfAbsent(data.motd, ServerCard::read);
        return l == NONE ? null : l;
    }

    private static Look read(Component motd) {
        String raw = find(motd, 0);
        if (raw == null) return NONE;
        try {
            JsonElement el = JsonParser.parseString(raw.substring(MARK.length()));
            if (!el.isJsonObject()) return NONE;
            JsonObject o = el.getAsJsonObject();
            int from = 0xFF7C5CFF, to = 0xFF4FC3F7;
            if (o.has("c") && o.get("c").isJsonArray()) {
                JsonArray c = o.getAsJsonArray("c");
                if (c.size() > 0) from = color(c.get(0), from);
                to = c.size() > 1 ? color(c.get(1), from) : from;
            }
            List<String> tags = new ArrayList<>();
            if (o.has("tags") && o.get("tags").isJsonArray()) {
                for (JsonElement t : o.getAsJsonArray("tags")) {
                    if (tags.size() >= 4 || !t.isJsonPrimitive()) continue;
                    String s = Protocol.clean(t.getAsString(), 16);
                    if (!s.isEmpty()) tags.add(s);
                }
            }
            return new Look(from, to, List.copyOf(tags));
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    private static String find(Component c, int depth) {
        if (depth > 6) return null;
        String ins = c.getStyle().getInsertion();
        if (ins != null && ins.startsWith(MARK) && ins.length() < 2048) return ins;
        for (Component s : c.getSiblings()) {
            String f = find(s, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    private static int color(JsonElement e, int fallback) {
        if (!e.isJsonPrimitive()) return fallback;
        String s = e.getAsString().trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (!s.matches("[0-9a-fA-F]{6}")) return fallback;
        return 0xFF000000 | Integer.parseInt(s, 16);
    }

    // ---------------------------------------------------------------- drawing

    /** Behind the entry: the server's colours, fading to the right, with a bright edge on the left. */
    public static void before(GuiGraphics g, ServerData data, int x, int y, int w, int h, boolean hovered) {
        Look l = look(data);
        if (l == null) return;
        float a = hovered ? 1f : 0.8f;
        int x0 = x - 2, y0 = y - 2, w0 = w + 4, h0 = h + 4;
        Draw.bar(g, x0, y0, w0, h0, Draw.fade(l.from(), 0.42f), Draw.fade(l.to(), 0.06f), a);
        g.fillGradient(x0, y0, x0 + 2, y0 + h0, l.from(), l.to());
        g.fill(x0, y0, x0 + w0, y0 + 1, Draw.fade(l.from(), 0.5f * a));
        g.fill(x0, y0 + h0 - 1, x0 + w0, y0 + h0, Draw.fade(l.to(), 0.3f * a));
    }

    /** On top: the tags between the name and the player count, and a bar under the player count. */
    public static void after(GuiGraphics g, ServerData data, int x, int y, int w, int h) {
        Look l = look(data);
        if (l == null) return;
        var font = Minecraft.getInstance().font;
        int nameEnd = x + 35 + font.width(data.name == null ? "" : data.name) + 6;
        int statusW = data.status == null ? 0 : font.width(data.status);
        int right = x + w - 15 - statusW - 8;
        // tags from the right, as long as there is room
        List<Component> pills = new ArrayList<>();
        int need = 0;
        for (String t : l.tags()) {
            Component c = Draw.bold(t);
            int pw = Draw.width(c) + 8;
            if (right - need - pw < nameEnd) break;
            pills.add(c);
            need += pw + 3;
        }
        int px = right - need + 3;
        for (int i = 0; i < pills.size(); i++) {
            Component c = pills.get(i);
            int pw = Draw.width(c) + 8;
            int col = Draw.mix(l.from(), l.to(), pills.size() == 1 ? 0 : i / (float) (pills.size() - 1));
            Draw.round(g, px, y, pw, 11, Draw.fade(col, 0.85f));
            Draw.text(g, c, px + 4, y + 2, 0xFFFFFFFF);
            px += pw + 3;
        }
        // how full it is, under the player count
        if (data.players != null && data.players.max() > 0 && statusW > 0) {
            int bx = x + w - 15 - statusW, bw = statusW;
            g.fill(bx, y + 10, bx + bw, y + 11, 0x40FFFFFF);
            int done = (int) Math.round(bw * Math.min(1.0, data.players.online() / (double) data.players.max()));
            Draw.bar(g, bx, y + 10, Math.max(1, done), 1, l.from(), l.to(), 1f);
        }
    }
}

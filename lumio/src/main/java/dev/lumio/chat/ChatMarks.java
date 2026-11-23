package dev.lumio.chat;

import dev.lumio.Config;
import dev.lumio.ui.Draw;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marks chat messages that name you (or contain one of your words): the words light up in the theme
 * colour, the line gets a coloured bar with a hint on hover, and a soft sound plays. Your own name in
 * front of your own messages ("Name: hi", "<Name> hi", "Name » hi") does not count.
 */
public final class ChatMarks {
    private static long lastSound;

    private ChatMarks() {}

    /** The marked message and its tag, or null when nothing is to be marked. */
    public static Marked mark(Component message) {
        Config c = Config.get();
        Minecraft mc = Minecraft.getInstance();
        if (message == null) return null;
        List<String> words = new ArrayList<>();
        String self = mc.getUser() == null ? "" : mc.getUser().getName();
        if (c.chatMentions && self != null && self.length() >= 3) words.add(self);
        for (String k : c.chatKeywords) if (k != null && k.trim().length() >= 2) words.add(k.trim());
        if (words.isEmpty()) return null;
        StringBuilder re = new StringBuilder("(?<![\\p{L}\\p{N}_])(");
        for (int i = 0; i < words.size(); i++) re.append(i == 0 ? "" : "|").append(Pattern.quote(words.get(i)));
        re.append(")(?![\\p{L}\\p{N}_])");
        Pattern pattern = Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        String plain = message.getString();
        if (plain.length() > 2000) return null;
        // where the matches are; the sender's own name at the front is left out
        List<int[]> hits = new ArrayList<>();
        Matcher m = pattern.matcher(plain);
        while (m.find()) hits.add(new int[]{m.start(), m.end()});
        if (hits.isEmpty()) return null;
        // your own message ("Name: ...", "<Name> ...") or one about you ("Name joined the game") is not marked
        if (!self.isEmpty() && plain.substring(hits.get(0)[0], hits.get(0)[1]).equalsIgnoreCase(self) && sender(plain, hits.get(0))) {
            return null;
        }
        String first = plain.substring(hits.get(0)[0], hits.get(0)[1]);
        boolean mention = first.equalsIgnoreCase(self);

        // rebuild the message with the same styles, the matches in the theme colour and bold
        MutableComponent out = Component.empty();
        int[] pos = {0};
        int accent = Draw.accent() & 0xFFFFFF;
        message.visit((style, text) -> {
            int start = pos[0], end = start + text.length();
            int at = 0;
            for (int[] h : hits) {
                int hs = Math.max(h[0], start), he = Math.min(h[1], end);
                if (hs >= he) continue;
                if (hs - start > at) out.append(Component.literal(text.substring(at, hs - start)).setStyle(style));
                out.append(Component.literal(text.substring(hs - start, he - start))
                        .setStyle(style.withColor(TextColor.fromRgb(accent)).withBold(true)));
                at = he - start;
            }
            if (at < text.length()) out.append(Component.literal(text.substring(at)).setStyle(style));
            pos[0] = end;
            return Optional.empty();
        }, Style.EMPTY);

        Component hint = mention ? Component.translatable("lumio.chat.mention") : Component.translatable("lumio.chat.keyword", first);
        GuiMessageTag tag = new GuiMessageTag(Draw.accent() & 0xFFFFFF, null, hint, "Lumio");
        if (c.chatSound && c.sounds && System.currentTimeMillis() - lastSound > 1500) {
            lastSound = System.currentTimeMillis();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.8f, 0.45f));
        }
        return new Marked(out, tag);
    }

    /** Whether the name at this place is the sender: at the very front, or right before ":", "»", ">" or "›". */
    static boolean sender(String plain, int[] hit) {
        String before = plain.substring(0, hit[0]).toLowerCase(Locale.ROOT);
        if (before.isBlank()) return true;
        String after = plain.substring(hit[1]);
        String rest = after.stripLeading();
        if (before.endsWith("<") && rest.startsWith(">")) return true;
        rest = rest.replaceFirst("^[\\]\\)]\\s*", "");
        return hit[0] < 48 && !rest.isEmpty() && ":»>›|".indexOf(rest.charAt(0)) >= 0;
    }

    public record Marked(Component message, GuiMessageTag tag) {}
}

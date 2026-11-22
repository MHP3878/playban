package dev.modcheck;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * The questions on the hidden sign and how the answers are judged. No server in here, only logic, so
 * every rule can be tested on its own.
 *
 * <p>How a question works: the sign shows a translation key with an empty fallback, wrapped in a
 * {@code "%s"} text ("Einschub"). A client without the mod gives the fallback back (nothing), a client
 * with the mod its own text, a client that hides the key (old Meteor) the raw key. The wrapping means a
 * filter that only looks at the top level of a text never sees the key. A key binding works the same
 * way: without the mod the name comes back as it is, with the mod the key it is bound to.
 *
 * <p>Why a sign can be trusted: every sign carries its own controls at random places.
 * <ul>
 *   <li>a <b>positive</b> control, a text every client knows (picked at random, e.g. {@code key.jump}):
 *       it must come back filled in;</li>
 *   <li>a <b>negative</b> control, a made-up key that looks like a real one, with a fallback that
 *       swaps two random words ({@code "%2$s-%1$s"}): it must come back exactly as the game itself
 *       builds it. This checks the client's text engine and ties the answer to this very sign;</li>
 *   <li>on the first sign also the same pair for key bindings.</li>
 * </ul>
 * A sign whose controls fail says nothing about mods: the client changes texts on its own.
 */
public final class Probe {
    public enum Kind { MOD, POSITIVE, NEGATIVE }

    /** One question: the text sent, the answer a client without the mod gives, the mod, the key, what it is for. */
    public record Ask(Component text, String expect, String mod, String key, Kind kind) {}

    /** What one sign told: whether it can be trusted, why not, and every answer. */
    public record Verdict(boolean trusted, String problem, Map<Ask, String> answers) {}

    public static final String SEP = "|";
    public static final int PER_LINE = 3;
    public static final int LINES = 4;
    public static final int PER_SIGN = PER_LINE * LINES;

    /** Texts every client has, in every language. */
    static final List<String> VANILLA_KEYS = List.of("key.jump", "key.sneak", "key.inventory", "key.drop", "key.chat", "key.attack",
            "key.use", "key.forward", "key.back", "key.left", "key.right", "key.sprint", "key.screenshot", "key.playerlist");
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String[] JOINERS = {"-", "+", "~", ":", "#", "="};
    private static final String[] PREFIX = {"key", "gui", "text", "options", "menu", "config", "screen", "hud"};
    private static final String[] SUFFIX = {"title", "toggle", "name", "desc", "open", "label", "tooltip", "button"};

    private Probe() {}

    private static Component wrap(Component inner) {
        return Component.translatable().key("%s").arguments(inner).build();
    }

    /** A mod question: a text key, or "bind:<name>" for a key binding; always wrapped. */
    public static Ask mod(String key, String mod) {
        if (key.startsWith("bind:")) {
            String id = key.substring(5);
            return new Ask(wrap(Component.keybind(id)), id, mod, key, Kind.MOD);
        }
        return new Ask(wrap(Component.translatable().key(key).fallback("").build()), "", mod, key, Kind.MOD);
    }

    /** A text every client knows: must come back filled in, and not as the raw key. */
    public static Ask positive(String key, boolean nested, boolean bind) {
        Component c = bind ? Component.keybind(key) : Component.translatable(key);
        return new Ask(nested ? wrap(c) : c, key, "", bind ? "bind:" + key : key, Kind.POSITIVE);
    }

    /** A made-up key whose fallback swaps two words: must come back exactly as "b<join>a". */
    public static Ask negative(String key, String a, String b, String join, boolean nested) {
        Component c = Component.translatable().key(key).fallback("%2$s" + join + "%1$s")
                .arguments(Component.text(a), Component.text(b)).build();
        return new Ask(nested ? wrap(c) : c, b + join + a, "", key, Kind.NEGATIVE);
    }

    /** A made-up key binding: must come back as its own name. */
    public static Ask negativeBind(String key, boolean nested) {
        Component c = Component.keybind(key);
        return new Ask(nested ? wrap(c) : c, key, "", "bind:" + key, Kind.NEGATIVE);
    }

    private static String word(Random r, int min, int max) {
        int n = min + r.nextInt(max - min + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(LETTERS.charAt(r.nextInt(LETTERS.length())));
        return b.toString();
    }

    /** A key that looks like one of a real mod, but no mod has it. */
    static String madeUpKey(Random r) {
        return PREFIX[r.nextInt(PREFIX.length)] + "." + word(r, 5, 9) + "." + SUFFIX[r.nextInt(SUFFIX.length)];
    }

    public static Ask randomPositive(Random r, boolean bind) {
        return positive(VANILLA_KEYS.get(r.nextInt(VANILLA_KEYS.size())), r.nextBoolean(), bind);
    }

    public static Ask randomNegative(Random r) {
        return negative(madeUpKey(r), word(r, 3, 4), word(r, 3, 4), JOINERS[r.nextInt(JOINERS.length)], r.nextBoolean());
    }

    public static Ask randomNegativeBind(Random r) {
        return negativeBind(madeUpKey(r), r.nextBoolean());
    }

    /** The controls of one sign. */
    public static List<Ask> controls(Random r, boolean first) {
        List<Ask> c = new ArrayList<>(List.of(randomPositive(r, false), randomNegative(r)));
        if (first) {
            c.add(randomPositive(r, true));
            c.add(randomNegativeBind(r));
        }
        return c;
    }

    /** How many mod questions fit on a sign next to its controls. */
    public static int roomFor(boolean first) {
        return PER_SIGN - (first ? 4 : 2);
    }

    /**
     * Lays one sign out: each control goes first on its own line (a line cut off at 80 characters
     * never loses a control), the mod questions fill the rest; the lines are shuffled.
     */
    public static List<List<Ask>> layout(List<Ask> controls, List<Ask> mods, Random r) {
        List<List<Ask>> rows = new ArrayList<>();
        for (int i = 0; i < LINES; i++) rows.add(new ArrayList<>());
        List<Integer> lines = new ArrayList<>(List.of(0, 1, 2, 3));
        Collections.shuffle(lines, r);
        for (int i = 0; i < controls.size() && i < LINES; i++) rows.get(lines.get(i)).add(controls.get(i));
        int next = 0;
        for (Ask m : mods) {
            while (next < LINES && rows.get(next).size() >= PER_LINE) next++;
            if (next >= LINES) break;
            rows.get(next).add(m);
        }
        rows.removeIf(List::isEmpty);
        return rows;
    }

    public static Component line(List<Ask> row) {
        var b = Component.text();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) b.append(Component.text(SEP));
            b.append(row.get(i).text());
        }
        return b.build();
    }

    /** The answers of every NEGATIVE control: they tie a reply to its sign. */
    public static List<String> nonces(List<List<Ask>> rows) {
        List<String> out = new ArrayList<>();
        for (List<Ask> row : rows) for (Ask a : row) if (a.kind() == Kind.NEGATIVE && !a.key().startsWith("bind:")) out.add(a.expect());
        return out;
    }

    /** Reads a reply: which answer belongs to which question, then whether the controls passed. */
    public static Verdict judge(List<List<Ask>> rows, List<String> lines) {
        Map<Ask, String> answers = new LinkedHashMap<>();
        for (int i = 0; i < rows.size() && i < lines.size(); i++) {
            List<Ask> row = rows.get(i);
            String[] parts = (lines.get(i) == null ? "" : lines.get(i)).split(Pattern.quote(SEP), -1);
            if (parts.length > row.size()) {
                // a text had the separator in it: only the first answer is sure (controls always stand first)
                answers.put(row.get(0), parts[0].trim());
                continue;
            }
            for (int j = 0; j < parts.length; j++) answers.put(row.get(j), parts[j].trim());
        }
        String problem = null;
        for (List<Ask> row : rows) {
            for (Ask a : row) {
                if (a.kind() == Kind.MOD) continue;
                String got = answers.get(a);
                if (got == null) {
                    problem = problem == null ? "missing" : problem;
                } else if (a.kind() == Kind.POSITIVE && (got.isEmpty() || got.equals(a.expect()))) {
                    problem = "blank"; // a text every client knows came back empty or raw: texts are not filled in
                } else if (a.kind() == Kind.NEGATIVE && !got.equals(a.expect())) {
                    problem = "altered"; // a made-up key did not come back as the game builds it: the client changes texts
                }
            }
        }
        return new Verdict(problem == null, problem, answers);
    }
}

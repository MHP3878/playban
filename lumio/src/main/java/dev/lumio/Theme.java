package dev.lumio;

import java.util.List;

/** The colour themes: an accent that runs from one colour into a second. */
public record Theme(String id, int accent, int accent2) {
    public static final List<Theme> ALL = List.of(
            new Theme("violet", 0xFF8B7CF6, 0xFF5EB8FF),
            new Theme("ocean", 0xFF38BDF8, 0xFF2DD4BF),
            new Theme("emerald", 0xFF34D399, 0xFFA3E635),
            new Theme("sunset", 0xFFFB923C, 0xFFF43F5E),
            new Theme("rose", 0xFFF472B6, 0xFFA78BFA),
            new Theme("mono", 0xFFE5E7EB, 0xFF9CA3AF));

    public static Theme byId(String id) {
        for (Theme t : ALL) if (t.id().equals(id)) return t;
        return null;
    }

    /** The theme in use now. */
    public static Theme current() {
        Theme t = byId(Config.get().theme);
        return t != null ? t : ALL.get(0);
    }
}

package dev.bastion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "30m", "1d 12h", "perm". */
public final class Time {
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([smhdw])");

    private Time() {}

    /** Milliseconds, 0 for permanent, -1 when it is not a duration. */
    public static long parse(String text) {
        if (text == null) return -1;
        String t = text.trim().toLowerCase();
        if (t.equals("perm") || t.equals("permanent") || t.equals("0")) return 0;
        Matcher m = PART.matcher(t);
        long total = 0;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (!t.substring(end, m.start()).isBlank()) return -1;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "w" -> n * 604_800_000L;
                case "d" -> n * 86_400_000L;
                case "h" -> n * 3_600_000L;
                case "m" -> n * 60_000L;
                default -> n * 1000L;
            };
            end = m.end();
            any = true;
        }
        return any && t.substring(end).isBlank() ? total : -1;
    }

    public static String format(long ms) {
        if (ms <= 0) return "perm";
        long s = ms / 1000, d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append("d ");
        if (h > 0) b.append(h).append("h ");
        if (m > 0 || b.length() == 0) b.append(Math.max(1, m)).append("m");
        return b.toString().trim();
    }
}

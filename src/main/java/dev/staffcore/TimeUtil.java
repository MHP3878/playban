package dev.staffcore;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeUtil {
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([wdhms])");

    private TimeUtil() {}

    /** Parses "1d 2h 30m" style input. Returns -1 when the text is not a duration. */
    public static long parse(String input) {
        if (input == null) return -1;
        String text = input.trim().toLowerCase();
        if (text.isEmpty()) return -1;
        if (text.matches("\\d+")) return Long.parseLong(text) * 60_000L;
        Matcher m = PART.matcher(text);
        long total = 0;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (!text.substring(end, m.start()).isBlank()) return -1;
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
        if (!any || !text.substring(end).isBlank()) return -1;
        return total;
    }

    public static String format(long ms) {
        if (ms < 1000) return "0s";
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 && d == 0) sb.append(s).append("s ");
        return sb.toString().trim();
    }

    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            while (word.length() > width) {
                if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}

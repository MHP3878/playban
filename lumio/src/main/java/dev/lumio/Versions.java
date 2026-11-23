package dev.lumio;

/** Compares versions like "0.1.0", "1.2" or "1.2.3+1.21.11" (the part after "+" does not count). */
public final class Versions {
    private Versions() {}

    public static int compare(String a, String b) {
        String[] x = clean(a).split("\\.");
        String[] y = clean(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0;
            int q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static String clean(String v) {
        if (v == null) return "0";
        String s = v.trim();
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        return s.isEmpty() ? "0" : s;
    }

    private static int number(String s) {
        int n = 0;
        for (char c : s.toCharArray()) {
            if (c < '0' || c > '9') break;
            n = n * 10 + (c - '0');
            if (n > 1_000_000) break;
        }
        return n;
    }
}

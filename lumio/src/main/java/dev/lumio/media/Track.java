package dev.lumio.media;

/**
 * What is playing: title, artist, the player app, whether it plays, where it stands and how long it
 * is (milliseconds, measured at {@code at}), and where its cover is (a link, a file, or empty).
 */
public record Track(String title, String artist, String app, boolean playing, long position, long length, long at, String art) {
    /** The position now: while it plays, the time since it was read is added. */
    public long positionNow() {
        long p = playing ? position + (System.currentTimeMillis() - at) : position;
        return length > 0 ? Math.max(0, Math.min(length, p)) : Math.max(0, p);
    }

    public boolean spotify() {
        return app != null && app.toLowerCase(java.util.Locale.ROOT).contains("spotify");
    }

    public static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }
}

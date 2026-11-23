package dev.lumio.media;

import dev.lumio.LumioClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads what the computer is playing (Spotify, a browser, any player), without logging in anywhere:
 * <ul>
 *   <li>Windows: the media session Windows shows in its volume overlay, through a small PowerShell
 *   script that Lumio writes next to the game (lumio/media.ps1) and that ends with the game;</li>
 *   <li>macOS: Spotify and Apple Music through AppleScript (macOS asks once whether that is allowed);</li>
 *   <li>Linux: every MPRIS player through playerctl, if it is installed.</li>
 * </ul>
 * Runs on its own thread, only while the music element of the HUD is on.
 */
public final class Media {
    private static volatile Track current;
    private static volatile boolean available = true;
    private static volatile boolean wanted;
    private static Thread thread;
    private static final Object LOCK = new Object();

    // Windows: the long-running script
    private static Process windows;
    private static volatile Track windowsLast;
    private static Path dir;

    private Media() {}

    public static Track current() {
        return current;
    }

    /** False when this computer has no way to read it (e.g. Linux without playerctl). */
    public static boolean available() {
        return available;
    }

    private enum Os { WINDOWS, MAC, LINUX, OTHER }

    private static Os os() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return Os.WINDOWS;
        if (n.contains("mac") || n.contains("darwin")) return Os.MAC;
        if (n.contains("linux") || n.contains("bsd")) return Os.LINUX;
        return Os.OTHER;
    }

    /** Whether the music element is on; the reader sleeps while it is off. */
    public static void setWanted(boolean on) {
        wanted = on;
    }

    /** Starts the reader thread. */
    public static void start() {
        synchronized (LOCK) {
            if (thread != null) return;
            thread = new Thread(Media::loop, "Lumio media");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Media::stopWindows, "Lumio media stop"));
    }

    private static void loop() {
        long retryAt = 0;
        while (true) {
            try {
                if (!wanted) {
                    current = null;
                    stopWindows();
                    Thread.sleep(2000);
                    continue;
                }
                if (!available && System.currentTimeMillis() < retryAt) {
                    Thread.sleep(2000);
                    continue;
                }
                Track t = switch (os()) {
                    case WINDOWS -> readWindows();
                    case MAC -> readMac();
                    case LINUX -> readLinux();
                    default -> {
                        available = false;
                        yield null;
                    }
                };
                if (!available) retryAt = System.currentTimeMillis() + 60_000;
                current = t;
                Cover.want(t == null ? "" : t.art());
                Thread.sleep(os() == Os.WINDOWS ? 500 : 1000);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                LumioClient.LOG.debug("Lumio media: {}", e.toString());
                current = null;
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- controls

    /** "toggle", "next" or "previous", sent to the player that is shown. */
    public static void control(String what) {
        Track t = current;
        Thread run = new Thread(() -> {
            try {
                switch (os()) {
                    case WINDOWS -> {
                        if (dir != null) Files.writeString(dir.resolve("media-cmd.txt"), what, StandardCharsets.UTF_8);
                    }
                    case MAC -> {
                        String app = t != null && "Music".equals(t.app()) ? "Music" : "Spotify";
                        String verb = switch (what) {
                            case "next" -> "next track";
                            case "previous" -> "previous track";
                            default -> "playpause";
                        };
                        run(List.of("osascript", "-e", "tell application \"" + app + "\" to " + verb), 3);
                    }
                    case LINUX -> run(List.of("playerctl", switch (what) {
                        case "next" -> "next";
                        case "previous" -> "previous";
                        default -> "play-pause";
                    }), 3);
                    default -> { }
                }
            } catch (Exception e) {
                LumioClient.LOG.debug("Lumio media control: {}", e.toString());
            }
        }, "Lumio media control");
        run.setDaemon(true);
        run.start();
    }

    // ---------------------------------------------------------------- Windows

    private static Track readWindows() throws IOException {
        if (windows == null || !windows.isAlive()) {
            dir = FabricLoader.getInstance().getGameDir().resolve("lumio");
            Files.createDirectories(dir);
            Path script = dir.resolve("media.ps1");
            try (InputStream in = Media.class.getResourceAsStream("/lumio/media.ps1")) {
                if (in == null) throw new IOException("media.ps1 missing in the jar");
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            Path ps = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (!Files.isRegularFile(ps)) {
                available = false;
                return null;
            }
            ProcessBuilder pb = new ProcessBuilder(ps.toString(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-File", script.toString(), String.valueOf(ProcessHandle.current().pid()),
                    dir.resolve("media-cmd.txt").toString(), dir.resolve("cover.img").toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            windows = pb.start();
            windows.getOutputStream().close();
            windowsLast = null;
            Process p = windows;
            Thread reader = new Thread(() -> readWindowsLines(p), "Lumio media reader");
            reader.setDaemon(true);
            reader.start();
            available = true;
        }
        return windowsLast;
    }

    private static void readWindowsLines(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 9 || !f[0].equals("T")) {
                    windowsLast = null;
                    continue;
                }
                String title = b64(f[1]);
                if (title.isBlank()) {
                    windowsLast = null;
                    continue;
                }
                long pos = num(f[4]), len = num(f[5]), updated = num(f[6]);
                boolean playing = f[3].equals("1");
                // Windows tells the position at its last update; count on from there
                long at = updated > 0 && updated <= System.currentTimeMillis() ? updated : System.currentTimeMillis();
                windowsLast = new Track(title, b64(f[2]), appName(b64(f[7])), playing, pos, len, at,
                        "file:" + dir.resolve("cover.img") + "#" + f[8]);
            }
        } catch (IOException ignored) {
            // the script ended
        }
        windowsLast = null;
    }

    /** "Spotify.exe" / "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify" / "Chrome" become a short name. */
    private static String appName(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains("spotify")) return "Spotify";
        if (s.contains("chrome")) return "Chrome";
        if (s.contains("firefox")) return "Firefox";
        if (s.contains("msedge") || s.contains("edge")) return "Edge";
        if (s.contains("opera")) return "Opera";
        if (s.contains("zune") || s.contains("media")) return "Media Player";
        int bang = id.lastIndexOf('!');
        String n = bang >= 0 ? id.substring(bang + 1) : id;
        if (n.toLowerCase(Locale.ROOT).endsWith(".exe")) n = n.substring(0, n.length() - 4);
        return n.length() > 20 ? n.substring(0, 20) : n;
    }

    private static void stopWindows() {
        Process p = windows;
        windows = null;
        if (p != null) p.destroyForcibly();
    }

    // ---------------------------------------------------------------- macOS

    private static Track readMac() throws Exception {
        StringBuilder script = new StringBuilder();
        if (appExists("Spotify")) script.append("""
                if application "Spotify" is running then
                  tell application "Spotify"
                    if player state is not stopped then
                      return "Spotify" & tab & (name of current track) & tab & (artist of current track) & tab & (player state as string) & tab & ((player position * 1000) as integer) & tab & (duration of current track) & tab & (artwork url of current track)
                    end if
                  end tell
                end if
                """);
        script.append("""
                if application "Music" is running then
                  tell application "Music"
                    if player state is not stopped then
                      return "Music" & tab & (name of current track) & tab & (artist of current track) & tab & (player state as string) & tab & ((player position * 1000) as integer) & tab & ((duration of current track * 1000) as integer) & tab & ""
                    end if
                  end tell
                end if
                return ""
                """);
        String out = run(List.of("osascript", "-e", script.toString()), 4);
        if (out == null) return null;
        String[] f = out.strip().split("\t", -1);
        if (f.length < 7 || f[1].isBlank()) return null;
        return new Track(f[1], f[2], f[0], f[3].equalsIgnoreCase("playing"), num(f[4]), num(f[5]), System.currentTimeMillis(), f[6]);
    }

    private static boolean appExists(String name) {
        String home = System.getProperty("user.home", "");
        return Files.isDirectory(Path.of("/Applications", name + ".app")) || Files.isDirectory(Path.of(home, "Applications", name + ".app"));
    }

    // ---------------------------------------------------------------- Linux

    private static Track readLinux() throws Exception {
        String out = run(List.of("playerctl", "-a", "metadata", "--format",
                "{{status}}\t{{title}}\t{{artist}}\t{{position}}\t{{mpris:length}}\t{{playerName}}\t{{mpris:artUrl}}"), 3);
        if (out == null) return null;
        Track first = null;
        for (String line : out.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 7 || f[1].isBlank()) continue;
            Track t = new Track(f[1], f[2], f[5].isEmpty() ? "" : Character.toUpperCase(f[5].charAt(0)) + f[5].substring(1),
                    f[0].equalsIgnoreCase("Playing"), num(f[3]) / 1000, num(f[4]) / 1000, System.currentTimeMillis(), f[6]);
            if (t.playing()) return t;
            if (first == null) first = t;
        }
        return first;
    }

    // ---------------------------------------------------------------- helpers

    /** Runs a program and returns what it printed, or null when it failed. Marks "not available" when it is missing. */
    private static String run(List<String> cmd, int seconds) throws InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(new ArrayList<>(cmd)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException missing) {
            available = false;
            return null;
        }
        available = true;
        try {
            p.getOutputStream().close();
            // the answers are a few lines, far less than the pipe holds, so waiting first cannot block
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            byte[] bytes = p.getInputStream().readNBytes(64 * 1024);
            return p.exitValue() == 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            p.destroyForcibly();
            return null;
        }
    }

    private static String b64(String s) {
        try {
            return new String(Base64.getDecoder().decode(s.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(s.trim().replace(',', '.'));
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }
}

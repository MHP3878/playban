package dev.lumio.update;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Before the game starts: is there a newer Lumio? Then a small window of its own asks whether to
 * update now. Yes downloads and checks the new version, closes Minecraft, swaps the file and starts
 * Minecraft again. Without internet, or with nothing new, the game starts at once (at most three
 * seconds are spent asking). Nothing about the player is sent: only the update file is fetched.
 */
public final class UpdateCheck implements PreLaunchEntrypoint {
    static final Logger LOG = LoggerFactory.getLogger("Lumio");
    /** The list of the newest Lumio, from the project's own repository. */
    public static final String DEFAULT_URL = "https://raw.githubusercontent.com/MHP3878/playban/HEAD/lumio/update.json";

    /** The update settings: on or off, and a version the player chose to skip. */
    public static final class Settings {
        public boolean auto = true;
        public String skip = "";
        public String url = "";

        static Path file() {
            return FabricLoader.getInstance().getConfigDir().resolve("lumio-update.json");
        }

        public static Settings load() {
            try {
                return new Gson().fromJson(Files.readString(file()), Settings.class);
            } catch (Exception e) {
                return new Settings();
            }
        }

        public void save() {
            try {
                Files.writeString(file(), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(this));
            } catch (IOException e) {
                LOG.warn("Lumio: could not save the update settings: {}", e.toString());
            }
        }
    }

    @Override
    public void onPreLaunch() {
        try {
            check();
        } catch (Throwable t) {
            LOG.warn("Lumio update check: {}", t.toString());
        }
    }

    static boolean allowed(String url) {
        return Hosts.allowed(url);
    }

    /** "0.10.2" against "0.11.0": below zero when a is older. */
    static int compare(String a, String b) {
        String[] x = a.split("[.+-]"), y = b.split("[.+-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void check() throws Exception {
        FabricLoader fl = FabricLoader.getInstance();
        Settings s = Settings.load();
        ModContainer me = fl.getModContainer("lumio").orElse(null);
        if (me == null) return;
        Path jar = ownJar(me);
        if (jar == null) return;
        // a half download from before is of no use
        try (var files = Files.list(jar.getParent())) {
            files.filter(f -> f.getFileName().toString().startsWith("lumio-") && f.getFileName().toString().endsWith(".download")).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // next time
                }
            });
        }
        if (!s.auto || Boolean.getBoolean("lumio.noUpdate")) return;
        String mc = fl.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        String current = me.getMetadata().getVersion().getFriendlyString().split("\\+")[0];
        String url = System.getProperty("lumio.updateUrl", s.url == null || s.url.isBlank() ? DEFAULT_URL : s.url);
        if (!allowed(url)) return;

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                .header("User-Agent", "Lumio/" + current).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return;
        JsonObject m = JsonParser.parseString(res.body()).getAsJsonObject();
        String latest = str(m, "version");
        if (latest.isEmpty() || compare(latest, current) <= 0 || latest.equals(s.skip)) return;
        JsonObject files = m.has("files") && m.get("files").isJsonObject() ? m.getAsJsonObject("files") : null;
        JsonObject file = files != null && files.has(mc) && files.get(mc).isJsonObject() ? files.getAsJsonObject(mc) : null;
        if (file == null) return;
        String download = str(file, "url"), sha = str(file, "sha256").toLowerCase(Locale.ROOT);
        if (!allowed(download) || !sha.matches("[0-9a-f]{64}")) return;
        String lang = language(fl.getGameDir());
        String changes = "";
        if (m.has("changes") && m.get("changes").isJsonObject()) {
            JsonObject c = m.getAsJsonObject("changes");
            changes = str(c, lang).isEmpty() ? str(c, "en") : str(c, lang);
        }
        LOG.info("Lumio {} is out (this is {}): asking whether to update", latest, current);

        // the window runs in a Java of its own, from a copy of this jar: the game keeps this one open
        Path copy = Files.createTempFile("lumio-updater", ".jar");
        Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
        copy.toFile().deleteOnExit();
        Path log = Path.of(System.getProperty("java.io.tmpdir"), "lumio-updater.log");
        Process up = new ProcessBuilder(javaBin(), "-cp", copy.toString(), "dev.lumio.update.Updater")
                .redirectError(log.toFile()).start();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(up.getOutputStream(), StandardCharsets.UTF_8))) {
            put(out, "lang", lang);
            put(out, "current", current);
            put(out, "version", latest);
            put(out, "minecraft", mc);
            put(out, "url", download);
            put(out, "sha256", sha);
            put(out, "jar", jar.toAbsolutePath().toString());
            put(out, "pid", String.valueOf(ProcessHandle.current().pid()));
            put(out, "cwd", System.getProperty("user.dir"));
            put(out, "changes", changes);
            // how to start this game again (only passed through the pipe, never written anywhere)
            for (String a : relaunch()) put(out, "arg", a);
            out.println("end");
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(up.getInputStream(), StandardCharsets.UTF_8));
        long until = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15);
        String line;
        while (System.currentTimeMillis() < until && (line = in.readLine()) != null) {
            switch (line.trim()) {
                case "LATER", "FAILED" -> {
                    return;
                }
                case "SKIP" -> {
                    s.skip = latest;
                    s.save();
                    return;
                }
                case "READY" -> {
                    // the new version is downloaded and checked: the game closes, the window swaps the file and starts it again
                    LOG.info("Lumio {} is ready: Minecraft restarts to use it", latest);
                    Runtime.getRuntime().halt(0);
                }
                default -> {
                    // something else on the line: ignore
                }
            }
        }
    }

    private static void put(PrintWriter out, String key, String value) {
        out.println(key + "=" + (value == null ? "" : value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")));
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Where this Lumio is: its jar in the mods folder (null when it runs from a folder, as in development). */
    static Path ownJar(ModContainer me) {
        String forced = System.getProperty("lumio.update.jar");
        if (forced != null) return Path.of(forced);
        try {
            for (Path p : me.getOrigin().getPaths()) if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) return p;
        } catch (UnsupportedOperationException | NoSuchMethodError ignored) {
            // inside another mod: not ours to swap
        }
        return null;
    }

    /** The language of the game, from options.txt: de or en. */
    static String language(Path gameDir) {
        try {
            for (String l : Files.readAllLines(gameDir.resolve("options.txt"))) {
                if (l.startsWith("lang:")) return l.substring(5).trim().startsWith("de") ? "de" : "en";
            }
        } catch (IOException ignored) {
            // a new installation
        }
        return Locale.getDefault().getLanguage().equals("de") ? "de" : "en";
    }

    static String javaBin() {
        return ProcessHandle.current().info().command().orElseGet(() -> Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java").toString());
    }

    /**
     * The command that started this game: the same Java, its settings, the class path, Fabric's own
     * start class and the game's arguments (with launchers that start through a wrapper, the wrapper
     * is skipped: Fabric's start class is on the class path anyway).
     */
    static List<String> relaunch() {
        List<String> a = new ArrayList<>();
        a.add(javaBin());
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!arg.startsWith("-Djava.class.path=")) a.add(arg);
        }
        a.add("-cp");
        a.add(System.getProperty("java.class.path"));
        String first = System.getProperty("sun.java.command", "").split(" ")[0];
        a.add(first.startsWith("net.fabricmc.") ? first : "net.fabricmc.loader.impl.launch.knot.KnotClient");
        a.addAll(Arrays.asList(FabricLoader.getInstance().getLaunchArguments(false)));
        return a;
    }
}

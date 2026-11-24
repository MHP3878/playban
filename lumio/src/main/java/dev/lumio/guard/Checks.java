package dev.lumio.guard;

import dev.lumio.LumioClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The checks themselves. Each one looks only at this game's own process and returns what it found,
 * described without folders (a file name and the kind of place it lies in, nothing about the user).
 */
final class Checks {
    private Checks() {}

    static String os() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return "windows";
        if (n.contains("mac") || n.contains("darwin")) return "mac";
        return "linux";
    }

    /** Only the file name of a path. */
    static String fileName(String path) {
        String p = path.replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        String n = i >= 0 ? p.substring(i + 1) : p;
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    /** Where a file lies, as a word: Temp, Downloads, Desktop, AppData, or "other folder". */
    static String place(String path) {
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (p.contains("/temp/") || p.contains("/tmp/")) return "Temp";
        if (p.contains("/downloads/")) return "Downloads";
        if (p.contains("/desktop/")) return "Desktop";
        if (p.contains("/appdata/")) return "AppData";
        if (p.contains("/dev/shm/")) return "/dev/shm";
        if (p.startsWith("/memfd:")) return "memory file";
        return "other folder";
    }

    // ---------------------------------------------------------------- Java agents and debuggers

    /** Agents named on the command line. authlib-injector (log-in servers of some launchers) is no cheat. */
    static List<Finding> agents() {
        List<Finding> out = new ArrayList<>();
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            String a = arg.trim();
            String lower = a.toLowerCase(Locale.ROOT);
            if (lower.startsWith("-javaagent:")) {
                String file = fileName(a.substring(11).split("=", 2)[0]);
                if (file.toLowerCase(Locale.ROOT).contains("authlib-injector")) continue;
                out.add(new Finding("agent", true, file, ""));
            } else if (lower.startsWith("-agentpath:")) {
                out.add(new Finding("agent", true, fileName(a.substring(11).split("=", 2)[0]), ""));
            } else if (lower.startsWith("-agentlib:")) {
                String lib = a.substring(10).split("=", 2)[0];
                if (lib.equalsIgnoreCase("jdwp")) out.add(new Finding("debugger", false, "jdwp", ""));
                else out.add(new Finding("agent", true, fileName(lib), ""));
            } else if (lower.startsWith("-xbootclasspath/a:") || lower.startsWith("-xbootclasspath/p:")) {
                out.add(new Finding("agent", false, "bootclasspath " + fileName(a.substring(18)), ""));
            }
        }
        return out;
    }

    /** Somebody used the attach mechanism on the running game (to load an agent, or a tool like jcmd). */
    static List<Finding> attach() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Attach Listener".equals(t.getName())) return List.of(new Finding("attach", false, "Attach Listener", ""));
        }
        return List.of();
    }

    // ---------------------------------------------------------------- code without origin

    /** What a class name turned out to be: fine, defined without a file (injected), or from an unknown file. */
    enum Origin { FINE, NONE, UNKNOWN_FILE, NOT_FOUND }

    private static final Map<String, Origin> CACHE = new HashMap<>();
    private static final Map<String, String> UNKNOWN = new HashMap<>();
    private static List<String> allowed;

    /** Places classes may come from: Minecraft, the mods, the libraries, Java itself. */
    private static List<String> allowed() {
        if (allowed != null) return allowed;
        Set<String> out = new LinkedHashSet<>();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            try {
                for (Path p : m.getOrigin().getPaths()) out.add(norm(p.toAbsolutePath().toString()));
            } catch (RuntimeException nested) {
                // a mod inside another mod: covered by the outer one
            }
        }
        for (String cp : System.getProperty("java.class.path", "").split(File.pathSeparator)) if (!cp.isBlank()) out.add(norm(new File(cp).getAbsolutePath()));
        out.add(norm(System.getProperty("java.home", "")));
        out.add(norm(FabricLoader.getInstance().getGameDir().toAbsolutePath().toString()));
        // libraries of the launcher, and of a development setup
        out.add("/libraries/");
        out.add("/.gradle/caches/");
        out.removeIf(String::isBlank);
        allowed = new ArrayList<>(out);
        return allowed;
    }

    private static String norm(String s) {
        return s.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /** Classes the JVM makes itself, or Mixin and lambdas: no file of their own, and that is normal. */
    static boolean generated(String name) {
        // java.* can only come from Java itself; everything else is looked up, whatever its name
        return name.contains("$$") || name.contains("/0x") || name.contains("LambdaForm$") || name.startsWith("jdk.internal.reflect.Generated")
                || name.startsWith("jdk.proxy") || name.startsWith("com.sun.proxy") || name.contains("$Proxy")
                || name.startsWith("org.spongepowered.asm.synthetic") || name.startsWith("java.");
    }

    static Origin origin(String name, ClassLoader game) {
        Origin known = CACHE.get(name);
        if (known != null) return known;
        Origin o;
        if (generated(name)) o = Origin.FINE;
        else {
            try {
                Class<?> c = Class.forName(name, false, game);
                ClassLoader l = c.getClassLoader();
                CodeSource cs = c.getProtectionDomain() == null ? null : c.getProtectionDomain().getCodeSource();
                if (l == null) {
                    // Java's own classes always belong to a named module (java.base ...); code pushed
                    // into the bootstrap loader by an injector does not
                    o = c.getModule().isNamed() || name.startsWith("moe.yushi.authlibinjector.") ? Origin.FINE : Origin.NONE;
                } else if (l == ClassLoader.getPlatformClassLoader()) o = Origin.FINE;
                else if (cs == null || cs.getLocation() == null) o = c.isHidden() || c.isSynthetic() ? Origin.FINE : Origin.NONE;
                else o = fromKnownPlace(cs.getLocation(), name) ? Origin.FINE : Origin.UNKNOWN_FILE;
            } catch (ClassNotFoundException | LinkageError | SecurityException e) {
                o = Origin.NOT_FOUND;
            }
        }
        if (CACHE.size() < 50_000) CACHE.put(name, o);
        return o;
    }

    private static boolean fromKnownPlace(URL location, String name) {
        String loc;
        try {
            // as a path, so "+" and "%20" come out as they are on disk
            String u = location.toString();
            if (u.startsWith("jar:")) u = u.substring(4).replaceFirst("!/.*$", "");
            loc = norm(Path.of(java.net.URI.create(u)).toAbsolutePath().toString());
        } catch (RuntimeException e) {
            loc = norm(location.toString());
        }
        for (String a : allowed()) if (loc.contains(a)) return true;
        UNKNOWN.put(name, fileName(loc.replaceFirst("^jar:", "").replaceFirst("!/.*$", "")));
        return false;
    }

    /**
     * Looks at what every thread of the game is running right now. Code defined straight into the game
     * without a file (the way injection clients put their classes in) is strong; code from a file in an
     * unusual place, or from a class loader nobody can find, is only unusual.
     */
    static List<Finding> code() {
        ClassLoader game = LumioClient.class.getClassLoader();
        Map<String, Finding> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            for (StackTraceElement f : e.getValue()) {
                String cls = f.getClassName();
                Origin o = origin(cls, game);
                if (o == Origin.FINE) continue;
                String shown = cls.length() > 80 ? cls.substring(0, 80) : cls;
                switch (o) {
                    case NONE -> out.putIfAbsent("none:" + cls, new Finding("code", true, shown, guessName(cls)));
                    case UNKNOWN_FILE -> out.putIfAbsent("file:" + cls, new Finding("codefile", false, shown + " (" + UNKNOWN.getOrDefault(cls, "?") + ")", ""));
                    case NOT_FOUND -> out.putIfAbsent("nf:" + cls, new Finding("codeloader", false, shown, ""));
                    default -> { }
                }
                if (out.size() >= 16) break;
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Class names the sampler saw on the game's own thread (Render thread), with their loader and module. */
    static final Map<String, String> SAMPLED = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Thread sampler;

    /**
     * A cheat put into the game has to run on the game's own thread to aim, hit or draw, but only for
     * a moment each tick. So the game's thread is looked at twenty times a second; every class seen
     * there is judged once, like the others.
     */
    static synchronized void startSampler() {
        if (sampler != null) return;
        Thread t = new Thread(() -> {
            Thread render = null;
            while (true) {
                try {
                    if (render == null || !render.isAlive()) {
                        render = null;
                        for (Thread th : Thread.getAllStackTraces().keySet()) if (th.getName().equals("Render thread")) render = th;
                    }
                    if (render != null) {
                        for (StackTraceElement f : render.getStackTrace()) {
                            if (SAMPLED.size() >= 20_000) break;
                            SAMPLED.putIfAbsent(f.getClassName(), String.valueOf(f.getClassLoaderName()) + "|" + f.getModuleName());
                        }
                    }
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                    // a moment later again
                }
            }
        }, "Lumio sampler");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        sampler = t;
    }

    private static final Set<String> JUDGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * What the sampler saw on the game's thread. Code without a file is strong as everywhere; code from
     * a class loader nobody of the game knows, outside any Java module, is reported by name ("hook").
     */
    static List<Finding> sampled() {
        ClassLoader game = LumioClient.class.getClassLoader();
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, String> e : SAMPLED.entrySet()) {
            String cls = e.getKey();
            if (!JUDGED.add(cls)) continue;
            Origin o = origin(cls, game);
            String shown = cls.length() > 80 ? cls.substring(0, 80) : cls;
            switch (o) {
                case NONE -> out.add(new Finding("code", true, shown, guessName(cls)));
                case UNKNOWN_FILE -> out.add(new Finding("codefile", false, shown + " (" + UNKNOWN.getOrDefault(cls, "?") + ")", ""));
                case NOT_FOUND -> {
                    // not one of Java's modules, and none of the game's class loaders knows it
                    if (e.getValue().endsWith("|null")) out.add(new Finding("hook", false, shown, guessName(cls)));
                }
                default -> { }
            }
            if (out.size() >= 16) break;
        }
        return out;
    }

    /** A client name from a package name, when it gives itself away (e.g. "gg.vape..." gives "Vape"). */
    static String guessName(String cls) {
        String c = cls.toLowerCase(Locale.ROOT);
        for (String[] n : new String[][]{{"vape", "Vape"}, {"prestige", "Prestige"}, {"drip", "Drip"}, {"entropy", "Entropy"},
                {"rise", "Rise"}, {"novoline", "Novoline"}, {"doomsday", "Doomsday"}, {"slinky", "Slinky"}, {"karma", "Karma"}}) {
            if (c.startsWith(n[0] + ".") || c.contains("." + n[0] + ".")) return n[1];
        }
        return "";
    }

    // ---------------------------------------------------------------- native: DLLs, libraries, threads

    /** Windows: DLLs from unusual folders (weak), and threads that start in no DLL at all (strong). */
    static List<Finding> windows() {
        List<Finding> out = new ArrayList<>();
        List<WindowsNative.Module> mods = WindowsNative.modules();
        String sysRoot = norm(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"));
        String javaHome = norm(System.getProperty("java.home", ""));
        String gameDir = norm(FabricLoader.getInstance().getGameDir().toAbsolutePath().toString());
        for (WindowsNative.Module m : mods) {
            String p = norm(m.path());
            if (p.startsWith(sysRoot) || p.startsWith(javaHome) || p.contains("/program files") || p.contains("/programdata/")
                    || p.startsWith(gameDir) || nativesOfTheGame(p) || harmless(p)) continue;
            // only where injectors drop their DLLs: Temp, Downloads, the desktop. Everything else
            // (AppData, other drives, the user's own folders) is full of overlays and tools.
            String where = place(m.path());
            if (!where.equals("Temp") && !where.equals("Downloads") && !where.equals("Desktop")) continue;
            out.add(new Finding("module", false, fileName(m.path()) + " (" + where + ")", ""));
            if (out.size() >= 8) break;
        }
        List<Long> outside = new ArrayList<>();
        for (long start : WindowsNative.threadStarts()) if (!inAny(start, mods)) outside.add(start);
        if (!outside.isEmpty()) {
            // a DLL loaded between the two lists would look the same: ask for the DLLs once more
            List<WindowsNative.Module> again = WindowsNative.modules();
            outside.removeIf(start -> inAny(start, again));
        }
        // only a sign for now: not every program that starts a thread this way is a cheat
        if (!outside.isEmpty()) out.add(new Finding("thread", false, outside.size() + "x", ""));
        return out;
    }

    private static boolean inAny(long start, List<WindowsNative.Module> mods) {
        for (WindowsNative.Module m : mods) {
            if (Long.compareUnsigned(start, m.base()) >= 0 && Long.compareUnsigned(start, m.base() + m.size()) < 0) return true;
        }
        return false;
    }

    /**
     * DLLs that overlays, recorders, drivers and antivirus programs put into every game: Discord,
     * Steam, NVIDIA, AMD, OBS, RivaTuner/MSI Afterburner, Overwolf, Medal, Razer, Logitech and others.
     */
    static boolean harmless(String p) {
        String n = fileName(p).toLowerCase(Locale.ROOT);
        for (String h : List.of("discordhook", "discord_", "gameoverlayrenderer", "steamclient", "graphics-hook", "obs-", "rtsshooks", "rtsscore",
                "overwolf", "owclient", "medal", "nvspcap", "nvcamera", "nvapi", "nvd3d", "nvoglv", "nvwgf", "amdxx", "atiu", "atio", "igd", "igc",
                "razer", "rzchroma", "logi", "lghub", "aswhook", "avghook", "bdhkm", "atcuf", "klhk", "sbiedll", "mbae", "epp", "fraps", "action_x64",
                "bandicam", "plays", "outplayed", "shadowplay", "geforce", "radeon", "xboxgamebar", "gamebar", "mirillis", "nahimic", "sonic", "dragon")) {
            if (n.startsWith(h)) return true;
        }
        return false;
    }

    /** The game's own native files: LWJGL, GLFW, OpenAL, JNA, jemalloc, in the folders launchers use. */
    static boolean nativesOfTheGame(String p) {
        String n = fileName(p).toLowerCase(Locale.ROOT);
        boolean name = n.startsWith("lwjgl") || n.startsWith("glfw") || n.startsWith("openal") || n.startsWith("jemalloc")
                || n.startsWith("jnidispatch") || n.startsWith("libjemalloc") || n.startsWith("liblwjgl") || n.startsWith("libglfw")
                || n.startsWith("libopenal") || n.startsWith("jna") || n.startsWith("sqlite") || n.startsWith("netty")
                || n.startsWith("libnetty") || n.startsWith("libjnidispatch") || n.startsWith("ffi");
        String d = p.toLowerCase(Locale.ROOT);
        boolean folder = d.contains("lwjgl") || d.contains("natives") || d.contains("/.minecraft/") || d.contains("/jna") || d.contains("/bin/");
        return name || folder;
    }

    /** Linux: shared libraries from temporary or download folders, or from memory files; LD_PRELOAD. */
    static List<Finding> linux() {
        List<Finding> out = new ArrayList<>();
        String preload = System.getenv("LD_PRELOAD");
        if (preload != null && !preload.isBlank()) out.add(new Finding("preload", false, fileName(preload.split("[ :]")[0]), ""));
        Path maps = Path.of("/proc/self/maps");
        if (!Files.isReadable(maps)) return out;
        try {
            Set<String> seen = new LinkedHashSet<>();
            for (String line : Files.readAllLines(maps)) {
                int i = line.indexOf('/');
                if (i < 0 || line.length() < 30) continue;
                String path = line.substring(i).trim();
                boolean exec = line.split(" ", 3).length > 1 && line.split(" ", 3)[1].contains("x");
                if (!exec || !seen.add(path)) continue;
                String place = place(path);
                if (path.startsWith("/memfd:")) {
                    // libffi (used by JNA) makes such memory files too
                    if (!path.contains("ffi")) out.add(new Finding("library", false, path.length() > 40 ? path.substring(0, 40) : path, ""));
                } else if (!place.equals("other folder") && !nativesOfTheGame(path) && !path.startsWith("/proc/")) {
                    out.add(new Finding("library", false, fileName(path) + " (" + place + ")", ""));
                }
                if (out.size() >= 8) break;
            }
        } catch (Exception e) {
            // not readable here
        }
        return out;
    }

    /** macOS: libraries pushed into every program. */
    static List<Finding> mac() {
        String insert = System.getenv("DYLD_INSERT_LIBRARIES");
        if (insert == null || insert.isBlank()) return List.of();
        return List.of(new Finding("preload", false, fileName(insert.split(":")[0]), ""));
    }

    // ---------------------------------------------------------------- programs next to the game

    /** A running program whose name is on the server's list of injectors (only matches are told). */
    static List<Finding> processes(List<String> watch) {
        if (watch.isEmpty()) return List.of();
        List<Finding> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            if (out.size() >= 4) return;
            String cmd = ph.info().command().orElse("");
            if (cmd.isEmpty()) return;
            String n = fileName(cmd).toLowerCase(Locale.ROOT);
            if (n.endsWith(".exe")) n = n.substring(0, n.length() - 4);
            for (String w : watch) {
                if (n.equals(w) && seen.add(w)) {
                    out.add(new Finding("process", true, fileName(cmd), Character.toUpperCase(w.charAt(0)) + w.substring(1)));
                    break;
                }
            }
        });
        return out;
    }
}

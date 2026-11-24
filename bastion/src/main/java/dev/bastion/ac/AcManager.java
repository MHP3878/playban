package dev.bastion.ac;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bastion.BastionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the anti-cheats Bastion works with on the server: Grim and Intave come as their official
 * files from Modrinth (checked against Modrinth's fingerprints) when they are missing, newer versions
 * wait in plugins/update for the next restart, and other anti-cheats (Vulcan, Spartan, NoCheatPlus ...)
 * are switched off and moved to plugins/Bastion/entfernt, where they can be taken back from.
 */
public final class AcManager {
    /** An anti-cheat Bastion brings: its plugin name and its Modrinth project. */
    record Managed(String plugin, String project) {}

    static final List<Managed> MANAGED = List.of(new Managed("GrimAC", "grimac"), new Managed("Intave", "intave"));

    private final BastionPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public AcManager(BastionPlugin plugin) {
        this.plugin = plugin;
    }

    private File pluginsDir() {
        return plugin.getDataFolder().getParentFile();
    }

    // ---------------------------------------------------------------- other anti-cheats

    /** Switches other anti-cheats off and moves their files out of plugins/ (only names on the list). */
    public List<String> removeOthers() {
        List<String> removed = new ArrayList<>();
        if (!plugin.getConfig().getBoolean("anticheat.remove-others", true)) return removed;
        List<String> names = new ArrayList<>();
        for (String n : plugin.getConfig().getStringList("anticheat.other-anticheats")) names.add(n.toLowerCase(Locale.ROOT));
        File target = new File(plugin.getDataFolder(), "entfernt");
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            String name = other.getName();
            if (other == plugin || name.equalsIgnoreCase("GrimAC") || name.equalsIgnoreCase("Intave")) continue;
            if (!names.contains(name.toLowerCase(Locale.ROOT))) continue;
            File jar = jarOf(other);
            if (other.isEnabled()) Bukkit.getPluginManager().disablePlugin(other);
            String where = "-";
            if (jar != null && jar.isFile() && jar.getParentFile().equals(pluginsDir())) {
                target.mkdirs();
                try {
                    Files.move(jar.toPath(), new File(target, jar.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    where = "plugins/Bastion/entfernt/" + jar.getName();
                } catch (IOException e) {
                    where = jar.getName() + " (" + plugin.lang().raw("acmanager.move-failed") + ")";
                }
            }
            removed.add(name);
            plugin.getLogger().warning("Removed the other anti-cheat " + name + " (it would fight with Grim): " + where);
            plugin.notifyTeam(plugin.lang().prefixed("acmanager.removed", "name", name, "where", where));
        }
        return removed;
    }

    public static File jarOfPlugin(Plugin p) {
        return jarOf(p);
    }

    /**
     * The plugin's own file in plugins/ (the server runs plugins from a converted copy elsewhere, so
     * the file is found by the name in its plugin.yml).
     */
    static File jarOf(Plugin p) {
        File dir = p.getDataFolder().getParentFile();
        File[] jars = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) return null;
        for (File jar : jars) {
            if (p.getName().equals(nameIn(jar))) return jar;
        }
        return null;
    }

    /** The plugin name a jar declares (plugin.yml or paper-plugin.yml), or null. */
    static String nameIn(File jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
            for (String yml : new String[]{"paper-plugin.yml", "plugin.yml"}) {
                var entry = zip.getEntry(yml);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                    String name = y.getString("name");
                    if (name != null) return name;
                }
            }
        } catch (IOException | RuntimeException e) {
            // not a plugin
        }
        return null;
    }

    // ---------------------------------------------------------------- Grim and Intave from Modrinth

    /** Looks after Grim and Intave in the background: missing ones are fetched, newer ones wait for the restart. */
    public void checkAsync() {
        if (!plugin.getConfig().getBoolean("anticheat.consensus.download", true) && !plugin.getConfig().getBoolean("anticheat.consensus.update", true)) return;
        String mc = Bukkit.getMinecraftVersion();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> installed = new ArrayList<>();
            List<String> updated = new ArrayList<>();
            for (Managed m : MANAGED) {
                if (!plugin.getConfig().getBoolean("anticheat." + (m.plugin().equals("GrimAC") ? "grim" : "intave") + ".enabled", true)) continue;
                try {
                    Plugin present = Bukkit.getPluginManager().getPlugin(m.plugin());
                    JsonObject file = newest(m.project(), mc);
                    if (file == null) continue;
                    String name = str(file, "filename");
                    String sha512 = str(file.getAsJsonObject("hashes"), "sha512");
                    String url = str(file, "url");
                    if (!name.endsWith(".jar") || name.contains("/") || name.contains("\\") || !url.startsWith("https://cdn.modrinth.com/")) continue;
                    if (present == null) {
                        if (!plugin.getConfig().getBoolean("anticheat.consensus.download", true)) continue;
                        File dest = new File(pluginsDir(), name);
                        if (dest.isFile()) continue;
                        download(url, sha512, dest);
                        if (m.plugin().equals("Intave")) Bukkit.getScheduler().runTask(plugin, () -> IntaveHook.configure(plugin, dest));
                        installed.add(m.plugin());
                    } else if (plugin.getConfig().getBoolean("anticheat.consensus.update", true)) {
                        File jar = jarOf(present);
                        if (jar == null || !jar.isFile() || sha512.equalsIgnoreCase(sha512(jar.toPath()))) continue;
                        // the server swaps it on the next start (same file name in plugins/update)
                        File dest = new File(new File(pluginsDir(), "update"), jar.getName());
                        if (dest.isFile() && sha512.equalsIgnoreCase(sha512(dest.toPath()))) continue;
                        dest.getParentFile().mkdirs();
                        download(url, sha512, dest);
                        updated.add(m.plugin());
                    }
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().info("Could not look for " + m.plugin() + " on Modrinth: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (installed.isEmpty() && updated.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!installed.isEmpty()) {
                    plugin.getLogger().warning("Installed " + String.join(" and ", installed) + " (official files from Modrinth). Restart the server once: then all anti-cheats work together.");
                    plugin.notifyTeam(plugin.lang().prefixed("acmanager.installed", "names", String.join(", ", installed)));
                }
                if (!updated.isEmpty()) {
                    plugin.getLogger().info("A newer " + String.join(" and ", updated) + " is ready in plugins/update and comes with the next restart.");
                    plugin.notifyTeam(plugin.lang().prefixed("acmanager.updated", "names", String.join(", ", updated)));
                }
            });
        });
    }

    /** The newest file of a Modrinth project for Paper and this Minecraft version (null if none). */
    private JsonObject newest(String project, String mc) throws IOException, InterruptedException {
        String q = "?loaders=" + URLEncoder.encode("[\"paper\"]", StandardCharsets.UTF_8) + "&game_versions=" + URLEncoder.encode("[\"" + mc + "\"]", StandardCharsets.UTF_8);
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/" + project + "/version" + q))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", "Bastion-Anticheat/" + plugin.getPluginMeta().getVersion()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;
        JsonElement root = JsonParser.parseString(res.body());
        if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return null;
        JsonObject version = root.getAsJsonArray().get(0).getAsJsonObject();
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.isEmpty()) return null;
        for (JsonElement f : files) if (f.getAsJsonObject().has("primary") && f.getAsJsonObject().get("primary").getAsBoolean()) return f.getAsJsonObject();
        return files.get(0).getAsJsonObject();
    }

    private void download(String url, String sha512, File dest) throws IOException, InterruptedException {
        Path part = dest.toPath().resolveSibling(dest.getName() + ".part");
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Bastion-Anticheat/" + plugin.getPluginMeta().getVersion()).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
        try (InputStream in = res.body()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }
        // only a file with exactly Modrinth's fingerprint is used
        if (sha512.isBlank() || !sha512.equalsIgnoreCase(sha512(part))) {
            Files.deleteIfExists(part);
            throw new IOException("the file does not match Modrinth's fingerprint");
        }
        Files.move(part, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static String sha512(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}

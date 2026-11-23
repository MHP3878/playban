package dev.lumio;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The installed mods, as Fabric Loader itself knows them: id, name, version and a fingerprint of the jar
 * file. Worked out once in the background at start, so joining a server is never slowed down. Nothing
 * else on the computer is looked at.
 */
public final class ModList {
    public record Mod(String id, String name, String version, String sha256, String parent) {}

    private static CompletableFuture<List<Mod>> mods;

    private ModList() {}

    public static synchronized CompletableFuture<List<Mod>> get() {
        if (mods == null) mods = CompletableFuture.supplyAsync(ModList::collect);
        return mods;
    }

    private static List<Mod> collect() {
        List<Mod> out = new ArrayList<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            var meta = c.getMetadata();
            String sha = "";
            String parent = "";
            try {
                ModOrigin origin = c.getOrigin();
                if (origin.getKind() == ModOrigin.Kind.PATH) {
                    for (Path p : origin.getPaths()) {
                        if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                            sha = sha256(p);
                            break;
                        }
                    }
                } else if (origin.getKind() == ModOrigin.Kind.NESTED) {
                    parent = origin.getParentModId();
                }
            } catch (RuntimeException ignored) {
                // a mod from somewhere unusual: listed without a fingerprint
            }
            out.add(new Mod(meta.getId(), meta.getName(), meta.getVersion().getFriendlyString(), sha, parent));
        }
        out.sort(Comparator.comparing(Mod::id));
        return out;
    }

    private static String sha256(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }
}

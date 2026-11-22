package dev.modcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mods people often want to forbid, with what gives them away: their mod channels and the translation
 * keys of their key bindings (for the hidden sign check). Typed names are cleaned up first, so
 * "Bee Locator", "beelocator" and "beelocator-21.11.jar" all mean the same mod.
 */
public final class KnownMods {
    public record Mod(String id, String name, List<String> aliases, List<String> channels, List<String> keys, boolean client) {}

    private static Mod mod(String id, String name, List<String> aliases, List<String> channels, List<String> keys) {
        return new Mod(id, name, aliases, channels, keys, false);
    }

    private static Mod client(String id, String name, List<String> aliases, List<String> keys) {
        return new Mod(id, name, aliases, List.of(id), keys, true);
    }

    public static final List<Mod> ALL = List.of(
            client("meteor", "Meteor Client", List.of("meteorclient", "meteor-client"), List.of("key.meteor-client.open-gui", "key.category.meteor-client")),
            client("wurst", "Wurst", List.of("wurstclient"), List.of("key.wurst.zoom", "key.categories.wurst")),
            client("liquidbounce", "LiquidBounce", List.of("liquid"), List.of()),
            client("aristois", "Aristois", List.of(), List.of()),
            client("impact", "Impact", List.of(), List.of()),
            client("future", "Future Client", List.of("futureclient"), List.of()),
            client("rusherhack", "RusherHack", List.of("rusher"), List.of()),
            client("thunderhack", "ThunderHack", List.of(), List.of()),
            client("bleachhack", "BleachHack", List.of(), List.of()),
            client("inertia", "Inertia", List.of(), List.of()),
            client("coffeeclient", "Coffee Client", List.of("coffee"), List.of()),
            mod("baritone", "Baritone", List.of("baritoneapi"), List.of("baritone"), List.of()),
            mod("freecam", "Freecam", List.of("freecammod"), List.of("freecam"), List.of("key.freecam.toggle", "category.freecam.freecam")),
            mod("xray", "X-Ray mods", List.of("advancedxray", "advanced-xray", "xraymod", "xrayultimate"), List.of("xray", "advanced-xray"),
                    List.of("key.xray.toggle", "keybinding.category.xray")),
            mod("seedcracker", "SeedCrackerX", List.of("seedcrackerx", "seed-cracker"), List.of("seedcracker"), List.of()),
            mod("beelocator", "Bee Locator", List.of("bee-locator", "beefinder"), List.of("beelocator"), List.of()),
            mod("chestesp", "Chest ESP", List.of("chest-esp", "storageesp"), List.of("chestesp"), List.of()),
            mod("entityradar", "Entity Radar", List.of("radar"), List.of("entityradar"), List.of()),
            mod("xaerominimap", "Xaero's Minimap", List.of("xaero", "xaerosminimap", "minimap"), List.of("xaerominimap"), List.of()),
            mod("xaeroworldmap", "Xaero's World Map", List.of("worldmap", "xaerosworldmap"), List.of("xaeroworldmap"), List.of()),
            mod("journeymap", "JourneyMap", List.of(), List.of("journeymap"), List.of()),
            mod("voxelmap", "VoxelMap", List.of(), List.of("voxelmap"), List.of()),
            mod("litematica", "Litematica", List.of(), List.of("litematica", "servux"), List.of()),
            mod("tweakeroo", "Tweakeroo", List.of(), List.of("tweakeroo"), List.of()),
            mod("itemscroller", "Item Scroller", List.of("item-scroller"), List.of("itemscroller"), List.of()),
            mod("autoclicker", "Auto Clicker", List.of("auto-clicker"), List.of("autoclicker"), List.of()),
            mod("autofish", "AutoFish", List.of("auto-fish"), List.of("autofish"), List.of()),
            mod("inventoryprofilesnext", "Inventory Profiles Next", List.of("ipn", "inventoryprofiles"), List.of("inventoryprofilesnext"), List.of()),
            mod("wynntils", "Wynntils", List.of(), List.of("wynntils"), List.of()),
            mod("replaymod", "Replay Mod", List.of("replay"), List.of("replaymod"), List.of()));

    private static final Set<String> NOISE = Set.of("fabric", "forge", "neoforge", "quilt", "mc", "mod", "mods", "client", "universal",
            "release", "beta", "alpha", "snapshot", "jar", "v");

    private KnownMods() {}

    /** "Bee Locator", "beelocator-21.11.jar", "BeeLocator_fabric_1.21" all become "beelocator". */
    public static String normalize(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.endsWith(".jar")) s = s.substring(0, s.length() - 4);
        StringBuilder out = new StringBuilder();
        for (String token : s.split("[-_+ .]+")) {
            if (token.isEmpty()) continue;
            // a version or a loader name ends the mod's name
            if (Character.isDigit(token.charAt(0)) || NOISE.contains(token)) {
                if (out.length() > 0) break;
                continue;
            }
            out.append(token.replaceAll("[^a-z0-9]", ""));
        }
        return out.toString();
    }

    /** The known mod a name means, or null. */
    public static Mod find(String input) {
        String n = normalize(input);
        for (Mod m : ALL) {
            if (m.id().equals(n) || normalize(m.name()).equals(n)) return m;
            for (String a : m.aliases()) if (normalize(a).equals(n)) return m;
        }
        return null;
    }

    /** Known mods whose name is close to what was typed, closest first: for "did you mean". */
    public static List<Mod> suggest(String input) {
        String n = normalize(input);
        List<Mod> out = new ArrayList<>();
        if (n.isEmpty()) return out;
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < ALL.size(); i++) {
            Mod m = ALL.get(i);
            int best = distance(n, m.id());
            best = Math.min(best, distance(n, normalize(m.name())));
            for (String a : m.aliases()) best = Math.min(best, distance(n, normalize(a)));
            boolean contains = m.id().contains(n) || n.contains(m.id());
            if (best <= Math.max(1, n.length() / 4) || (contains && n.length() >= 3)) scored.add(new int[]{i, contains ? 0 : best});
        }
        scored.sort((a, b) -> Integer.compare(a[1], b[1]));
        for (int[] s : scored) out.add(ALL.get(s[0]));
        return out;
    }

    /** Translation keys a mod with this id probably has: guesses from the usual patterns. */
    public static List<String> guessKeys(String id) {
        return List.of("key.categories." + id, "key.category." + id, "category." + id, id + ".key.category");
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}

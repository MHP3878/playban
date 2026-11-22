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

    // The keys are taken from the mods' own language files (checked against their sources, Nov 2026).
    // "bind:" asks for a key binding instead of a text. Mods without texts (Lithium, FerriteCore,
    // ImmediatelyFast, Baritone) and Wurst (loads its texts outside the game's language) cannot be
    // seen by the sign check; channels and the client name still work for them.
    public static final List<Mod> ALL = List.of(
            client("meteor", "Meteor Client", List.of("meteorclient", "meteor-client"),
                    List.of("key.meteor-client.open-gui", "key.category.meteor-client.meteor-client", "bind:key.meteor-client.open-gui")),
            client("wurst", "Wurst", List.of("wurstclient"), List.of("key.wurst.zoom", "bind:key.wurst.zoom")),
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
            mod("freecam", "Freecam", List.of("freecammod"), List.of("freecam"), List.of("key.freecam.toggle", "key.category.freecam.controls")),
            mod("xray", "X-Ray mods", List.of("advancedxray", "advanced-xray", "xraymod", "xrayultimate"), List.of("xray", "advanced-xray"),
                    List.of("key.xray.toggle", "keybinding.category.xray")),
            mod("seedcracker", "SeedCrackerX", List.of("seedcrackerx", "seed-cracker"), List.of("seedcracker"), List.of("settings.antiAntiXrayExplained")),
            mod("beelocator", "Bee Locator", List.of("bee-locator", "beefinder"), List.of("beelocator"), List.of()),
            mod("chestesp", "Chest ESP", List.of("chest-esp", "storageesp"), List.of("chestesp"), List.of()),
            mod("entityradar", "Entity Radar", List.of("radar"), List.of("entityradar"), List.of()),
            mod("xaerominimap", "Xaero's Minimap", List.of("xaero", "xaerosminimap", "minimap"), List.of("xaerominimap"), List.of()),
            mod("xaeroworldmap", "Xaero's World Map", List.of("worldmap", "xaerosworldmap"), List.of("xaeroworldmap"), List.of()),
            mod("journeymap", "JourneyMap", List.of(), List.of("journeymap"), List.of()),
            mod("voxelmap", "VoxelMap", List.of(), List.of("voxelmap"), List.of()),
            mod("litematica", "Litematica", List.of(), List.of("litematica", "servux"), List.of("litematica.gui.label.material_list.title.available")),
            mod("tweakeroo", "Tweakeroo", List.of(), List.of("tweakeroo"), List.of("tweakeroo.gui.title.camera_preset_editor")),
            mod("itemscroller", "Item Scroller", List.of("item-scroller"), List.of("itemscroller"), List.of("itemscroller.hotkeys.category.hotkeys")),
            mod("minihud", "MiniHUD", List.of("mini-hud"), List.of("minihud"), List.of("minihud.gui.title.copy_file")),
            mod("autoclicker", "Auto Clicker", List.of("auto-clicker"), List.of("autoclicker"), List.of()),
            mod("autofish", "AutoFish", List.of("auto-fish"), List.of("autofish"), List.of()),
            mod("inventoryprofilesnext", "Inventory Profiles Next", List.of("ipn", "inventoryprofiles"), List.of("inventoryprofilesnext"), List.of()),
            mod("wynntils", "Wynntils", List.of(), List.of("wynntils"), List.of("command.wynntils.compass.description")),
            mod("replaymod", "Replay Mod", List.of("replay"), List.of("replaymod"), List.of("replaymod.gui.replayviewer", "key.replaymod.marker")),
            // everyday mods: not cheats, but good to see
            mod("sodium", "Sodium", List.of(), List.of(), List.of("sodium.options.pages.general", "sodium.option_impact.low")),
            mod("iris", "Iris Shaders", List.of("irisshaders"), List.of("iris"), List.of("iris.keybind.reload", "key.category.iris.keybinds")),
            mod("modmenu", "Mod Menu", List.of("mod-menu"), List.of(), List.of("modmenu.title", "key.modmenu.open_menu")),
            mod("clothconfig", "Cloth Config", List.of("cloth-config", "cloth"), List.of(), List.of("title.cloth-config.config", "category.cloth-config.scrolling")),
            mod("entityculling", "EntityCulling", List.of("entity-culling"), List.of(), List.of("text.entityculling.title", "key.entityculling.toggle")),
            mod("etf", "Entity Texture Features", List.of("entitytexturefeatures", "entity_texture_features"), List.of(),
                    List.of("config.entity_texture_features.title")),
            mod("emf", "Entity Model Features", List.of("entitymodelfeatures", "entity_model_features"), List.of(),
                    List.of("entity_model_features.title")),
            mod("sodiumextra", "Sodium Extra", List.of("sodium-extra"), List.of(), List.of("modmenu.descriptionTranslation.sodium-extra")),
            mod("placeholderapi", "Placeholder API", List.of("placeholder-api", "textplaceholderapi"), List.of(),
                    List.of("modmenu.descriptionTranslation.placeholder-api")),
            mod("immediatelyfast", "ImmediatelyFast", List.of("immediately-fast"), List.of(), List.of()),
            mod("lithium", "Lithium", List.of(), List.of(), List.of()),
            mod("ferritecore", "FerriteCore", List.of("ferrite-core"), List.of(), List.of()),
            mod("zoomify", "Zoomify", List.of(), List.of(), List.of("key.category.zoomify.category")),
            mod("appleskin", "AppleSkin", List.of("apple-skin"), List.of("appleskin"), List.of()),
            mod("simplevoicechat", "Simple Voice Chat", List.of("voicechat", "simple-voice-chat"), List.of("voicechat"),
                    List.of("key.category.voicechat.voicechat", "key.voice_chat")));

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

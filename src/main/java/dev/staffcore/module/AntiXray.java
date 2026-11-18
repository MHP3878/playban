package dev.staffcore.module;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Switches on Paper's own anti-xray, the strongest protection there is against x-ray packs and debris
 * finders: hidden ores are sent to the client as stone (engine mode 2 even sends fake ores everywhere),
 * so the cheat has nothing to show. Writes config/paper-world-defaults.yml and, for the nether,
 * world_nether/paper-world.yml; a backup is kept. Takes effect after a restart.
 */
public final class AntiXray {
    private AntiXray() {}

    public static final List<String> OVERWORLD_HIDDEN = List.of("copper_ore", "deepslate_copper_ore", "raw_copper_block", "gold_ore",
            "deepslate_gold_ore", "iron_ore", "deepslate_iron_ore", "raw_iron_block", "coal_ore", "deepslate_coal_ore", "lapis_ore",
            "deepslate_lapis_ore", "mossy_cobblestone", "obsidian", "chest", "diamond_ore", "deepslate_diamond_ore", "redstone_ore",
            "deepslate_redstone_ore", "clay", "emerald_ore", "deepslate_emerald_ore", "ender_chest", "spawner", "amethyst_block", "budding_amethyst");
    public static final List<String> OVERWORLD_REPLACE = List.of("stone", "oak_planks", "deepslate");
    public static final List<String> NETHER_HIDDEN = List.of("ancient_debris", "bone_block", "glowstone", "magma_block", "nether_bricks",
            "nether_gold_ore", "nether_quartz_ore", "polished_blackstone_bricks", "gilded_blackstone");
    public static final List<String> NETHER_REPLACE = List.of("basalt", "blackstone", "gravel", "netherrack", "soul_sand", "soul_soil");

    private static void backup(File f) throws IOException {
        if (f.exists()) Files.copy(f.toPath(), new File(f.getPath() + ".staffcore-backup").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void write(File f, boolean on, int maxHeight, List<String> hidden, List<String> replace) throws IOException {
        YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        String p = "anticheat.anti-xray.";
        cfg.set(p + "enabled", on);
        if (on) {
            cfg.set(p + "engine-mode", 2);
            cfg.set(p + "max-block-height", maxHeight);
            cfg.set(p + "update-radius", 2);
            cfg.set(p + "lava-obscures", false);
            cfg.set(p + "use-permission", false);
            cfg.set(p + "hidden-blocks", hidden);
            cfg.set(p + "replacement-blocks", replace);
        }
        f.getParentFile().mkdirs();
        cfg.save(f);
    }

    /** Returns the files that were written. */
    public static List<String> set(File serverFolder, String netherWorld, boolean on) throws IOException {
        File defaults = new File(serverFolder, "config/paper-world-defaults.yml");
        backup(defaults);
        write(defaults, on, 64, OVERWORLD_HIDDEN, OVERWORLD_REPLACE);
        File nether = new File(serverFolder, netherWorld + "/paper-world.yml");
        backup(nether);
        write(nether, on, 128, NETHER_HIDDEN, NETHER_REPLACE);
        return List.of(defaults.getPath(), nether.getPath());
    }

    /** Whether the defaults file has it switched on. */
    public static boolean enabled(File serverFolder) {
        File defaults = new File(serverFolder, "config/paper-world-defaults.yml");
        return defaults.exists() && YamlConfiguration.loadConfiguration(defaults).getBoolean("anticheat.anti-xray.enabled", false);
    }
}

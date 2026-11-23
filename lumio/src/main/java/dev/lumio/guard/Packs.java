package dev.lumio.guard;

import dev.lumio.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The resource packs a player has switched on (their own, from the resourcepacks folder; not the
 * server's), and whether one of them is an x-ray pack: it makes stone, deepslate, dirt and the like
 * see-through or empty so ores show through walls. Only names and the finding are sent, no files.
 */
public final class Packs {
    public record Info(String id, String name, boolean xray, String why) {}

    /** Blocks that fill the ground. A pack that makes them invisible is for seeing ores. */
    private static final List<String> FILLER = List.of("stone", "deepslate", "dirt", "netherrack", "andesite", "diorite", "granite", "tuff",
            "gravel", "cobblestone", "grass_block_side", "sand", "basalt_side", "blackstone");
    private static final Map<String, Info> SEEN = new ConcurrentHashMap<>();

    private Packs() {}

    /** The player's own packs that are switched on, each looked at once. */
    public static List<Info> active() {
        List<Info> out = new ArrayList<>();
        List<Pack> selected;
        try {
            selected = new ArrayList<>(Minecraft.getInstance().getResourcePackRepository().getSelectedPacks());
        } catch (RuntimeException e) {
            return out;
        }
        for (Pack p : selected) {
            String id = p.getId();
            // their own packs only: the game's, the mods' and the server's own are not theirs to choose
            if (id == null || !id.startsWith("file/")) continue;
            out.add(SEEN.computeIfAbsent(id, k -> look(p)));
        }
        return out;
    }

    private static Info look(Pack p) {
        String name = p.getTitle().getString();
        if (name.length() > 64) name = name.substring(0, 64);
        String why = "";
        int hidden = 0;
        try (PackResources res = p.open()) {
            for (String block : FILLER) {
                String reason = invisible(res, block);
                if (reason != null) {
                    hidden++;
                    if (why.isEmpty()) why = block + " " + reason;
                }
            }
        } catch (Exception e) {
            return new Info(p.getId(), name, false, "");
        }
        // one changed texture can be style; stone and its kind gone in several is for seeing ores
        boolean xray = hidden >= 2;
        return new Info(p.getId(), name, xray, xray ? hidden + "x, " + why : "");
    }

    /** Why this block would be invisible with the pack ("see-through", "empty model"), or null. */
    private static String invisible(PackResources res, String block) {
        try (InputStream in = Compat.packFile(res, "minecraft", "textures/block/" + block + ".png")) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null && img.getColorModel().hasAlpha()) {
                    int clear = 0, all = 0;
                    for (int y = 0; y < img.getHeight(); y++) {
                        for (int x = 0; x < img.getWidth(); x++) {
                            all++;
                            if ((img.getRGB(x, y) >>> 24) < 128) clear++;
                        }
                    }
                    if (all > 0 && clear * 2 > all) return "see-through";
                }
            }
        } catch (Exception ignored) {
            // not readable: not judged
        }
        String model = text(res, "models/block/" + block + ".json");
        if (model != null && (model.replaceAll("\\s", "").contains("\"elements\":[]") || model.contains("block/air") || model.contains("barrier"))) {
            return "empty model";
        }
        String state = text(res, "blockstates/" + block + ".json");
        if (state != null && (state.contains("block/air") || state.contains("\"minecraft:air\"") || state.contains("barrier"))) return "empty blockstate";
        return null;
    }

    private static String text(PackResources res, String path) {
        try (InputStream in = Compat.packFile(res, "minecraft", path)) {
            if (in == null) return null;
            byte[] b = in.readNBytes(64 * 1024);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** The packs as findings for the integrity report: an x-ray pack is sure, any other pack is only named. */
    public static List<Finding> findings() {
        List<Finding> out = new ArrayList<>();
        for (Info i : active()) out.add(new Finding("pack", i.xray(), i.name() + (i.xray() ? " (" + i.why() + ")" : ""), i.xray() ? "xray" : ""));
        return out;
    }
}

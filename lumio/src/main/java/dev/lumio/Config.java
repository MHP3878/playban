package dev.lumio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lumio's settings, kept in config/lumio.json. A broken file is replaced by the defaults. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    // ---- look
    /** Lumio's own size, the same on every GUI scale: 1.0 looks like GUI scale 2; smaller by default. */
    public double uiSize = 0.8;
    /** Lumio's size as screen pixels per unit: 1.5, 2 (normal), 2.5 or 3; whole and half pixels stay sharp. */
    public float uiPx = 2f;
    /** HUD elements snap to edges, the middle and each other while being moved. */
    public boolean snap = true;
    /** how HUD panels look: "glass" (see-through with a light edge), "solid" or "minimal" (no panel) */
    public String hudStyle = "solid";
    /** which look update this file has seen (1: the box is the standard again) */
    public int lookVersion;
    /** 1: the HUD was laid out anew for Lumio 0.8's sharp sizes */
    public int layoutVersion;
    /** a thin stripe in the theme's running colours at the left of each HUD panel */
    public boolean hudAccent = true;
    /** a shadow under HUD text */
    public boolean hudShadow = false;
    public String theme = "violet";
    public boolean sounds = true;
    /** where cards appear: "right" or "left" */
    public String cardSide = "right";

    // ---- extras
    /** zoom while the zoom key (C) is held; the mouse wheel zooms further */
    public boolean zoom = true;
    /** a card when a tool or a piece of armour is nearly broken */
    public boolean durabilityWarning = true;
    /** where you died last (per server or world), for the HUD element; null when there is none */
    public DeathSpot death;

    /** The place of the last death. */
    public static final class DeathSpot {
        public String where = "";
        public String dim = "";
        public double x, y, z;
        public long at;
    }

    // ---- HUD: position and settings of each element, by name
    public Map<String, HudSlot> hud = new LinkedHashMap<>();

    // ---- radial menu
    public List<WheelEntry> wheel = new ArrayList<>();

    // ---- chat: mark messages that name you or contain one of your words
    public boolean chatMentions = true;
    public boolean chatSound = true;
    public List<String> chatKeywords = new ArrayList<>();

    /** One HUD element: on or off, where (as a share of the screen, 0..1) and how big. */
    public static final class HudSlot {
        public boolean on = true;
        public double x;
        public double y;
        public double scale = 1.0;
        /** the dark rounded panel behind the element */
        public boolean bg = true;
        /** a soft glow in the theme colour around it */
        public boolean glow = false;

        public HudSlot() {}

        public HudSlot(boolean on, double x, double y) {
            this.on = on;
            this.x = x;
            this.y = y;
        }
    }

    /** One field of the radial menu: a name, an item as its picture, and what it does. */
    public static final class WheelEntry {
        public String name = "";
        public String icon = "minecraft:compass";
        /** "command", "chat" or "page" */
        public String type = "command";
        public String value = "";
        /** for "page": the fields of the next page */
        public List<WheelEntry> page = new ArrayList<>();

        public WheelEntry() {}

        public WheelEntry(String name, String icon, String type, String value) {
            this.name = name;
            this.icon = icon;
            this.type = type;
            this.value = value;
        }
    }

    public static Config get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("lumio.json");
    }

    private static Config load() {
        Config c = null;
        try {
            if (Files.exists(file())) {
                try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
                    c = GSON.fromJson(r, Config.class);
                }
            }
        } catch (Exception broken) {
            LumioClient.LOG.warn("config/lumio.json could not be read, using the defaults: {}", broken.getMessage());
        }
        if (c == null) c = new Config();
        c.fillDefaults();
        return c;
    }

    private void fillDefaults() {
        if (hud == null) hud = new LinkedHashMap<>();
        if (wheel == null) wheel = new ArrayList<>();
        if (theme == null || Theme.byId(theme) == null) theme = "violet";
        if (!"left".equals(cardSide)) cardSide = "right";
        // 0.7.1 had glass as the standard; the box was liked better, so everyone gets it back once
        if (lookVersion < 1) hudStyle = "solid";
        // 0.7.1 had the text shadow on: off again, it makes small text look blurred
        if (lookVersion < 2) hudShadow = false;
        lookVersion = 2;
        if (hudStyle == null || !java.util.List.of("glass", "solid", "minimal").contains(hudStyle)) hudStyle = "solid";
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            LumioClient.LOG.warn("config/lumio.json could not be saved: {}", e.getMessage());
        }
    }
}

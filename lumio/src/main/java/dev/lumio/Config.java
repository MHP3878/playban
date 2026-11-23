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
    public String theme = "violet";
    public boolean sounds = true;
    /** where cards appear: "right" or "left" */
    public String cardSide = "right";

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

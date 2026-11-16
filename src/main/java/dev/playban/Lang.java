package dev.playban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Lang {
    public static final List<String> BUNDLED = List.of("en", "de");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PlayBanPlugin plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    private String code = "en";

    public Lang(PlayBanPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String wanted) {
        for (String c : BUNDLED) {
            if (!new File(plugin.getDataFolder(), "lang/" + c + ".yml").exists()) {
                plugin.saveResource("lang/" + c + ".yml", false);
            }
        }
        File file = new File(plugin.getDataFolder(), "lang/" + wanted + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Language file lang/" + wanted + ".yml not found, using en.");
            wanted = "en";
            file = new File(plugin.getDataFolder(), "lang/en.yml");
        }
        code = wanted;
        cfg = YamlConfiguration.loadConfiguration(file);
        InputStream in = plugin.getResource("lang/" + wanted + ".yml");
        if (in == null) in = plugin.getResource("lang/en.yml");
        if (in != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    public String code() {
        return code;
    }

    public List<String> available() {
        List<String> codes = new ArrayList<>();
        File[] files = new File(plugin.getDataFolder(), "lang").listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) for (File f : files) codes.add(f.getName().substring(0, f.getName().length() - 4));
        codes.sort(String::compareTo);
        if (codes.isEmpty()) codes.add("en");
        return codes;
    }

    public boolean has(String key) {
        return cfg.contains(key);
    }

    private static String fill(String raw, Object... kv) {
        String out = raw;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String s = v instanceof Component c ? MM.serialize(c) : MM.escapeTags(String.valueOf(v));
            out = out.replace("{" + kv[i] + "}", s);
        }
        return out;
    }

    public String raw(String key) {
        if (cfg.isList(key)) return String.join("\n", cfg.getStringList(key));
        String s = cfg.getString(key);
        return s == null ? "<red>missing text: " + key : s;
    }

    public Component msg(String key, Object... kv) {
        return MM.deserialize(fill(raw(key), kv));
    }

    /** Plain text insert that is not a lang key, e.g. a custom task name with MiniMessage tags. */
    public Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    public List<Component> lore(String key, Object... kv) {
        List<Component> out = new ArrayList<>();
        if (!cfg.contains(key)) return out;
        String raw = raw(key);
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n", -1)) out.add(MM.deserialize(fill(line, kv)));
        return out;
    }

    public void send(CommandSender to, String key, Object... kv) {
        to.sendMessage(msg("prefix").append(msg(key, kv)));
    }

    public Component prefixed(String key, Object... kv) {
        return msg("prefix").append(msg(key, kv));
    }
}

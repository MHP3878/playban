package dev.bastion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Texts from lang/<code>.yml; values filled into {placeholders} are always plain text. */
public final class Lang {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private YamlConfiguration texts = new YamlConfiguration();
    private YamlConfiguration fallback = new YamlConfiguration();

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String code) {
        for (String c : List.of("en", "de")) {
            if (!new File(plugin.getDataFolder(), "lang/" + c + ".yml").exists()) plugin.saveResource("lang/" + c + ".yml", false);
        }
        File f = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (!f.exists()) f = new File(plugin.getDataFolder(), "lang/en.yml");
        texts = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource("lang/" + code + ".yml");
        if (in == null) in = plugin.getResource("lang/en.yml");
        if (in != null) fallback = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        texts.setDefaults(fallback);
    }

    public String raw(String key) {
        String s = texts.getString(key);
        return s == null ? "missing text: " + key : s;
    }

    private static TagResolver resolve(String template, Object... args) {
        List<TagResolver> r = new ArrayList<>();
        for (int i = 0; i + 1 < args.length; i += 2) r.add(Placeholder.unparsed(String.valueOf(args[i]), String.valueOf(args[i + 1])));
        return TagResolver.resolver(r);
    }

    private static String braces(String s, Object... args) {
        for (int i = 0; i + 1 < args.length; i += 2) s = s.replace("{" + args[i] + "}", "<" + args[i] + ">");
        return s;
    }

    public Component msg(String key, Object... args) {
        return MM.deserialize(braces(raw(key), args), resolve(key, args));
    }

    public List<Component> lore(String key, Object... args) {
        List<Component> out = new ArrayList<>();
        List<String> lines = texts.getStringList(key);
        if (lines.isEmpty() && texts.isString(key)) lines = List.of(raw(key));
        for (String l : lines) out.add(MM.deserialize(braces(l, args), resolve(key, args)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        return out;
    }

    public Component prefixed(String key, Object... args) {
        return msg("prefix").append(msg(key, args));
    }

    public void send(CommandSender to, String key, Object... args) {
        to.sendMessage(prefixed(key, args));
    }
}

package dev.staffcore;

import dev.staffcore.model.Category;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and saves the offense categories (categories.yml). */
public final class CategoryRegistry {
    public static final List<String> TYPES = List.of("WARN", "PLAYBAN", "SPECBAN", "BAN");
    private final StaffCorePlugin plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public CategoryRegistry(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "categories.yml");
    }

    public void load() {
        categories.clear();
        if (!file().exists()) plugin.saveResource("categories.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        ConfigurationSection root = cfg.getConfigurationSection("categories");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Category c = new Category();
            c.id = id;
            c.name = s.getString("name", id);
            Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
            c.icon = icon == null || !icon.isItem() ? Material.PAPER : icon;
            c.expireMs = Math.max(0, TimeUtil.parse(s.getString("expire-after", "0")));
            for (Map<?, ?> raw : s.getMapList("steps")) {
                String type = String.valueOf(raw.get("type")).toUpperCase();
                if (!TYPES.contains(type)) {
                    plugin.getLogger().warning("categories.yml: '" + id + "' has a step with an unknown type, skipped.");
                    continue;
                }
                String duration = raw.get("duration") == null ? "" : String.valueOf(raw.get("duration"));
                long ms = isPermanent(duration) ? 0 : TimeUtil.parse(duration);
                if (type.equals("PLAYBAN") && ms <= 0) {
                    plugin.getLogger().warning("categories.yml: '" + id + "' has a PLAYBAN step without a duration, using 1h.");
                    ms = 3_600_000L;
                }
                c.steps.add(new Category.Step(type, Math.max(0, ms)));
            }
            categories.put(id, c);
        }
    }

    public static boolean isPermanent(String text) {
        String t = text.trim().toLowerCase();
        return t.isEmpty() || t.equals("perm") || t.equals("permanent") || t.equals("forever");
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Category c : categories.values()) {
            String p = "categories." + c.id + ".";
            cfg.set(p + "name", c.name);
            cfg.set(p + "icon", c.icon.name());
            cfg.set(p + "expire-after", c.expireMs <= 0 ? "0" : TimeUtil.format(c.expireMs));
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Category.Step step : c.steps) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", step.type);
                if (step.type.equals("PLAYBAN")) m.put("duration", TimeUtil.format(step.durationMs));
                if (step.type.equals("BAN") || step.type.equals("SPECBAN")) m.put("duration", step.durationMs <= 0 ? "permanent" : TimeUtil.format(step.durationMs));
                steps.add(m);
            }
            cfg.set(p + "steps", steps);
        }
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save categories.yml: " + e.getMessage());
        }
    }

    public Category get(String id) {
        return id == null ? null : categories.get(id.toLowerCase());
    }

    public List<Category> all() {
        return new ArrayList<>(categories.values());
    }

    public Category create(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "category";
        String id = base;
        for (int i = 2; categories.containsKey(id); i++) id = base + "-" + i;
        Category c = new Category();
        c.id = id;
        c.name = name;
        c.steps.add(new Category.Step("WARN", 0));
        c.steps.add(new Category.Step("PLAYBAN", 86_400_000L));
        categories.put(id, c);
        save();
        return c;
    }

    public void delete(String id) {
        categories.remove(id);
        save();
    }
}

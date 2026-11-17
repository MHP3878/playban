package dev.staffcore;

import dev.staffcore.model.TaskDef;
import dev.staffcore.model.TaskType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and saves the server's task definitions (tasks.yml). */
public final class TaskRegistry {
    private final StaffCorePlugin plugin;
    private final Map<String, TaskDef> tasks = new LinkedHashMap<>();

    public TaskRegistry(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "tasks.yml");
    }

    public void load() {
        tasks.clear();
        if (!file().exists()) plugin.saveResource("tasks.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        ConfigurationSection root = cfg.getConfigurationSection("tasks");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            TaskDef d = new TaskDef();
            d.id = id;
            try {
                d.type = TaskType.valueOf(s.getString("type", "").toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("tasks.yml: task '" + id + "' has an unknown type, skipped.");
                continue;
            }
            d.enabled = s.getBoolean("enabled", true);
            d.name = s.getString("name", "");
            d.reduction = s.getString("reduction", "10%");
            if (!TaskDef.validReduction(d.reduction)) {
                plugin.getLogger().warning("tasks.yml: task '" + id + "' has an invalid reduction, using 10%.");
                d.reduction = "10%";
            }
            d.timeLimitMs = Math.max(0, TimeUtil.parse(s.getString("time-limit", "0")));
            d.amount = Math.max(1, s.getDouble("amount", 1));
            Material mat = Material.matchMaterial(s.getString("material", "COBBLESTONE"));
            d.material = mat == null || !mat.isItem() ? Material.COBBLESTONE : mat;
            d.target = "ANY".equalsIgnoreCase(s.getString("target")) ? "ANY" : "VICTIM";
            d.minLength = Math.max(1, s.getInt("min-length", 20));
            for (Map<?, ?> q : s.getMapList("questions")) {
                TaskDef.Question question = new TaskDef.Question();
                question.question = String.valueOf(q.get("question"));
                Object answers = q.get("answers");
                if (answers instanceof List<?> list) for (Object a : list) question.answers.add(String.valueOf(a));
                if (question.answers.size() >= 2) d.questions.add(question);
            }
            tasks.put(id, d);
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (TaskDef d : tasks.values()) {
            String p = "tasks." + d.id + ".";
            cfg.set(p + "type", d.type.name());
            cfg.set(p + "enabled", d.enabled);
            if (!d.name.isEmpty()) cfg.set(p + "name", d.name);
            cfg.set(p + "reduction", d.reduction);
            cfg.set(p + "time-limit", d.timeLimitMs <= 0 ? "0" : TimeUtil.format(d.timeLimitMs));
            switch (d.type) {
                case NICE_MESSAGE -> { cfg.set(p + "target", d.target); cfg.set(p + "min-length", d.minLength); }
                case STAFF_MESSAGE -> cfg.set(p + "min-length", d.minLength);
                case GIFT_MONEY -> { cfg.set(p + "target", d.target); cfg.set(p + "amount", d.amount); }
                case DONATE_ITEMS -> { cfg.set(p + "material", d.material.name()); cfg.set(p + "amount", (int) d.amount); }
                case QUIZ -> {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (TaskDef.Question q : d.questions) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("question", q.question);
                        m.put("answers", q.answers);
                        list.add(m);
                    }
                    cfg.set(p + "questions", list);
                }
            }
        }
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save tasks.yml: " + e.getMessage());
        }
    }

    public TaskDef get(String id) {
        return tasks.get(id);
    }

    public List<TaskDef> all() {
        return new ArrayList<>(tasks.values());
    }

    public TaskDef create(TaskType type) {
        String base = type.name().toLowerCase().replace('_', '-');
        String id = base;
        for (int i = 2; tasks.containsKey(id); i++) id = base + "-" + i;
        TaskDef d = new TaskDef();
        d.id = id;
        d.type = type;
        d.enabled = false;
        if (type == TaskType.DONATE_ITEMS) d.amount = 64;
        if (type == TaskType.GIFT_MONEY) d.amount = 100;
        tasks.put(id, d);
        save();
        return d;
    }

    public void delete(String id) {
        tasks.remove(id);
        save();
    }
}

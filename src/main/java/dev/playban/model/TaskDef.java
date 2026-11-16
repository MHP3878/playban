package dev.playban.model;

import dev.playban.TimeUtil;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** A task as configured by the server (tasks.yml). */
public final class TaskDef {
    public static final class Question {
        public String question;
        /** The first answer is the correct one; answers are shuffled when shown. */
        public List<String> answers = new ArrayList<>();
    }

    public String id;
    public TaskType type;
    public boolean enabled = true;
    public String name = "";
    public String reduction = "10%";
    public long timeLimitMs = 0;
    public double amount = 1;
    public Material material = Material.COBBLESTONE;
    public String target = "VICTIM";
    public int minLength = 20;
    public List<Question> questions = new ArrayList<>();

    public long reductionMs(long originalMs) {
        String r = reduction.trim();
        if (r.endsWith("%")) {
            try {
                double pct = Double.parseDouble(r.substring(0, r.length() - 1).trim());
                return (long) (originalMs * Math.max(0, Math.min(100, pct)) / 100.0);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return Math.max(0, TimeUtil.parse(r));
    }

    public static boolean validReduction(String r) {
        r = r.trim();
        if (r.endsWith("%")) {
            try {
                double pct = Double.parseDouble(r.substring(0, r.length() - 1).trim());
                return pct > 0 && pct <= 100;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return TimeUtil.parse(r) > 0;
    }
}

package dev.lumio.hud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lines the server sends for the HUD (e.g. the account balance): label and value, by id. */
public final class HudData {
    private static final Map<String, String[]> LINES = new LinkedHashMap<>();

    private HudData() {}

    public static void set(String id, String label, String value) {
        if (value == null || value.isEmpty()) LINES.remove(id);
        else LINES.put(id, new String[]{label, value});
        while (LINES.size() > 8) LINES.remove(LINES.keySet().iterator().next());
    }

    public static void clear() {
        LINES.clear();
    }

    public static List<String[]> lines() {
        return new ArrayList<>(LINES.values());
    }
}

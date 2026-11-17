package dev.staffcore.model;

import java.util.ArrayList;
import java.util.List;

/** A rank such as Owner or Mod: a power level, how it looks, and the permissions it gives. */
public final class Rank {
    public String id;
    public String name;
    /** Higher means more power. Nobody can change ranks at or above their own level. */
    public int level;
    /** Shown in front of the name in chat and the tab list (MiniMessage). */
    public String prefix = "";
    public final List<String> permissions = new ArrayList<>();
}

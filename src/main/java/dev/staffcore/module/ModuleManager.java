package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import dev.staffcore.command.DelegateCommand;
import dev.staffcore.gui.Items;
import dev.staffcore.gui.Menu;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Every bigger system in one place: homes, teleport requests, money, spawn, ranks, block log and so on.
 * In the menu a left-click switches a module on or off (its commands appear or disappear at once),
 * a right-click opens its settings.
 */
public final class ModuleManager {
    public record Cmd(String name, String description, String permission, TabExecutor executor, List<String> aliases) {
        public Cmd(String name, String description, String permission, TabExecutor executor) {
            this(name, description, permission, executor, List.of());
        }
    }

    public enum Kind { CYCLE, NUMBER, TEXT, BOOL }

    /** One setting of a module: a config path and how it is changed. */
    public record Setting(String path, Kind kind, Material icon, List<Object> values, Object fallback) {}

    public static final class Module {
        public final String id;
        final Material icon;
        final BooleanSupplier on;
        final Consumer<Boolean> store;
        final List<Cmd> commands = new ArrayList<>();
        final List<Setting> settings = new ArrayList<>();
        Consumer<Player> customSettings;
        Runnable whenOn = () -> {};
        Runnable whenOff = () -> {};
        final List<DelegateCommand> registered = new ArrayList<>();

        Module(String id, Material icon, BooleanSupplier on, Consumer<Boolean> store) {
            this.id = id;
            this.icon = icon;
            this.on = on;
            this.store = store;
        }

        public Module command(Cmd c) {
            commands.add(c);
            return this;
        }

        public Module setting(String path, Kind kind, Material icon, Object fallback, Object... values) {
            settings.add(new Setting(path, kind, icon, List.of(values), fallback));
            return this;
        }

        public Module settingsMenu(Consumer<Player> opener) {
            customSettings = opener;
            return this;
        }

        public Module onEnable(Runnable r) {
            whenOn = r;
            return this;
        }

        public Module onDisable(Runnable r) {
            whenOff = r;
            return this;
        }

        public boolean enabled() {
            return on.getAsBoolean();
        }
    }

    private final StaffCorePlugin plugin;
    private final Map<String, Module> modules = new LinkedHashMap<>();

    public ModuleManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    /** The module's name without colors, for chat lines and titles. */
    public String name(String id) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(lang().raw("module." + id + ".name"));
    }

    /** A module switched by a true/false entry in config.yml. */
    public Module add(String id, Material icon, String configKey, boolean byDefault) {
        return add(id, icon, () -> plugin.getConfig().getBoolean(configKey, byDefault), v -> {
            plugin.getConfig().set(configKey, v);
            plugin.saveConfig();
        });
    }

    public Module add(String id, Material icon, BooleanSupplier on, Consumer<Boolean> store) {
        Module m = new Module(id, icon, on, store);
        modules.put(id, m);
        return m;
    }

    public List<Module> all() {
        return new ArrayList<>(modules.values());
    }

    public Module get(String id) {
        return modules.get(id);
    }

    public boolean on(String id) {
        Module m = modules.get(id);
        return m != null && m.enabled();
    }

    /** Registers the commands of every module that is on. Called once on start. */
    public void startAll() {
        for (Module m : modules.values()) if (m.enabled()) start(m);
    }

    public void stopAll() {
        for (Module m : modules.values()) {
            for (DelegateCommand c : m.registered) plugin.unregisterCommand(c);
            m.registered.clear();
        }
    }

    private void start(Module m) {
        for (Cmd c : m.commands) m.registered.add(plugin.registerCommand(c.name(), c.description(), c.permission(), c.executor(), c.aliases()));
        m.whenOn.run();
    }

    public void toggle(Module m, String by) {
        boolean now = !m.enabled();
        m.store.accept(now);
        if (now) start(m);
        else {
            for (DelegateCommand c : m.registered) plugin.unregisterCommand(c);
            m.registered.clear();
            m.whenOff.run();
        }
        plugin.syncCommands();
        plugin.staffLog(by, now ? "module on" : "module off", null, m.id);
    }

    // ---------------------------------------------------------------- menus

    public void menu(Player p) {
        Menu menu = new Menu(6, lang().msg("module.title"));
        int slot = 10;
        for (Module m : modules.values()) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 44) break;
            boolean on = m.enabled();
            List<Component> lore = new ArrayList<>(lang().lore("module." + m.id + ".lore"));
            lore.add(Component.empty());
            if (!m.commands.isEmpty()) {
                StringBuilder cmds = new StringBuilder();
                for (Cmd c : m.commands) cmds.append(cmds.length() == 0 ? "/" : ", /").append(c.name());
                lore.addAll(plugin.adminMenus().textLore(lang().raw("module.commands") + " " + cmds));
            }
            lore.add(lang().msg(on ? "module.state-on" : "module.state-off"));
            lore.add(lang().msg("module.left"));
            lore.add(lang().msg(m.settings.isEmpty() && m.customSettings == null ? "module.right-none" : "module.right"));
            var item = Items.of(on ? m.icon : Material.GRAY_DYE, lang().msg("module." + m.id + ".name"), lore);
            if (on) {
                var meta = item.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
            menu.set(slot++, item, e -> {
                if (e.isRightClick()) {
                    if (m.customSettings != null) m.customSettings.accept(p);
                    else if (!m.settings.isEmpty()) settings(p, m);
                    return;
                }
                toggle(m, p.getName());
                p.playSound(p.getLocation(), m.enabled() ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
                lang().send(p, switchedKey(m), "module", name(m.id));
                menu(p);
            });
        }
        menu.set(49, Items.of(Material.OAK_DOOR, lang().msg("menu.back"), List.of()), e -> plugin.adminMenus().main(p));
        menu.fill().open(p);
    }

    private String shown(Setting s) {
        Object v = plugin.getConfig().get(s.path(), s.fallback());
        if (s.kind() == Kind.BOOL) return lang().raw(Boolean.TRUE.equals(v) ? "module.word-on" : "module.word-off");
        return String.valueOf(v);
    }

    public void settings(Player p, Module m) {
        Menu menu = new Menu(4, lang().msg("module.settings-title", "module", name(m.id)));
        int slot = 10;
        for (Setting s : m.settings) {
            List<Component> lore = new ArrayList<>(lang().lore("module-setting." + s.path() + ".lore"));
            lore.add(Component.empty());
            lore.add(lang().msg("module.value", "value", shown(s)));
            lore.add(lang().msg(s.kind() == Kind.CYCLE || s.kind() == Kind.BOOL ? "module.click-cycle" : "module.click-type"));
            menu.set(slot++, Items.of(s.icon(), lang().msg("module-setting." + s.path() + ".name"), lore), e -> {
                switch (s.kind()) {
                    case BOOL -> {
                        plugin.getConfig().set(s.path(), !Boolean.TRUE.equals(plugin.getConfig().get(s.path(), s.fallback())));
                        plugin.saveConfig();
                        settings(p, m);
                    }
                    case CYCLE -> {
                        Object now = plugin.getConfig().get(s.path(), s.fallback());
                        int i = -1;
                        for (int k = 0; k < s.values().size(); k++) if (String.valueOf(s.values().get(k)).equals(String.valueOf(now))) i = k;
                        plugin.getConfig().set(s.path(), s.values().get((i + 1) % s.values().size()));
                        plugin.saveConfig();
                        settings(p, m);
                    }
                    default -> plugin.input().ask(p, "module.prompt", text -> {
                        if (s.kind() == Kind.NUMBER) {
                            try {
                                plugin.getConfig().set(s.path(), Math.max(0, Double.parseDouble(text.trim().replace(",", "."))));
                            } catch (NumberFormatException ex) {
                                lang().send(p, "error.bad-number", "input", text);
                            }
                        } else {
                            String v = text.trim();
                            if (!v.isEmpty() && v.length() <= 16) plugin.getConfig().set(s.path(), v);
                        }
                        plugin.saveConfig();
                        settings(p, m);
                    }, () -> settings(p, m));
                }
            });
        }
        menu.set(31, Items.of(Material.OAK_DOOR, lang().msg("menu.back"), List.of()), e -> menu(p));
        menu.fill().open(p);
    }

    /** For the console: /sc modules and /sc module <id> on|off. */
    public void list(org.bukkit.command.CommandSender s) {
        for (Module m : modules.values()) {
            s.sendMessage(lang().msg("module.line", "module", m.id, "name", name(m.id),
                    "state", lang().raw(m.enabled() ? "module.word-on" : "module.word-off")));
        }
    }

    /** The message after switching: modules without commands do not talk about commands. */
    public String switchedKey(Module m) {
        return (m.enabled() ? "module.switched-on" : "module.switched-off") + (m.commands.isEmpty() ? "-plain" : "");
    }

    public boolean set(String id, boolean value, String by) {
        Module m = modules.get(id.toLowerCase());
        if (m == null) return false;
        if (m.enabled() != value) toggle(m, by);
        return true;
    }

    public static void playersUpdate() {
        for (Player online : Bukkit.getOnlinePlayers()) online.updateCommands();
    }
}

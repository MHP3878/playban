package dev.staffcore.module;

import dev.staffcore.Lang;
import dev.staffcore.StaffCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple money system: /money, /pay, /baltop and /eco. With Vault installed it also serves as the
 * server's economy, so shops and other plugins use the same balances. That works without Vault on the
 * build path: the Vault interface is answered at runtime through a proxy.
 */
public final class MoneyManager implements TabExecutor {
    private final StaffCorePlugin plugin;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private Object vaultProvider;

    public MoneyManager(StaffCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "money.yml");
    }

    public void load() {
        balances.clear();
        if (!file().exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        for (String k : cfg.getKeys(false)) {
            try {
                balances.put(UUID.fromString(k), cfg.getDouble(k));
            } catch (IllegalArgumentException ignored) {
                // broken entry
            }
        }
    }

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : balances.entrySet()) cfg.set(e.getKey().toString(), e.getValue());
        try {
            cfg.save(file());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save money.yml: " + e.getMessage());
        }
    }

    public double startBalance() {
        return Math.max(0, plugin.getConfig().getDouble("money.start", 100));
    }

    public double balance(UUID id) {
        return balances.getOrDefault(id, startBalance());
    }

    public boolean has(UUID id, double amount) {
        return balance(id) >= amount;
    }

    public synchronized void set(UUID id, double amount) {
        balances.put(id, Math.max(0, round(amount)));
        dirty = true;
    }

    public synchronized boolean withdraw(UUID id, double amount) {
        if (amount < 0 || balance(id) < amount) return false;
        set(id, balance(id) - amount);
        return true;
    }

    public synchronized void deposit(UUID id, double amount) {
        if (amount > 0) set(id, balance(id) + amount);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public String format(double amount) {
        String symbol = plugin.getConfig().getString("money.symbol", "$");
        String number = amount == Math.floor(amount) ? String.format(Locale.ROOT, "%,.0f", amount) : String.format(Locale.ROOT, "%,.2f", amount);
        return plugin.getConfig().getBoolean("money.symbol-after", false) ? number + " " + symbol : symbol + number;
    }

    private UUID byName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> e : plugin.store().names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    private String nameOf(UUID id) {
        String n = plugin.store().names.get(id);
        if (n != null) return n;
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    private static double parse(String text) {
        try {
            double v = Double.parseDouble(text.replace(",", "."));
            return Double.isFinite(v) ? round(v) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- Vault

    /** Offers these balances to every plugin through Vault, unless another economy is already there. */
    public void hookVault() {
        if (vaultProvider != null || Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            if (Bukkit.getServicesManager().getRegistration(eco) != null) {
                plugin.getLogger().info("Another economy is already registered with Vault; StaffCore money keeps its own balances.");
                return;
            }
            Class<?> response = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            Class<?> typeClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse$ResponseType");
            var ctor = response.getConstructor(double.class, double.class, typeClass, String.class);
            Object success = Enum.valueOf(typeClass.asSubclass(Enum.class), "SUCCESS");
            Object failure = Enum.valueOf(typeClass.asSubclass(Enum.class), "FAILURE");
            Object notImplemented = Enum.valueOf(typeClass.asSubclass(Enum.class), "NOT_IMPLEMENTED");
            vaultProvider = Proxy.newProxyInstance(eco.getClassLoader(), new Class<?>[]{eco}, (proxy, method, args) -> {
                String m = method.getName();
                UUID id = args != null && args.length > 0 ? idOf(args[0]) : null;
                double amount = args != null && args.length > 1 && args[args.length - 1] instanceof Double d ? d : 0;
                return switch (m) {
                    case "isEnabled" -> plugin.isEnabled();
                    case "getName" -> "StaffCore";
                    case "hasBankSupport" -> false;
                    case "fractionalDigits" -> 2;
                    case "format" -> format((Double) args[0]);
                    case "currencyNamePlural", "currencyNameSingular" -> plugin.getConfig().getString("money.symbol", "$");
                    case "hasAccount", "createPlayerAccount" -> id != null;
                    case "getBalance" -> id == null ? 0.0 : balance(id);
                    case "has" -> id != null && has(id, amount);
                    case "withdrawPlayer" -> id != null && withdraw(id, amount)
                            ? ctor.newInstance(amount, balance(id), success, null)
                            : ctor.newInstance(0.0, id == null ? 0.0 : balance(id), failure, "Not enough money");
                    case "depositPlayer" -> {
                        if (id == null || amount < 0) yield ctor.newInstance(0.0, 0.0, failure, "No such player");
                        deposit(id, amount);
                        yield ctor.newInstance(amount, balance(id), success, null);
                    }
                    case "getBanks" -> new ArrayList<String>();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "StaffCore economy";
                    default -> method.getReturnType() == response ? ctor.newInstance(0.0, 0.0, notImplemented, "No banks") : null;
                };
            });
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class raw = eco;
            Bukkit.getServicesManager().register(raw, vaultProvider, plugin, ServicePriority.Normal);
            plugin.getLogger().info("StaffCore money is the economy for Vault now.");
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Could not offer StaffCore money to Vault: " + e.getMessage());
        }
    }

    public void unhookVault() {
        if (vaultProvider == null) return;
        Bukkit.getServicesManager().unregister(vaultProvider);
        vaultProvider = null;
    }

    private UUID idOf(Object o) {
        if (o instanceof OfflinePlayer op) return op.getUniqueId();
        if (o instanceof String s) return byName(s);
        return null;
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "money" -> {
                if (args.length > 0 && sender.hasPermission("staffcore.money.others")) {
                    UUID id = byName(args[0]);
                    if (id == null) lang().send(sender, "money.unknown", "player", args[0]);
                    else lang().send(sender, "money.other", "player", nameOf(id), "amount", format(balance(id)));
                } else if (sender instanceof Player p) lang().send(p, "money.own", "amount", format(balance(p.getUniqueId())));
                else lang().send(sender, "money.usage");
            }
            case "pay" -> {
                if (!(sender instanceof Player p)) {
                    lang().send(sender, "error.players-only");
                    return true;
                }
                if (args.length < 2) {
                    lang().send(p, "money.usage-pay");
                    return true;
                }
                UUID to = byName(args[0]);
                double amount = parse(args[1]);
                if (to == null) lang().send(p, "money.unknown", "player", args[0]);
                else if (to.equals(p.getUniqueId())) lang().send(p, "money.self");
                else if (amount <= 0) lang().send(p, "error.bad-number", "input", args[1]);
                else if (!withdraw(p.getUniqueId(), amount)) lang().send(p, "money.not-enough", "amount", format(balance(p.getUniqueId())));
                else {
                    deposit(to, amount);
                    lang().send(p, "money.paid", "player", nameOf(to), "amount", format(amount));
                    Player other = Bukkit.getPlayer(to);
                    if (other != null) {
                        lang().send(other, "money.received", "player", p.getName(), "amount", format(amount));
                        other.playSound(other.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
                    }
                }
            }
            case "baltop" -> {
                List<Map.Entry<UUID, Double>> top = new ArrayList<>(balances.entrySet());
                top.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
                lang().send(sender, "money.top-header");
                for (int i = 0; i < Math.min(10, top.size()); i++) {
                    sender.sendMessage(lang().msg("money.top-line", "rank", i + 1, "player", nameOf(top.get(i).getKey()), "amount", format(top.get(i).getValue())));
                }
                if (top.isEmpty()) lang().send(sender, "money.top-empty");
            }
            case "eco" -> {
                if (args.length < 3) {
                    lang().send(sender, "money.usage-eco");
                    return true;
                }
                UUID id = byName(args[1]);
                double amount = parse(args[2]);
                if (id == null) {
                    lang().send(sender, "money.unknown", "player", args[1]);
                    return true;
                }
                if (amount < 0) {
                    lang().send(sender, "error.bad-number", "input", args[2]);
                    return true;
                }
                switch (args[0].toLowerCase()) {
                    case "give" -> deposit(id, amount);
                    case "take" -> set(id, balance(id) - amount);
                    case "set" -> set(id, amount);
                    default -> {
                        lang().send(sender, "money.usage-eco");
                        return true;
                    }
                }
                lang().send(sender, "money.other", "player", nameOf(id), "amount", format(balance(id)));
                plugin.staffLog(sender.getName(), "eco " + args[0].toLowerCase(), nameOf(id), format(amount));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String cmd = command.getName().toLowerCase();
        String typed = args[args.length - 1].toLowerCase();
        int playerArg = cmd.equals("eco") ? 2 : 1;
        if (cmd.equals("eco") && args.length == 1) {
            for (String s : List.of("give", "take", "set")) if (s.startsWith(typed)) out.add(s);
        } else if (args.length == playerArg && !cmd.equals("baltop")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(typed)) out.add(p.getName());
        } else if (args.length == playerArg + 1 && (cmd.equals("pay") || cmd.equals("eco"))) {
            for (String s : List.of("10", "100", "1000")) if (s.startsWith(typed)) out.add(s);
        }
        return out;
    }
}

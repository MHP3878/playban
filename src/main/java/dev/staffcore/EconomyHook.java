package dev.staffcore;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/** Talks to Vault through reflection so PlayBan also runs on servers without it. */
public final class EconomyHook {
    private Object provider;
    private Method getBalance, withdraw, deposit, success;

    public boolean available() {
        if (provider != null) return true;
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(eco);
            if (rsp == null) return false;
            getBalance = eco.getMethod("getBalance", OfflinePlayer.class);
            withdraw = eco.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = eco.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            success = Class.forName("net.milkbowl.vault.economy.EconomyResponse").getMethod("transactionSuccess");
            provider = rsp.getProvider();
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public double balance(OfflinePlayer p) {
        try {
            return ((Number) getBalance.invoke(provider, p)).doubleValue();
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Moves money from one player to another. Returns false and changes nothing on failure. */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        try {
            if (!(boolean) success.invoke(withdraw.invoke(provider, from, amount))) return false;
            if (!(boolean) success.invoke(deposit.invoke(provider, to, amount))) {
                deposit.invoke(provider, from, amount);
                return false;
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}

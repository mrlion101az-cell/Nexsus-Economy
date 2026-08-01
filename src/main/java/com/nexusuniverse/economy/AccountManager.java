package com.nexusuniverse.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The single source of truth for every player's balance. Vault economy
 * calls, the shop, and (eventually) an auction house and stock market all
 * route through this -- not their own separate stashes of money. This is
 * deliberately the plugin's foundation: everything else in the ecosystem
 * builds on top of it rather than each keeping its own ledger.
 *
 * Saves synchronously on every change. That has a real cost on a busy
 * server, but for money specifically, never silently losing a write
 * matters more than shaving milliseconds -- an economy plugin that loses
 * a balance update on a crash is a much bigger problem than one that's a
 * little slower. If this becomes a measurable performance issue later,
 * batching writes is the natural next step, but correctness comes first.
 */
public class AccountManager {

    private final JavaPlugin plugin;
    private final File balancesFile;
    private final Map<UUID, Double> balances = new HashMap<>();
    private final double startingBalance;

    public AccountManager(JavaPlugin plugin, double startingBalance) {
        this.plugin = plugin;
        this.startingBalance = startingBalance;
        this.balancesFile = new File(plugin.getDataFolder(), "balances.yml");
        load();
    }

    private void load() {
        if (!balancesFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(balancesFile);
        for (String key : data.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                balances.put(id, data.getDouble(key));
            } catch (IllegalArgumentException ignored) {
                // skip malformed keys rather than fail the whole load
            }
        }
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
            data.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            data.save(balancesFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "NexusEconomy: failed to save balances.yml -- a balance change may not have persisted!", e);
        }
    }

    public boolean hasAccount(UUID id) {
        return balances.containsKey(id);
    }

    public double getBalance(UUID id) {
        return balances.getOrDefault(id, startingBalance);
    }

    public void createAccount(UUID id) {
        if (!balances.containsKey(id)) {
            balances.put(id, startingBalance);
            save();
        }
    }

    public boolean has(UUID id, double amount) {
        return getBalance(id) >= amount;
    }

    /** Returns false (and changes nothing) if the account doesn't have enough -- balances never go negative. */
    public boolean withdraw(UUID id, double amount) {
        double current = getBalance(id);
        if (current < amount) return false;
        balances.put(id, round2(current - amount));
        save();
        return true;
    }

    public void deposit(UUID id, double amount) {
        double current = getBalance(id);
        balances.put(id, round2(current + amount));
        save();
    }

    public void setBalance(UUID id, double amount) {
        balances.put(id, round2(amount));
        save();
    }

    /** Applies savings interest to every known account at or above the minimum balance. Returns how many accounts were paid. */
    public int applyInterest(double rate, double minimumBalance) {
        int paidCount = 0;
        for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
            double balance = entry.getValue();
            if (balance < minimumBalance) continue;
            double interest = round2(balance * rate);
            if (interest <= 0) continue;
            entry.setValue(round2(balance + interest));
            paidCount++;
        }
        if (paidCount > 0) save();
        return paidCount;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

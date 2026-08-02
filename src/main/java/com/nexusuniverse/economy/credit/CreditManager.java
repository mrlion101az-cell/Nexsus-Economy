package com.nexusuniverse.economy.credit;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * A real credit line: a limit, cash advances against it, compounding
 * interest on whatever's left unpaid at the end of a cycle, a minimum
 * payment due every cycle, a credit score that actually moves with your
 * payment history, and an account freeze if you fall behind too many
 * cycles running. "Charging" the card is modeled as a cash advance --
 * it deposits the charged amount straight into the player's spendable
 * bank balance while adding the same amount (plus future interest) to
 * what they owe. That's a deliberate scoping choice: it keeps this
 * self-contained rather than needing to rewire the shop's payment path
 * to support a second payment method.
 *
 * Billing cycles are driven externally -- see SeasonPoller and
 * NexusEconomyPlugin -- this class just runs one cycle when told to.
 */
public class CreditManager {

    private final Plugin plugin;
    private final AccountManager accounts;
    private final File dataFile;
    private final Map<UUID, CreditAccount> accountsByPlayer = new HashMap<>();

    public CreditManager(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.dataFile = new File(plugin.getDataFolder(), "credit.yml");
        load();
    }

    public CreditAccount getOrCreate(UUID playerId) {
        return accountsByPlayer.computeIfAbsent(playerId, id -> {
            double limit = plugin.getConfig().getDouble("credit.starting-limit", 500.0);
            int score = plugin.getConfig().getInt("credit.starting-score", 650);
            return new CreditAccount(id, limit, score);
        });
    }

    /** A cash advance: adds to what's owed AND deposits the same amount as spendable cash right now. */
    public boolean charge(Player player, double amount) {
        CreditAccount account = getOrCreate(player.getUniqueId());
        if (account.isFrozen()) return false;
        if (amount <= 0 || amount > account.availableCredit()) return false;

        account.setBalanceOwed(account.balanceOwed() + amount);
        accounts.deposit(player.getUniqueId(), amount);
        save();
        return true;
    }

    /**
     * Same eligibility rules as charge() (limit, frozen check), but does NOT deposit the amount
     * into the player's bank -- used when the charged amount is going straight to pay something
     * else (a NexusBudget bill, via /credit paybill, or an item purchase in the credit-card shop
     * menu, via CreditShopManager) rather than into the player's own pocket first. Charging $50
     * to pay a $50 bill (or buy a $50 item) this way means the player ends up owing their credit
     * account $50 with their bank balance completely untouched, not $50 richer on top of
     * whatever else happened.
     */
    public boolean chargeForExternalPayment(Player player, double amount) {
        CreditAccount account = getOrCreate(player.getUniqueId());
        if (account.isFrozen()) return false;
        if (amount <= 0 || amount > account.availableCredit()) return false;

        account.setBalanceOwed(account.balanceOwed() + amount);
        save();
        return true;
    }

    /** Pays down the balance from the player's bank; counts toward this cycle's minimum due. */
    public boolean pay(Player player, double amount) {
        CreditAccount account = getOrCreate(player.getUniqueId());
        if (amount <= 0) return false;
        double toPay = Math.min(amount, account.balanceOwed());
        if (toPay <= 0) return false;
        if (!accounts.withdraw(player.getUniqueId(), toPay)) return false;

        account.setBalanceOwed(account.balanceOwed() - toPay);
        account.addCyclePayment(toPay);
        if (account.balanceOwed() <= 0.001 && account.isFrozen()) {
            account.setFrozen(false); // paying off in full always lifts a freeze
        }
        save();
        return true;
    }

    /** Runs one billing cycle for every known account: judges last cycle, applies interest, sets the next minimum due. */
    public void runBillingCycle() {
        double interestRate = plugin.getConfig().getDouble("credit.monthly-interest-rate", 0.02);
        double minPercent = plugin.getConfig().getDouble("credit.minimum-payment-percent", 0.03);
        double minFloor = plugin.getConfig().getDouble("credit.minimum-payment-floor", 10.0);
        double lateFee = plugin.getConfig().getDouble("credit.late-fee", 25.0);
        int scorePenalty = plugin.getConfig().getInt("credit.score-penalty-per-missed-payment", 30);
        int scoreBonus = plugin.getConfig().getInt("credit.score-bonus-per-ontime-payment", 5);
        int freezeThreshold = plugin.getConfig().getInt("credit.freeze-after-missed-payments", 3);
        int minScore = plugin.getConfig().getInt("credit.min-score", 300);
        int maxScore = plugin.getConfig().getInt("credit.max-score", 850);

        for (CreditAccount account : accountsByPlayer.values()) {
            boolean hadDueLastCycle = account.currentCycleMinimumDue() > 0;
            boolean metLastCycle = account.currentCyclePaid() >= account.currentCycleMinimumDue();

            if (hadDueLastCycle) {
                if (metLastCycle) {
                    account.setCreditScore(Math.min(maxScore, account.creditScore() + scoreBonus));
                    account.setMissedPayments(Math.max(0, account.missedPayments() - 1)); // paying on time earns back a step of standing
                } else {
                    account.setBalanceOwed(account.balanceOwed() + lateFee);
                    account.setCreditScore(Math.max(minScore, account.creditScore() - scorePenalty));
                    account.setMissedPayments(account.missedPayments() + 1);
                    if (account.missedPayments() >= freezeThreshold) {
                        account.setFrozen(true);
                    }
                }
            }

            if (account.balanceOwed() > 0) {
                account.setBalanceOwed(account.balanceOwed() * (1 + interestRate));
            }

            double newMinimum = account.balanceOwed() > 0
                    ? Math.max(minFloor, account.balanceOwed() * minPercent)
                    : 0;
            account.setCurrentCycleMinimumDue(round2(newMinimum));
            account.resetCyclePaid();

            notifyStatement(account);
        }
        save();
    }

    private void notifyStatement(CreditAccount account) {
        if (account.balanceOwed() <= 0) return;
        Player player = Bukkit.getPlayer(account.playerId());
        if (player == null) return;

        player.sendMessage("§6§lCredit Statement: §fBalance $" + String.format("%.2f", account.balanceOwed())
                + " §7| Minimum due: §c$" + String.format("%.2f", account.currentCycleMinimumDue())
                + " §7| Score: §f" + account.creditScore());
        if (account.isFrozen()) {
            player.sendMessage("§c§lYour credit account is FROZEN due to missed payments. Pay down your balance to lift it.");
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : data.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection section = data.getConfigurationSection(key);
                if (section == null) continue;

                CreditAccount account = new CreditAccount(id, section.getDouble("limit"), section.getInt("score"));
                account.setBalanceOwed(section.getDouble("owed"));
                account.setCurrentCycleMinimumDue(section.getDouble("minimumDue"));
                account.addCyclePayment(section.getDouble("cyclePaid"));
                account.setMissedPayments(section.getInt("missedPayments"));
                account.setFrozen(section.getBoolean("frozen"));
                accountsByPlayer.put(id, account);
            } catch (Exception ignored) {
                // skip a malformed entry rather than fail the whole load
            }
        }
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (CreditAccount account : accountsByPlayer.values()) {
            String key = account.playerId().toString();
            data.set(key + ".limit", account.creditLimit());
            data.set(key + ".owed", account.balanceOwed());
            data.set(key + ".minimumDue", account.currentCycleMinimumDue());
            data.set(key + ".cyclePaid", account.currentCyclePaid());
            data.set(key + ".score", account.creditScore());
            data.set(key + ".missedPayments", account.missedPayments());
            data.set(key + ".frozen", account.isFrozen());
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: failed to save credit.yml", e);
        }
    }
}

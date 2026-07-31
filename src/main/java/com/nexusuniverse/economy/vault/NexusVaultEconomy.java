package com.nexusuniverse.economy.vault;

import com.nexusuniverse.economy.AccountManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Implements Vault's Economy interface against our own AccountManager, so
 * any plugin that talks to Vault (shops, quest rewards, whatever else is
 * installed) is actually reading and writing OUR Bank, not a separate
 * balance somewhere else -- this is what makes "the bank has all the
 * money instead of the shop" actually true for the whole server, not
 * just for our own plugins.
 *
 * Vault's "bank account" concept (createBank/bankDeposit/etc -- a SHARED
 * account multiple players can own together) is a different thing from
 * what this plugin calls "the Bank" (each player's own balance). That
 * shared-account feature isn't supported here, matching how most economy
 * plugins behave -- hasBankSupport() reports that honestly, and the
 * bank-account methods return NOT_IMPLEMENTED rather than silently doing
 * nothing.
 */
public class NexusVaultEconomy implements Economy {

    private final AccountManager accounts;
    private final String currencySingular;
    private final String currencyPlural;
    private final int fractionalDigits;

    public NexusVaultEconomy(AccountManager accounts, String currencySingular, String currencyPlural, int fractionalDigits) {
        this.accounts = accounts;
        this.currencySingular = currencySingular;
        this.currencyPlural = currencyPlural;
        this.fractionalDigits = fractionalDigits;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "NexusEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return fractionalDigits;
    }

    @Override
    public String format(double amount) {
        return String.format("$%,.2f", amount);
    }

    @Override
    public String currencyNamePlural() {
        return currencyPlural;
    }

    @Override
    public String currencyNameSingular() {
        return currencySingular;
    }

    @Override
    @Deprecated
    public boolean hasAccount(String playerName) {
        return hasAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return accounts.hasAccount(player.getUniqueId());
    }

    @Override
    @Deprecated
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    @Deprecated
    public double getBalance(String playerName) {
        return accounts.getBalance(Bukkit.getOfflinePlayer(playerName).getUniqueId());
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return accounts.getBalance(player.getUniqueId());
    }

    @Override
    @Deprecated
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    @Deprecated
    public boolean has(String playerName, double amount) {
        return accounts.has(Bukkit.getOfflinePlayer(playerName).getUniqueId(), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return accounts.has(player.getUniqueId(), amount);
    }

    @Override
    @Deprecated
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Cannot withdraw a negative amount.");
        }
        UUID id = player.getUniqueId();
        accounts.createAccount(id);
        boolean success = accounts.withdraw(id, amount);
        if (!success) {
            return new EconomyResponse(0, accounts.getBalance(id), EconomyResponse.ResponseType.FAILURE, "Insufficient funds.");
        }
        return new EconomyResponse(amount, accounts.getBalance(id), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Cannot deposit a negative amount.");
        }
        UUID id = player.getUniqueId();
        accounts.createAccount(id);
        accounts.deposit(id, amount);
        return new EconomyResponse(amount, accounts.getBalance(id), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // --- Vault's shared "bank account" concept -- not supported, see class doc ---

    @Override
    @Deprecated
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    @Deprecated
    public EconomyResponse isBankOwner(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    @Deprecated
    public EconomyResponse isBankMember(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }

    private EconomyResponse notImplemented() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported by NexusEconomy.");
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        accounts.createAccount(player.getUniqueId());
        return true;
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }
}

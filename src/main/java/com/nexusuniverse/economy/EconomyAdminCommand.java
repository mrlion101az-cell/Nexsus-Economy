package com.nexusuniverse.economy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class EconomyAdminCommand implements CommandExecutor {

    private final AccountManager accounts;

    public EconomyAdminCommand(AccountManager accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /economyadmin <set|add|remove> <player> <amount>");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "That's not a valid amount.");
            return true;
        }

        accounts.createAccount(target.getUniqueId());

        switch (args[0].toLowerCase()) {
            case "set" -> {
                accounts.setBalance(target.getUniqueId(), amount);
                sender.sendMessage(ChatColor.GREEN + target.getName() + "'s balance set to $" + String.format("%.2f", amount) + ".");
            }
            case "add" -> {
                accounts.deposit(target.getUniqueId(), amount);
                sender.sendMessage(ChatColor.GREEN + "Added $" + String.format("%.2f", amount) + " to " + target.getName() + ".");
            }
            case "remove" -> {
                if (!accounts.withdraw(target.getUniqueId(), amount)) {
                    sender.sendMessage(ChatColor.RED + target.getName() + " doesn't have that much.");
                    return true;
                }
                sender.sendMessage(ChatColor.GREEN + "Removed $" + String.format("%.2f", amount) + " from " + target.getName() + ".");
            }
            default -> sender.sendMessage(ChatColor.RED + "Usage: /economyadmin <set|add|remove> <player> <amount>");
        }
        return true;
    }
}
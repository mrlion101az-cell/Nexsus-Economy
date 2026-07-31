package com.nexusuniverse.economy;

import com.nexusuniverse.economy.bank.BankMenu;
import com.nexusuniverse.economy.cash.CashManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BankCommand implements CommandExecutor {

    private final AccountManager accounts;
    private final CashManager cashManager;
    private final BankMenu bankMenu;

    public BankCommand(AccountManager accounts, CashManager cashManager, BankMenu bankMenu) {
        this.accounts = accounts;
        this.cashManager = cashManager;
        this.bankMenu = bankMenu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            player.openInventory(bankMenu.build(player));
            return true;
        }

        if (args[0].equalsIgnoreCase("balance")) {
            accounts.createAccount(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "Balance: " + ChatColor.WHITE + "$" + String.format("%.2f", accounts.getBalance(player.getUniqueId())));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "pay" -> handlePay(player, args);
            case "withdraw" -> handleWithdraw(player, args);
            case "deposit" -> handleDeposit(player);
            default -> player.sendMessage(ChatColor.RED + "Usage: /bank [balance|pay <player> <amount>|withdraw <amount>|deposit] (no args opens the bank menu)");
        }
        return true;
    }

    private void handlePay(Player sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /bank pay <player> <amount>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return;
        }
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't pay yourself.");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "That's not a valid amount.");
            return;
        }
        if (amount <= 0) {
            sender.sendMessage(ChatColor.RED + "Amount must be positive.");
            return;
        }

        accounts.createAccount(sender.getUniqueId());
        accounts.createAccount(target.getUniqueId());

        if (!accounts.withdraw(sender.getUniqueId(), amount)) {
            sender.sendMessage(ChatColor.RED + "You don't have that much.");
            return;
        }
        accounts.deposit(target.getUniqueId(), amount);

        sender.sendMessage(ChatColor.GREEN + "Paid " + target.getName() + " $" + String.format("%.2f", amount) + ".");
        target.sendMessage(ChatColor.GREEN + sender.getName() + " paid you $" + String.format("%.2f", amount) + ".");
    }

    private void handleWithdraw(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /bank withdraw <amount>");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "That's not a valid amount.");
            return;
        }

        CashManager.Result result = cashManager.withdraw(player, amount);
        switch (result) {
            case SUCCESS -> player.sendMessage(ChatColor.GREEN + "Withdrew $" + String.format("%.0f", amount) + " as physical cash.");
            case INSUFFICIENT_FUNDS -> player.sendMessage(ChatColor.RED + "You don't have that much.");
            case NOT_WHOLE_DOLLARS -> player.sendMessage(ChatColor.RED + "Cash can only be withdrawn in whole dollars.");
            case INVENTORY_FULL -> player.sendMessage(ChatColor.RED + "Not enough inventory space for that many bills.");
        }
    }

    private void handleDeposit(Player player) {
        int deposited = cashManager.depositAll(player);
        if (deposited > 0) {
            player.sendMessage(ChatColor.GREEN + "Deposited $" + deposited + " in cash.");
        } else {
            player.sendMessage(ChatColor.RED + "You aren't carrying any cash.");
        }
    }
}

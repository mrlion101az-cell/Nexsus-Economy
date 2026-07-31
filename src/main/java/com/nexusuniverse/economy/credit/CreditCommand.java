package com.nexusuniverse.economy.credit;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CreditCommand implements CommandExecutor {

    private final CreditManager credit;

    public CreditCommand(CreditManager credit) {
        this.credit = credit;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "charge" -> handleCharge(player, args);
            case "pay" -> handlePay(player, args);
            case "payoff" -> handlePayoff(player);
            case "admin" -> handleAdmin(player, args);
            default -> player.sendMessage("§cUsage: /credit <status|charge <amount>|pay <amount>|payoff>");
        }
        return true;
    }

    private void sendStatus(Player player) {
        CreditAccount account = credit.getOrCreate(player.getUniqueId());
        player.sendMessage("§7--- Credit Account ---");
        player.sendMessage("§fBalance owed: §c$" + String.format("%.2f", account.balanceOwed()));
        player.sendMessage("§fCredit limit: §f$" + String.format("%.2f", account.creditLimit())
                + " §7(available: $" + String.format("%.2f", account.availableCredit()) + ")");
        player.sendMessage("§fMinimum due this cycle: §e$" + String.format("%.2f", account.currentCycleMinimumDue())
                + " §7(paid so far: $" + String.format("%.2f", account.currentCyclePaid()) + ")");
        player.sendMessage("§fCredit score: §f" + account.creditScore());
        if (account.isFrozen()) {
            player.sendMessage("§c§lFROZEN -- pay down your balance to lift it.");
        }
    }

    private void handleCharge(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /credit charge <amount>");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cAmount must be a number.");
            return;
        }
        if (credit.charge(player, amount)) {
            player.sendMessage("§aCharged $" + amount + " to your credit account -- deposited into your bank.");
        } else {
            player.sendMessage("§cCan't charge that -- over your limit, frozen, or an invalid amount.");
        }
    }

    private void handlePay(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /credit pay <amount>");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cAmount must be a number.");
            return;
        }
        if (credit.pay(player, amount)) {
            player.sendMessage("§aPaid $" + amount + " toward your credit balance.");
        } else {
            player.sendMessage("§cCouldn't make that payment -- check your bank balance and that you actually owe money.");
        }
    }

    private void handlePayoff(Player player) {
        CreditAccount account = credit.getOrCreate(player.getUniqueId());
        double owed = account.balanceOwed();
        if (owed <= 0) {
            player.sendMessage("§7You don't owe anything.");
            return;
        }
        if (credit.pay(player, owed)) {
            player.sendMessage("§aPaid off your full balance of $" + String.format("%.2f", owed) + ".");
        } else {
            player.sendMessage("§cYou don't have enough in your bank to pay off the full balance ($" + String.format("%.2f", owed) + ").");
        }
    }

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("nexuseconomy.admin")) {
            player.sendMessage("§cNo permission.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /credit admin <runcycle|setlimit <player> <amount>|unfreeze <player>>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "runcycle" -> {
                credit.runBillingCycle();
                player.sendMessage("§aForced a billing cycle for every credit account.");
            }
            case "setlimit" -> {
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /credit admin setlimit <player> <amount>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("§cPlayer not found.");
                    return;
                }
                try {
                    double limit = Double.parseDouble(args[3]);
                    credit.getOrCreate(target.getUniqueId()).setCreditLimit(limit);
                    credit.save();
                    player.sendMessage("§aSet " + target.getName() + "'s credit limit to $" + limit + ".");
                } catch (NumberFormatException e) {
                    player.sendMessage("§cAmount must be a number.");
                }
            }
            case "unfreeze" -> {
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /credit admin unfreeze <player>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("§cPlayer not found.");
                    return;
                }
                credit.getOrCreate(target.getUniqueId()).setFrozen(false);
                credit.save();
                player.sendMessage("§aUnfroze " + target.getName() + "'s credit account.");
            }
            default -> player.sendMessage("§cUsage: /credit admin <runcycle|setlimit <player> <amount>|unfreeze <player>>");
        }
    }
}
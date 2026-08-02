package com.nexusuniverse.economy.stocks;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class StockMarketCommand implements CommandExecutor {

    private final StockMarketManager market;

    public StockMarketCommand(StockMarketManager market) {
        this.market = market;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            handleList(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "price" -> handlePrice(player, args);
            case "buy" -> handleBuy(player, args);
            case "sell" -> handleSell(player, args);
            case "portfolio" -> handlePortfolio(player);
            default -> player.sendMessage("§cUsage: /stocks <list|price <symbol>|buy <symbol> <shares>|sell <symbol> <shares>|portfolio>");
        }
        return true;
    }

    private void handleList(Player player) {
        player.sendMessage("§7--- Stock Market ---");
        for (Stock stock : market.roster()) {
            String trend = stock.isUp() ? "§a\u25B2" : stock.isDown() ? "§c\u25BC" : "§7\u25AC";
            player.sendMessage("§f" + stock.key().toUpperCase() + " §7(" + stock.companyName() + ") "
                    + trend + " §f$" + String.format("%.2f", stock.currentPrice()));
        }
    }

    private void handlePrice(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /stocks price <symbol>");
            return;
        }
        Stock stock = market.getStock(args[1]);
        if (stock == null) {
            player.sendMessage("§cNo stock by that symbol.");
            return;
        }
        player.sendMessage("§f" + stock.companyName() + " (" + stock.key().toUpperCase() + "): §a$" + String.format("%.2f", stock.currentPrice()));
    }

    private void handleBuy(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /stocks buy <symbol> <shares>");
            return;
        }
        int shares;
        try {
            shares = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cShares must be a whole number.");
            return;
        }
        String error = market.buy(player, args[1], shares);
        if (error != null) {
            player.sendMessage("§c" + error);
        } else {
            player.sendMessage("§aBought " + shares + " share(s) of " + args[1].toUpperCase() + ".");
        }
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /stocks sell <symbol> <shares>");
            return;
        }
        int shares;
        try {
            shares = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cShares must be a whole number.");
            return;
        }
        String error = market.sell(player, args[1], shares);
        if (error != null) {
            player.sendMessage("§c" + error);
        } else {
            player.sendMessage("§aSold " + shares + " share(s) of " + args[1].toUpperCase() + ".");
        }
    }

    private void handlePortfolio(Player player) {
        Map<String, Holding> holdings = market.holdingsFor(player.getUniqueId());
        if (holdings.isEmpty()) {
            player.sendMessage("§7You don't own any stock.");
            return;
        }

        player.sendMessage("§7--- Your Portfolio ---");
        double totalValue = 0;
        for (Map.Entry<String, Holding> entry : holdings.entrySet()) {
            Stock stock = market.getStock(entry.getKey());
            if (stock == null) continue;
            Holding holding = entry.getValue();
            double currentValue = holding.shares() * stock.currentPrice();
            double unrealized = currentValue - holding.totalCostBasis();
            String sign = unrealized >= 0 ? "§a+" : "§c";

            player.sendMessage("§f" + stock.key().toUpperCase() + " §7x" + holding.shares()
                    + " §7@ avg $" + String.format("%.2f", holding.averageCost())
                    + " §7- now worth §f$" + String.format("%.2f", currentValue)
                    + " §7(" + sign + "$" + String.format("%.2f", Math.abs(unrealized)) + "§7)");
            totalValue += currentValue;
        }
        player.sendMessage("§7Total portfolio value: §f$" + String.format("%.2f", totalValue));
    }
}

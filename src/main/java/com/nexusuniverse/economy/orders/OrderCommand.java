package com.nexusuniverse.economy.orders;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class OrderCommand implements CommandExecutor {

    private final OrderBoardManager orders;

    public OrderCommand(OrderBoardManager orders) {
        this.orders = orders;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sendList(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "fulfill" -> handleFulfill(player, args);
            case "cancel" -> handleCancel(player, args);
            default -> player.sendMessage("§cUsage: /orders <list|create <material> <qty> <pay-per-item>|fulfill <id>|cancel <id>>");
        }
        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage("§cUsage: /orders create <material> <quantity> <pay-per-item>");
            return;
        }
        Material material;
        try {
            material = Material.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage("§cUnknown material.");
            return;
        }
        int quantity;
        double pay;
        try {
            quantity = Integer.parseInt(args[2]);
            pay = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cQuantity and price must be numbers.");
            return;
        }
        if (quantity <= 0 || pay <= 0) {
            player.sendMessage("§cQuantity and price must be positive.");
            return;
        }

        Order order = orders.createOrder(player, material, quantity, pay);
        if (order == null) {
            player.sendMessage("§cYou don't have enough to escrow this order (total: $" + String.format("%,.2f", quantity * pay) + ").");
            return;
        }
        player.sendMessage("§aOrder posted: " + quantity + "x " + material.name() + " @ $" + pay + " each. §7ID: " + order.id());
    }

    private void handleFulfill(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /orders fulfill <id>");
            return;
        }
        int fulfilled = orders.fulfill(player, args[1]);
        if (fulfilled <= 0) {
            player.sendMessage("§cCouldn't fulfill that order -- check the ID and that you're holding the right item.");
            return;
        }
        player.sendMessage("§aTurned in " + fulfilled + " item(s) and got paid.");
    }

    private void handleCancel(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /orders cancel <id>");
            return;
        }
        if (orders.cancelOrder(args[1], player.getUniqueId())) {
            player.sendMessage("§aOrder cancelled and refunded.");
        } else {
            player.sendMessage("§cCouldn't cancel that order -- it may not be yours.");
        }
    }

    private void sendList(Player player) {
        List<Order> active = orders.activeOrders();
        if (active.isEmpty()) {
            player.sendMessage("§7No active orders.");
            return;
        }
        player.sendMessage("§7--- Active Orders ---");
        for (Order order : active) {
            player.sendMessage("§f" + order.remainingQuantity() + "x " + order.material().name()
                    + " §7@ §a$" + order.payPerItem() + "§7 each -- posted by " + order.posterName()
                    + " §8[" + order.id().toString().substring(0, 8) + "]");
        }
        player.sendMessage("§7/orders fulfill <id> to turn in items (the short ID above works).");
    }
}

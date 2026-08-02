package com.nexusuniverse.economy.shop;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class ShopCommand implements CommandExecutor {

    private final ShopManager shopManager;

    public ShopCommand(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            if (shopManager.categories().isEmpty() && shopManager.customItems().isEmpty()) {
                player.sendMessage(ChatColor.RED + "The shop has no categories configured.");
                return true;
            }
            player.openInventory(shopManager.buildCategoryMenu());
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "addcustom" -> handleAddCustom(player, args);
            case "removecustom" -> handleRemoveCustom(player, args);
            case "addenchant" -> handleAddEnchant(player, args);
            case "removeenchant" -> handleRemoveEnchant(player, args);
            default -> player.sendMessage("§cUsage: /shop, /shop addcustom <buy-price>, /shop removecustom <index>, "
                    + "/shop addenchant <buy-price>, /shop removeenchant <index>");
        }
        return true;
    }

    private void handleAddCustom(Player player, String[] args) {
        if (!player.hasPermission("nexuseconomy.admin")) {
            player.sendMessage("§cNo permission.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /shop addcustom <buy-price> (hold the item you want to add)");
            return;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType().isAir()) {
            player.sendMessage("§cYou need to be holding the item you want to add to the Custom tab.");
            return;
        }
        double buy;
        try {
            buy = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cBuy price must be a number.");
            return;
        }
        if (buy <= 0) {
            player.sendMessage("§cBuy price must be positive.");
            return;
        }

        ItemStack toAdd = inHand.clone();
        toAdd.setAmount(1);
        shopManager.addCustomItem(toAdd, buy);
        player.sendMessage("§aAdded " + toAdd.getType() + " to the Custom tab for $" + buy + ".");
    }

    private void handleRemoveCustom(Player player, String[] args) {
        if (!player.hasPermission("nexuseconomy.admin")) {
            player.sendMessage("§cNo permission.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /shop removecustom <index> (see /shop, open Custom, count from 0)");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cIndex must be a number.");
            return;
        }
        if (shopManager.removeCustomItem(index)) {
            player.sendMessage("§aRemoved custom item #" + index + ".");
        } else {
            player.sendMessage("§cNo custom item at that index.");
        }
    }

    /**
     * Same as /shop addcustom, but for the Enchantments tab -- this is how NexusEnchants' own
     * custom enchant items (Lava Walker, Tide Walker boots, etc.) actually get into the shop:
     * hold the real, correctly-tagged item (e.g. from NexusEnchants' own give command) and price
     * it here. NexusEconomy has no way to reconstruct another plugin's custom NBT from scratch,
     * so this sidesteps needing to -- it just prices whatever real item you hand it.
     */
    private void handleAddEnchant(Player player, String[] args) {
        if (!player.hasPermission("nexuseconomy.admin")) {
            player.sendMessage("§cNo permission.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /shop addenchant <buy-price> (hold the item you want to add -- "
                    + "e.g. an enchanted book, or a custom NexusEnchants item)");
            return;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType().isAir()) {
            player.sendMessage("§cYou need to be holding the item you want to add to the Enchantments tab.");
            return;
        }
        double buy;
        try {
            buy = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cBuy price must be a number.");
            return;
        }
        if (buy <= 0) {
            player.sendMessage("§cBuy price must be positive.");
            return;
        }

        ItemStack toAdd = inHand.clone();
        toAdd.setAmount(1);
        shopManager.addEnchantItem(toAdd, buy);
        player.sendMessage("§aAdded " + toAdd.getType() + " to the Enchantments tab for $" + String.format("%,.2f", buy) + ".");
    }

    private void handleRemoveEnchant(Player player, String[] args) {
        if (!player.hasPermission("nexuseconomy.admin")) {
            player.sendMessage("§cNo permission.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /shop removeenchant <index> (see /shop, open Enchantments, count from 0)");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cIndex must be a number.");
            return;
        }
        if (shopManager.removeEnchantItem(index)) {
            player.sendMessage("§aRemoved enchantment tab item #" + index + ".");
        } else {
            player.sendMessage("§cNo item at that index.");
        }
    }
}


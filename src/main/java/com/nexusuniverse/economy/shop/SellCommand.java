package com.nexusuniverse.economy.shop;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A faster path to the same sell transactions the shop GUI already does -- /sell hand and
 * /sell all both go through ShopManager.sell(...) exactly like clicking an item in SELL mode
 * would, so pricing, sellability, and payout are identical either way. This exists purely for
 * speed: selling a full inventory one GUI click at a time is slow, especially across several
 * different materials at once.
 */
public class SellCommand implements CommandExecutor {

    private final ShopManager shopManager;

    public SellCommand(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /sell hand, /sell all");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "hand" -> handleSellHand(player);
            case "all" -> handleSellAll(player);
            default -> player.sendMessage(ChatColor.YELLOW + "Usage: /sell hand, /sell all");
        }
        return true;
    }

    private void handleSellHand(Player player) {
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "You're not holding anything.");
            return;
        }

        ShopItem item = shopManager.sellableItemFor(inHand.getType());
        if (item == null) {
            player.sendMessage(ChatColor.RED + "The shop doesn't buy that back.");
            return;
        }

        int sold = shopManager.sell(player, item, inHand.getAmount());
        if (sold > 0) {
            String itemName = item.material().name().toLowerCase().replace('_', ' ');
            player.sendMessage(ChatColor.GOLD + "Sold " + sold + "x " + itemName + ChatColor.GOLD
                    + " for $" + String.format("%,.2f", sold * item.sell()) + ".");
        } else {
            player.sendMessage(ChatColor.RED + "Couldn't sell that.");
        }
    }

    private void handleSellAll(Player player) {
        ShopManager.SellAllResult result = shopManager.sellAll(player);
        if (result.totalItems() == 0) {
            player.sendMessage(ChatColor.RED + "Nothing in your inventory is sellable here.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "Sold " + result.totalItems() + " item(s) across "
                + result.distinctMaterials() + " type(s) for a total of $"
                + String.format("%,.2f", result.totalPayout()) + ".");
    }
}

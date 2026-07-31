package com.nexusuniverse.economy.cash;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Physical cash: a plain paper item (no resource pack, no custom texture)
 * tagged in PDC with its exact dollar value, color-coded by denomination
 * for quick visual recognition -- a stack of ten $100 bills is genuinely
 * 1000 dollars sitting in an inventory slot, tradeable and stackable like
 * any other item.
 */
public class CashItems {

    private final NamespacedKey valueKey;

    public CashItems(Plugin plugin) {
        this.valueKey = new NamespacedKey(plugin, "cash_value");
    }

    public ItemStack create(int denomination) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(colorFor(denomination) + "" + ChatColor.BOLD + "$" + denomination + " Bill");
        meta.setLore(List.of(ChatColor.GRAY + "Redeemable at any bank.", ChatColor.DARK_GRAY + "/bank deposit"));
        meta.getPersistentDataContainer().set(valueKey, PersistentDataType.INTEGER, denomination);
        item.setItemMeta(meta);
        return item;
    }

    /** Returns the cash value of this item, or null if it isn't a NexusEconomy cash item. */
    public Integer readValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(valueKey, PersistentDataType.INTEGER);
    }

    private ChatColor colorFor(int denomination) {
        return switch (denomination) {
            case 1 -> ChatColor.WHITE;
            case 5 -> ChatColor.GREEN;
            case 10 -> ChatColor.AQUA;
            case 20 -> ChatColor.RED;
            case 50 -> ChatColor.LIGHT_PURPLE;
            case 100 -> ChatColor.GOLD;
            default -> ChatColor.WHITE;
        };
    }
}

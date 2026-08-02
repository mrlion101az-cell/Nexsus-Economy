package com.nexusuniverse.economy.credit;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * The physical credit card: a plain paper item (same "no resource pack needed" approach as
 * CashItems), PDC-tagged with the owning player's UUID so it's personal to their account rather
 * than a generic bearer instrument the way cash bills are. Right-click it while holding it to see
 * your statement (see CreditCardListener) -- it's a real, functional key into the account, not
 * just a flavor prop.
 */
public class CreditCardItems {

    private final NamespacedKey ownerKey;

    public CreditCardItems(Plugin plugin) {
        this.ownerKey = new NamespacedKey(plugin, "credit_card_owner");
    }

    public ItemStack create(UUID ownerId, String ownerName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Credit Card");
        meta.setLore(List.of(
                ChatColor.GRAY + "Issued to " + ChatColor.WHITE + ownerName,
                ChatColor.DARK_GRAY + "Right-click to check your statement.",
                ChatColor.DARK_GRAY + "/credit charge, /credit pay, /credit paybill"
        ));
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, ownerId.toString());
        item.setItemMeta(meta);
        return item;
    }

    /** The UUID of the player this specific card was issued to, or null if this item isn't a NexusEconomy credit card. */
    public UUID readOwner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

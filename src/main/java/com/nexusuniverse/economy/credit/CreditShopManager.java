package com.nexusuniverse.economy.credit;

import com.nexusuniverse.economy.shop.ShopCategory;
import com.nexusuniverse.economy.shop.ShopItem;
import com.nexusuniverse.economy.shop.ShopManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The menu that opens when a player right-clicks their credit card (see CreditCardListener):
 * the same categories and items as the main shop (ShopManager), same pricing, but buy-only --
 * there's no sell side to a credit card purchase -- and every purchase is charged straight to
 * the player's credit line via CreditManager.chargeForExternalPayment instead of withdrawing
 * Vault balance. Once a purchase would push them past their available credit, it's refused with
 * a message telling them to pay down their balance -- CreditAccount's existing limit/frozen logic
 * already enforces that for free, nothing new needed there.
 *
 * Same quantity-toggle pattern as the main shop (1/16/32/64 per click, shift-click for a full
 * stack) so stack purchases work here too, per the request that prompted this.
 */
public class CreditShopManager {

    private static final int ITEMS_PER_PAGE = 45;

    private final Plugin plugin;
    private final ShopManager shopManager;
    private final CreditManager creditManager;
    private final NamespacedKey categoryKeyTag;

    public CreditShopManager(Plugin plugin, ShopManager shopManager, CreditManager creditManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.creditManager = creditManager;
        this.categoryKeyTag = new NamespacedKey(plugin, "credit_shop_category_key");
    }

    public NamespacedKey categoryKeyTag() {
        return categoryKeyTag;
    }

    public Inventory buildCategoryMenu(UUID viewerId) {
        var categories = shopManager.categories();
        int size = Math.min(54, Math.max(9, ((categories.size() + 8) / 9) * 9));
        CreditShopHolder holder = new CreditShopHolder(CreditShopHolder.Type.CATEGORY_LIST, null, 0, 1);
        Inventory inv = Bukkit.createInventory(holder, size, ChatColor.AQUA + "" + ChatColor.BOLD
                + "Credit Card -- Categories" + ChatColor.GRAY + " (buy only)");
        holder.setInventory(inv);

        for (ShopCategory category : categories.values()) {
            ItemStack icon = new ItemStack(category.icon());
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + category.displayName());
            meta.setLore(List.of(ChatColor.GRAY + "" + category.items().size() + " items",
                    ChatColor.DARK_GRAY + "Charged to your credit card"));
            meta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, category.key());
            icon.setItemMeta(meta);
            inv.addItem(icon);
        }

        return inv;
    }

    public Inventory buildCategoryPage(UUID viewerId, String categoryKey, int page, int quantity) {
        ShopCategory category = shopManager.categories().get(categoryKey);
        if (category == null) return null;

        List<ShopItem> items = category.items();
        int totalPages = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        if (quantity <= 0) quantity = 1;

        CreditAccount account = creditManager.getOrCreate(viewerId);
        CreditShopHolder holder = new CreditShopHolder(CreditShopHolder.Type.ITEM_PAGE, categoryKey, page, quantity);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.AQUA + "" + ChatColor.BOLD + category.displayName()
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ") -- avail. $"
                + String.format("%,.0f", account.availableCredit()));
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(items.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            ShopItem shopItem = items.get(i);
            try {
                ItemStack display = new ItemStack(shopItem.material());
                ItemMeta meta = display.getItemMeta();
                meta.setDisplayName(ChatColor.WHITE + prettyName(shopItem.material()));

                List<String> lore = new ArrayList<>();
                double total = shopItem.buy() * quantity;
                boolean afford = total <= account.availableCredit();
                lore.add((afford ? ChatColor.AQUA : ChatColor.RED) + "Buy x" + quantity + ": $"
                        + String.format("%.2f", total) + ChatColor.GRAY + " (click)");
                if (!afford) {
                    lore.add(ChatColor.RED + "Over your available credit");
                }
                lore.add(ChatColor.DARK_GRAY + "Shift-click for a full stack (64)");
                lore.add(ChatColor.DARK_GRAY + "Charged to your credit card, not your bank");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                plugin.getLogger().warning("NexusEconomy: couldn't build credit-shop tile for "
                        + shopItem.material() + " in \"" + categoryKey + "\", skipping it.");
            }
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(52, navItem(Material.ARROW, "Next Page"));
        inv.setItem(51, quantityToggleItem(quantity));

        return inv;
    }

    private ItemStack quantityToggleItem(int quantity) {
        ItemStack item = new ItemStack(Material.PAPER, Math.min(64, Math.max(1, quantity)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Quantity: x" + quantity);
        meta.setLore(List.of(
                ChatColor.GRAY + "A plain click buys " + quantity + " at a time.",
                ChatColor.GRAY + "Click here to cycle to x" + ShopManager.nextQuantity(quantity) + "."
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack navItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + name);
        item.setItemMeta(meta);
        return item;
    }

    private String prettyName(Material material) {
        String raw = material.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}

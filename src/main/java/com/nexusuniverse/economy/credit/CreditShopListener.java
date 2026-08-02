package com.nexusuniverse.economy.credit;

import com.nexusuniverse.economy.shop.ShopCategory;
import com.nexusuniverse.economy.shop.ShopItem;
import com.nexusuniverse.economy.shop.ShopManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CreditShopListener implements Listener {

    private final CreditShopManager creditShop;
    private final ShopManager shopManager;
    private final CreditManager creditManager;
    private final Map<UUID, Integer> lastQuantity = new HashMap<>();

    public CreditShopListener(CreditShopManager creditShop, ShopManager shopManager, CreditManager creditManager) {
        this.creditShop = creditShop;
        this.shopManager = shopManager;
        this.creditManager = creditManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof CreditShopHolder holder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != topInventory) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;

        if (holder.type() == CreditShopHolder.Type.CATEGORY_LIST) {
            handleCategoryClick(player, clicked);
        } else {
            handleItemPageClick(player, holder, event, clicked);
        }
    }

    private void handleCategoryClick(Player player, ItemStack clicked) {
        String categoryKey = clicked.getItemMeta().getPersistentDataContainer()
                .get(creditShop.categoryKeyTag(), PersistentDataType.STRING);
        if (categoryKey == null) return;

        int quantity = lastQuantity.getOrDefault(player.getUniqueId(), 1);
        Inventory page = creditShop.buildCategoryPage(player.getUniqueId(), categoryKey, 0, quantity);
        if (page != null) player.openInventory(page);
    }

    private void handleItemPageClick(Player player, CreditShopHolder holder, InventoryClickEvent event, ItemStack clicked) {
        int slot = event.getSlot();

        if (slot == 49 && clicked.getType() == Material.BARRIER) {
            player.openInventory(creditShop.buildCategoryMenu(player.getUniqueId()));
            return;
        }
        if (slot == 45 && clicked.getType() == Material.ARROW) {
            player.openInventory(creditShop.buildCategoryPage(player.getUniqueId(), holder.categoryKey(), holder.page() - 1, holder.quantity()));
            return;
        }
        if (slot == 52 && clicked.getType() == Material.ARROW) {
            player.openInventory(creditShop.buildCategoryPage(player.getUniqueId(), holder.categoryKey(), holder.page() + 1, holder.quantity()));
            return;
        }
        if (slot == 51 && clicked.getType() == Material.PAPER) {
            int newQuantity = ShopManager.nextQuantity(holder.quantity());
            lastQuantity.put(player.getUniqueId(), newQuantity);
            player.openInventory(creditShop.buildCategoryPage(player.getUniqueId(), holder.categoryKey(), holder.page(), newQuantity));
            return;
        }
        if (slot >= 45) return;

        ShopCategory category = shopManager.categories().get(holder.categoryKey());
        if (category == null) return;

        int index = (holder.page() * 45) + slot;
        if (index < 0 || index >= category.items().size()) return;
        ShopItem item = category.items().get(index);

        ClickType click = event.getClick();
        int quantity = (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) ? 64 : holder.quantity();
        double total = item.buy() * quantity;
        String itemName = item.material().name().toLowerCase().replace('_', ' ');

        if (!creditManager.chargeForExternalPayment(player, total)) {
            player.sendMessage(ChatColor.RED + "Can't charge that to your credit card -- it's over your available "
                    + "credit, your account is frozen, or the amount's invalid. Pay down your balance with /credit pay.");
            return;
        }

        ItemStack toGive = new ItemStack(item.material(), quantity);
        var leftover = player.getInventory().addItem(toGive);
        for (ItemStack extra : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }

        player.sendMessage(ChatColor.AQUA + "Charged " + quantity + "x " + itemName + ChatColor.AQUA
                + " ($" + String.format("%.2f", total) + ") to your credit card.");

        // refresh the page in place so the available-credit total in the title and the
        // afford/can't-afford lore on every item stay accurate after this purchase
        Inventory refreshed = creditShop.buildCategoryPage(player.getUniqueId(), holder.categoryKey(), holder.page(), holder.quantity());
        if (refreshed != null) player.openInventory(refreshed);
    }
}

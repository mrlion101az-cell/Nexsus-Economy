package com.nexusuniverse.economy.shop;

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

public class ShopListener implements Listener {

    private final ShopManager shopManager;

    public ShopListener(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof ShopMenuHolder holder)) return;

        event.setCancelled(true); // never let items move in/out of shop GUIs
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != topInventory) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;

        if (holder.type() == ShopMenuHolder.Type.CATEGORY_LIST) {
            handleCategoryClick(player, clicked);
        } else if (holder.type() == ShopMenuHolder.Type.CUSTOM_PAGE) {
            handleCustomPageClick(player, holder, event, clicked);
        } else {
            handleItemPageClick(player, holder, event, clicked);
        }
    }

    private void handleCategoryClick(Player player, ItemStack clicked) {
        String categoryKey = clicked.getItemMeta().getPersistentDataContainer().get(shopManager.categoryKeyTag(), PersistentDataType.STRING);
        if (categoryKey == null) return;

        if (categoryKey.equals("Custom")) {
            player.openInventory(shopManager.buildCustomPage(0));
            return;
        }
        Inventory page = shopManager.buildCategoryPage(categoryKey, 0, shopManager.lastMode(player.getUniqueId()));
        if (page != null) player.openInventory(page);
    }

    private void handleCustomPageClick(Player player, ShopMenuHolder holder, InventoryClickEvent event, ItemStack clicked) {
        int slot = event.getSlot();

        if (slot == 49 && clicked.getType() == Material.BARRIER) {
            player.openInventory(shopManager.buildCategoryMenu());
            return;
        }
        if (slot == 45 && clicked.getType() == Material.ARROW) {
            player.openInventory(shopManager.buildCustomPage(holder.page() - 1));
            return;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            player.openInventory(shopManager.buildCustomPage(holder.page() + 1));
            return;
        }
        if (slot >= 45) return;

        int index = (holder.page() * 45) + slot;
        var entries = shopManager.customItems();
        if (index < 0 || index >= entries.size()) return;

        // custom items are unique/gimmick items from other Nexus plugins -- always buy one at a time,
        // rather than trying to reason about arbitrary max-stack-size interactions on a shift-click
        boolean bought = shopManager.buyCustom(player, entries.get(index), 1);
        if (bought) {
            player.sendMessage(ChatColor.GREEN + "Bought 1x " + entries.get(index).item().getType() + ChatColor.GREEN
                    + " for $" + String.format("%.2f", entries.get(index).buy()) + ".");
        } else {
            player.sendMessage(ChatColor.RED + "You can't afford that, or your inventory is full.");
        }
    }

    private void handleItemPageClick(Player player, ShopMenuHolder holder, InventoryClickEvent event, ItemStack clicked) {
        int slot = event.getSlot();

        if (slot == 49 && clicked.getType() == Material.BARRIER) {
            player.openInventory(shopManager.buildCategoryMenu());
            return;
        }
        if (slot == 45 && clicked.getType() == Material.ARROW) {
            player.openInventory(shopManager.buildCategoryPage(holder.categoryKey(), holder.page() - 1, holder.mode()));
            return;
        }
        if (slot == 52 && clicked.getType() == Material.ARROW) {
            player.openInventory(shopManager.buildCategoryPage(holder.categoryKey(), holder.page() + 1, holder.mode()));
            return;
        }
        if (slot == 53 && (clicked.getType() == Material.LIME_DYE || clicked.getType() == Material.RED_DYE)) {
            // the buy/sell toggle -- flips the mode and redraws the same page, mainly for
            // controller/Xbox players who only have one click and can't right-click to sell
            ShopMenuHolder.Mode newMode = holder.mode() == ShopMenuHolder.Mode.BUY
                    ? ShopMenuHolder.Mode.SELL : ShopMenuHolder.Mode.BUY;
            shopManager.setLastMode(player.getUniqueId(), newMode);
            player.openInventory(shopManager.buildCategoryPage(holder.categoryKey(), holder.page(), newMode));
            return;
        }
        if (slot >= 45) return; // rest of the nav row, nothing else there

        ShopCategory category = shopManager.categories().get(holder.categoryKey());
        if (category == null) return;

        int index = (holder.page() * 45) + slot;
        if (index < 0 || index >= category.items().size()) return;
        ShopItem item = category.items().get(index);

        ClickType click = event.getClick();
        int quantity = (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) ? 64 : 1;
        String itemName = item.material().name().toLowerCase().replace('_', ' ');

        boolean sellAction;
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            sellAction = true; // right-click always sells -- kept for mouse players as a shortcut
        } else if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
            sellAction = holder.mode() == ShopMenuHolder.Mode.SELL; // plain click follows the toggle
        } else {
            return; // ignore other click types (number keys, drop, double-click, etc.)
        }

        if (sellAction) {
            int sold = shopManager.sell(player, item, quantity);
            if (sold > 0) {
                player.sendMessage(ChatColor.GOLD + "Sold " + sold + "x " + itemName + ChatColor.GOLD
                        + " for $" + String.format("%.2f", sold * item.sell()) + ".");
            } else {
                player.sendMessage(ChatColor.RED + "You don't have any of that to sell (or it isn't sellable).");
            }
        } else {
            boolean bought = shopManager.buy(player, item, quantity);
            if (bought) {
                player.sendMessage(ChatColor.GREEN + "Bought " + quantity + "x " + itemName + ChatColor.GREEN
                        + " for $" + String.format("%.2f", quantity * item.buy()) + ".");
            } else {
                player.sendMessage(ChatColor.RED + "You can't afford that, or your inventory is full.");
            }
        }
    }
}

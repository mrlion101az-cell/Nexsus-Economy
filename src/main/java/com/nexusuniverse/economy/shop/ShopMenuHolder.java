package com.nexusuniverse.economy.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ShopMenuHolder implements InventoryHolder {

    public enum Type { CATEGORY_LIST, ITEM_PAGE, CUSTOM_PAGE, ENCHANT_PAGE }

    /**
     * BUY or SELL. Controls what a plain click (left-click on a mouse,
     * the only click a controller/Xbox player can do) does to an item
     * slot. Right-click always sells regardless of this, so mouse
     * players keep the old shortcut -- this is purely additive.
     */
    public enum Mode { BUY, SELL }

    private final Type type;
    private final String categoryKey;
    private final int page;
    private final Mode mode;
    private final int quantity;
    private Inventory inventory;

    public ShopMenuHolder(Type type, String categoryKey, int page) {
        this(type, categoryKey, page, Mode.BUY, 1);
    }

    public ShopMenuHolder(Type type, String categoryKey, int page, Mode mode) {
        this(type, categoryKey, page, mode, 1);
    }

    /**
     * @param quantity how many a plain click transacts on an ITEM_PAGE -- one of 1, 16, 32, 64,
     *                 selected via the quantity toggle button. Shift-click always transacts a
     *                 full stack (64) regardless of this, same as before. Not used by
     *                 CUSTOM_PAGE/ENCHANT_PAGE, which stay one-at-a-time -- see the comment on
     *                 ShopListener#handleCustomPageClick for why.
     */
    public ShopMenuHolder(Type type, String categoryKey, int page, Mode mode, int quantity) {
        this.type = type;
        this.categoryKey = categoryKey;
        this.page = page;
        this.mode = mode == null ? Mode.BUY : mode;
        this.quantity = quantity <= 0 ? 1 : quantity;
    }

    public Type type() {
        return type;
    }

    public String categoryKey() {
        return categoryKey;
    }

    public int page() {
        return page;
    }

    public Mode mode() {
        return mode;
    }

    public int quantity() {
        return quantity;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}

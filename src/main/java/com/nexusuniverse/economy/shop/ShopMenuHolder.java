package com.nexusuniverse.economy.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ShopMenuHolder implements InventoryHolder {

    public enum Type { CATEGORY_LIST, ITEM_PAGE, CUSTOM_PAGE }

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
    private Inventory inventory;

    public ShopMenuHolder(Type type, String categoryKey, int page) {
        this(type, categoryKey, page, Mode.BUY);
    }

    public ShopMenuHolder(Type type, String categoryKey, int page, Mode mode) {
        this.type = type;
        this.categoryKey = categoryKey;
        this.page = page;
        this.mode = mode == null ? Mode.BUY : mode;
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

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}

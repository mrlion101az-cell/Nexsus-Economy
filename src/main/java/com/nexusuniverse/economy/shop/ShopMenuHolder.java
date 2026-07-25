package com.nexusuniverse.economy.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ShopMenuHolder implements InventoryHolder {

    public enum Type { CATEGORY_LIST, ITEM_PAGE, CUSTOM_PAGE }

    private final Type type;
    private final String categoryKey;
    private final int page;
    private Inventory inventory;

    public ShopMenuHolder(Type type, String categoryKey, int page) {
        this.type = type;
        this.categoryKey = categoryKey;
        this.page = page;
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

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}

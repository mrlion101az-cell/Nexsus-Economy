package com.nexusuniverse.economy.credit;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class CreditShopHolder implements InventoryHolder {

    public enum Type { CATEGORY_LIST, ITEM_PAGE }

    private final Type type;
    private final String categoryKey;
    private final int page;
    private final int quantity;
    private Inventory inventory;

    public CreditShopHolder(Type type, String categoryKey, int page, int quantity) {
        this.type = type;
        this.categoryKey = categoryKey;
        this.page = page;
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

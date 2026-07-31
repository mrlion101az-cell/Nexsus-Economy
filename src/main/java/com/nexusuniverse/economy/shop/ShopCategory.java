package com.nexusuniverse.economy.shop;

import org.bukkit.Material;

import java.util.List;

public class ShopCategory {

    private final String key;
    private final String displayName;
    private final Material icon;
    private final List<ShopItem> items;

    public ShopCategory(String key, String displayName, Material icon, List<ShopItem> items) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.items = items;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    public List<ShopItem> items() {
        return items;
    }
}

package com.nexusuniverse.economy.shop;

import org.bukkit.Material;

public record ShopItem(Material material, double buy, double sell) {
    public boolean sellable() {
        return sell > 0;
    }
}

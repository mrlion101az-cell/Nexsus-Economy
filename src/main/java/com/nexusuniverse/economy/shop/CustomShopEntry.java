package com.nexusuniverse.economy.shop;

import org.bukkit.inventory.ItemStack;

/** Buy-only by design -- these carry custom NBT from other Nexus plugins, so there's no reliable generic way to price a sell-back. */
public record CustomShopEntry(ItemStack item, double buy) {
}

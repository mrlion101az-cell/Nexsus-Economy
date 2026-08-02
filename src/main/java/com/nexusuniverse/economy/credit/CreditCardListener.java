package com.nexusuniverse.economy.credit;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Right-click a physical credit card to open the credit-card shop menu (see CreditShopManager) --
 * a buy-only version of the main shop where every purchase is charged straight to the credit
 * line instead of the player's bank balance. Shift+right-click instead shows the plain-text
 * statement (the same output as /credit status), so both the "spend on credit" and "check my
 * account" functions stay reachable from the one item without either crowding the other out.
 */
public class CreditCardListener implements Listener {

    private final CreditCardItems cardItems;
    private final CreditCommand creditCommand;
    private final CreditShopManager creditShop;

    public CreditCardListener(CreditCardItems cardItems, CreditCommand creditCommand, CreditShopManager creditShop) {
        this.cardItems = cardItems;
        this.creditCommand = creditCommand;
        this.creditShop = creditShop;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        UUID owner = cardItems.readOwner(item);
        if (owner == null) return;

        Player player = event.getPlayer();
        // Deliberately only works for whoever the card was ISSUED to, not necessarily the player
        // holding it right now -- a traded/dropped/picked-up card still only reaches its actual
        // owner's account and shop, same as how a real credit card doesn't become someone else's
        // account just because they're holding it.
        if (!owner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.GRAY + "This card isn't yours -- it's issued to someone else's account.");
            return;
        }

        event.setCancelled(true); // don't also let a right-click-on-block interaction (e.g. opening a chest) fire underneath this
        if (player.isSneaking()) {
            creditCommand.sendStatus(player);
        } else {
            player.openInventory(creditShop.buildCategoryMenu(player.getUniqueId()));
        }
    }
}

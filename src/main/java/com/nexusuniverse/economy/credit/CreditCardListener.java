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

public class CreditCardListener implements Listener {

    private final CreditCardItems cardItems;
    private final CreditCommand creditCommand;

    public CreditCardListener(CreditCardItems cardItems, CreditCommand creditCommand) {
        this.cardItems = cardItems;
        this.creditCommand = creditCommand;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        UUID owner = cardItems.readOwner(item);
        if (owner == null) return;

        Player player = event.getPlayer();
        // Deliberately shows the statement for whoever the card was ISSUED to, not necessarily
        // the player holding it right now -- a traded/dropped/picked-up card still only reveals
        // its actual owner's account, same as how a real credit card doesn't become someone
        // else's account just because they're holding it.
        if (!owner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.GRAY + "This card isn't yours -- it's issued to someone else's account.");
            return;
        }

        creditCommand.sendStatus(player);
    }
}
